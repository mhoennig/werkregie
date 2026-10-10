import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.clientserverapi.client.MatrixClientServerApiClientImpl
import de.connect2x.trixnity.vodozemac.megolm.GroupSession
import de.connect2x.trixnity.vodozemac.megolm.InboundGroupSession
import de.connect2x.trixnity.vodozemac.olm.Account
import de.connect2x.trixnity.vodozemac.olm.OlmMessage
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking

/**
 * Werkbaum #dash.matrix.sdk: Olm and Megolm round trips through vodozemac, the client's crypto
 * driver module, and, given a server name, discovery and versions of that homeserver.
 * Signs in nowhere and sends nothing.
 */
fun main(args: Array<String>) {
    println("JVM ${System.getProperty("java.version")} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    olm()
    megolm()
    println("crypto driver module: ${CryptoDriverModule.vodozemac()::class.qualifiedName ?: "anonymous"}")
    args.firstOrNull()?.let { runBlocking { homeserver(it) } }
}

/** Two devices: Alice opens a session with Bob's one-time key, Bob answers. */
private fun olm() {
    val alice = Account()
    val bob = Account()
    bob.generateOneTimeKeys(1)
    val oneTimeKey = bob.oneTimeKeys.values.first()
    bob.markKeysAsPublished()

    val outbound = alice.createOutboundSession(bob.curve25519Key, oneTimeKey)
    val first = outbound.encrypt("Hallo Bob") as OlmMessage.PreKey.Text
    val inbound = bob.createInboundSession(first, alice.curve25519Key)
    check(inbound.plaintext.value == "Hallo Bob")

    val answer = inbound.session.encrypt("Hallo Alice")
    check(outbound.decrypt(answer) == "Hallo Alice")
    println("olm: pre-key message and answer decrypted (session ${outbound.sessionId.take(8)}…)")
}

/** A room: the sender's group session, a member who got its session key. */
private fun megolm() {
    val outbound = GroupSession()
    val inbound = InboundGroupSession(outbound.sessionKey)
    val messages = listOf("erste", "zweite", "dritte").map { outbound.encrypt(it) }
    val decrypted = messages.map { inbound.decrypt(it).plaintext.value }
    check(decrypted == listOf("erste", "zweite", "dritte"))
    println("megolm: ${messages.size} room messages decrypted, first known index ${inbound.firstKnownIndex}")
}

/** .well-known/matrix/client of the server name, then the versions its homeserver speaks. */
private suspend fun homeserver(serverName: String) {
    val discovery = MatrixClientServerApiClientImpl(Url("https://$serverName")).discovery.getClient().getOrThrow()
    val baseUrl = discovery.homeserver.baseUrl
    val versions = MatrixClientServerApiClientImpl(Url(baseUrl)).server.getVersions().getOrThrow()
    println("homeserver: ${versions.versions.size} spec versions, newest ${versions.versions.last()}")
}
