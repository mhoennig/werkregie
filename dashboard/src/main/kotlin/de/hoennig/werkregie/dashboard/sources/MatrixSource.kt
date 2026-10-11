package de.hoennig.werkregie.dashboard.sources

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.media.inMemory
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.repository.exposed.exposed
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.MatrixClientServerApiClientImpl
import de.connect2x.trixnity.clientserverapi.client.classicLoginWithPassword
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.MatrixConfig
import de.hoennig.werkregie.dashboard.Source
import io.ktor.http.Url
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Matrix messages, read with the user's own account as a device of its own, so that end-to-end
 * encrypted rooms can be read once the device has their keys. The client syncs in a thread of its
 * own; [fetch] only hands out what it collected. The rows survive a restart in `messages.json`.
 *
 * One client per machine: a relay and the local mode share the state directory, and two clients
 * with the same keys would spoil each other's sessions. The process that holds `sync.lock` syncs;
 * another one reads `messages.json` along and takes over once the lock is free.
 */
class MatrixSource(
    private val config: MatrixConfig,
    private val stateDir: Path,
    private val mapper: ObjectMapper,
) : Source {
    override val id = "matrix"
    override val label = "Matrix"
    override val ttl: Duration = Duration.ofSeconds(2)

    private val dir = stateDir.resolve("matrix")
    private val saved = dir.resolve("messages.json")
    private val rooms = config.rooms.toSet()
    private val items = LinkedHashMap<String, Item>()

    @Volatile
    private var error: String? = null

    @Volatile
    private var origin = "Matrix"
    private val started = lazy { start() }

    @Volatile
    private var lock: FileLock? = null

    /** Whether this process runs the client, rather than reading along. */
    internal val syncing: Boolean get() = lock != null

    override fun fetch(now: Instant, since: Instant): List<Item> {
        if (!loggedIn(dir)) throw IllegalStateException(NOT_LOGGED_IN)
        // a lock once taken is never given back to null, whichever thread asks
        synchronized(this) { if (lock == null) lock = tryLock(dir) }
        if (lock == null) return readSaved().filter { (it.time ?: Instant.EPOCH).isAfter(since) }
        started.value
        error?.let { throw IllegalStateException(it) }
        return synchronized(items) { items.values.filter { (it.time ?: Instant.EPOCH).isAfter(since) } }
    }

    override fun note(): String? = if (lock == null) "liest mit; ein anderer Prozess synchronisiert" else null

    private fun readSaved(): List<Item> =
        if (Files.exists(saved)) runCatching { mapper.readValue<List<Item>>(saved.toFile()) }.getOrDefault(emptyList())
        else emptyList()

    private fun start() {
        readSaved().forEach { items[it.url ?: return@forEach] = it }
        Thread.ofPlatform().name("matrix-sync").daemon().start {
            runCatching { runBlocking { sync() } }.onFailure {
                // a store without an account: the login broke off, or the files were copied
                error = if ("authProviderData must not be null" in it.message.orEmpty()) NOT_LOGGED_IN
                else "Matrix: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private suspend fun sync() {
        val client = openClient(dir, authProviderData = null).getOrThrow()
        origin = client.userId.domain
        coroutineScope {
            // listen first, so that the events of the first sync count, too
            launch {
                client.room.getTimelineEventsFromNowOn(decryptionTimeout = 10.seconds).collect { event ->
                    runCatching { message(client, event) }.getOrNull()?.let(::add)
                }
            }
            client.startSync()
        }
    }

    private suspend fun message(client: MatrixClient, timelineEvent: TimelineEvent): MatrixMessage? {
        val event = timelineEvent.event as? RoomEvent.MessageEvent<*> ?: return null
        if (event.sender == client.userId) return null
        val content = timelineEvent.content?.getOrNull()
        // a reaction, a redaction or an edit is no message of its own
        if (content != null && (content !is RoomMessageEventContent || content.relatesTo is RelatesTo.Replace)) return null
        val text = content?.body
        val room = client.room.getById(event.roomId).first()
        val sender = client.user.getById(event.roomId, event.sender).first()?.name ?: event.sender.full
        val me = client.user.getById(event.roomId, client.userId).first()?.name
        return MatrixMessage(
            eventId = event.id.full,
            roomId = event.roomId.full,
            roomName = room?.name?.explicitName ?: sender,
            direct = room?.isDirect == true,
            sender = sender,
            time = Instant.ofEpochMilli(event.originTimestamp),
            text = text,
            mentionsMe = text != null &&
                MatrixMessages.mentions(text, content.mentions?.users?.map { it.full }?.toSet(), client.userId.full, me),
        )
    }

    private fun add(message: MatrixMessage) {
        val reason = MatrixMessages.reason(message, rooms) ?: return
        val item = MatrixMessages.item(message, reason, origin, config.link)
        synchronized(items) {
            items.remove(item.url)
            items[item.url!!] = item
            while (items.size > config.maxMessages) items.remove(items.keys.first())
            save(items.values.toList())
        }
    }

    private fun save(list: List<Item>) {
        val temp = Files.createTempFile(dir, "messages", ".json")
        mapper.writeValue(temp.toFile(), list)
        Files.move(temp, saved, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val STORE = "client"
        private const val NOT_LOGGED_IN = "nicht angemeldet: werkregie-dashboard matrix login"

        fun loggedIn(dir: Path): Boolean = Files.exists(dir.resolve("$STORE.mv.db"))

        /**
         * Signs in as a new device of the user's account and keeps its session in [stateDir].
         * Returns user ID and device ID.
         */
        fun login(config: MatrixConfig, stateDir: Path, user: String, password: String, deviceName: String): Pair<String, String> =
            runBlocking {
                val dir = stateDir.resolve("matrix")
                check(!loggedIn(dir)) { "schon angemeldet; zum Neuanmelden $dir löschen" }
                val lock = checkNotNull(tryLock(dir)) { "ein Relay oder der lokale Modus liest Matrix; erst stoppen" }
                // closing the channel frees the lock, also when the login fails
                lock.channel().use {
                    val baseUrl = baseUrl(config.homeserver ?: error("matrix.homeserver fehlt"))
                    val auth = MatrixClientAuthProviderData.classicLoginWithPassword(
                        baseUrl = baseUrl,
                        identifier = IdentifierType.User(user),
                        password = password,
                        initialDeviceDisplayName = deviceName,
                    ).getOrThrow()
                    // a half-written store would count as a login
                    val client = openClient(dir, auth).onFailure { deleteStore(dir) }.getOrThrow()
                    try {
                        client.userId.full to client.deviceId
                    } finally {
                        client.closeSuspending()
                    }
                }
            }

        /** A server name is looked up in its .well-known; a URL is taken as it is. */
        private suspend fun baseUrl(homeserver: String): Url {
            if (homeserver.startsWith("https://") || homeserver.startsWith("http://")) return Url(homeserver)
            val discovery = MatrixClientServerApiClientImpl(Url("https://$homeserver")).discovery.getClient()
            return Url(discovery.getOrNull()?.homeserver?.baseUrl ?: "https://$homeserver")
        }

        /**
         * The lock of the process that runs the client, or null when another process holds it.
         * The operating system frees it when the process ends; the channel stays open until then.
         */
        internal fun tryLock(dir: Path): FileLock? {
            ensureDir(dir)
            val channel = FileChannel.open(dir.resolve("sync.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) channel.close()
            return lock
        }

        // session, keys and message texts: for the user only
        private fun ensureDir(dir: Path) {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            }
        }

        private fun deleteStore(dir: Path) {
            Files.list(dir).use { files -> files.filter { it.fileName.toString().startsWith(STORE) }.forEach(Files::delete) }
        }

        internal suspend fun openClient(dir: Path, authProviderData: MatrixClientAuthProviderData?): Result<MatrixClient> {
            ensureDir(dir)
            // r2dbc-h2 drops the first slash of the path, and H2 wants it absolute
            val database = R2dbcDatabase.connect(url = "r2dbc:h2:file:///${dir.resolve(STORE).toAbsolutePath()}")
            return MatrixClient.create(
                repositoriesModule = RepositoriesModule.exposed(database),
                mediaStoreModule = MediaStoreModule.inMemory(),
                cryptoDriverModule = CryptoDriverModule.vodozemac(),
                authProviderData = authProviderData,
            )
        }
    }
}
