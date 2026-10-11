package de.hoennig.werkregie.dashboard.sources

import de.hoennig.werkregie.dashboard.DashboardConfig
import de.hoennig.werkregie.dashboard.Item
import de.hoennig.werkregie.dashboard.Section
import de.hoennig.werkregie.dashboard.MatrixConfig
import de.hoennig.werkregie.dashboard.ServerConfig
import de.hoennig.werkregie.dashboard.localSources
import de.hoennig.werkregie.dashboard.mapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant

class MatrixSourceTest : FunSpec({
    val state = Files.createTempDirectory("werkregie-state")
    fun config(matrix: MatrixConfig?) = DashboardConfig(matrix = matrix, server = ServerConfig(stateDir = state.toString()))

    // Scenario#8.01
    test("without a homeserver there is no Matrix source") {
        localSources(config(null), mapper()).map { it.id } shouldNotContain "matrix"
        localSources(config(MatrixConfig()), mapper()).map { it.id } shouldNotContain "matrix"
        localSources(config(MatrixConfig(homeserver = "example.org")), mapper()).map { it.id } shouldContain "matrix"
    }

    test("the client's H2 store opens and gets its tables, and an empty one asks for a login") {
        val dir = Files.createTempDirectory("matrix-store")
        val error = runBlocking { MatrixSource.openClient(dir, authProviderData = null) }.exceptionOrNull()
        error?.message shouldContain "authProviderData must not be null"
        Files.exists(dir.resolve("client.mv.db")) shouldBe true
    }

    // Scenario#8.03
    test("without a login the source names the command that signs in") {
        val source = MatrixSource(MatrixConfig(homeserver = "example.org"), state, mapper())
        val error = shouldThrow<IllegalStateException> { source.fetch(Instant.now(), Instant.EPOCH) }
        error.message shouldContain "werkregie-dashboard matrix login"
    }

    // Scenario#11.01
    test("the local mode shows the relay's messages, without a session of its own") {
        val other = Files.createTempDirectory("werkregie-state")
        val dir = other.resolve("matrix")
        Files.createDirectories(dir)
        Files.createFile(dir.resolve("client.mv.db"))
        val message = Item("matrix", "example.org", "message", Section.OPEN, "Hallo", "message", Instant.now(),
            url = "https://matrix.to/#/!r:example.org/\$e")
        mapper().writeValue(dir.resolve("messages.json").toFile(), listOf(message))
        val held = MatrixSource.tryLock(dir)!!
        try {
            val source = MatrixSource(MatrixConfig(homeserver = "example.org"), other, mapper())
            source.fetch(Instant.now(), Instant.EPOCH).map { it.title } shouldBe listOf("Hallo")
            source.syncing shouldBe false
            source.note() shouldContain "liest mit"
        } finally {
            held.channel().close()
        }
    }

    // Scenario#11.02
    test("the local mode takes over from a stopped relay") {
        val other = Files.createTempDirectory("werkregie-state")
        val dir = other.resolve("matrix")
        Files.createDirectories(dir)
        Files.createFile(dir.resolve("client.mv.db"))
        val held = MatrixSource.tryLock(dir)!!
        val source = MatrixSource(MatrixConfig(homeserver = "example.org"), other, mapper())
        source.fetch(Instant.now(), Instant.EPOCH)
        source.syncing shouldBe false
        held.channel().close()
        source.fetch(Instant.now(), Instant.EPOCH)
        source.syncing shouldBe true
    }

    // Scenario#11.03
    test("no login while another process syncs") {
        val other = Files.createTempDirectory("werkregie-state")
        val held = MatrixSource.tryLock(other.resolve("matrix"))!!
        try {
            val error = shouldThrow<IllegalStateException> {
                MatrixSource.login(MatrixConfig(homeserver = "example.org"), other, "@me:example.org", "secret", "Werkregie test")
            }
            error.message shouldContain "erst stoppen"
        } finally {
            held.channel().close()
        }
    }

    test("a failed login frees the lock") {
        val other = Files.createTempDirectory("werkregie-state")
        // nothing listens on port 1: the login fails after the lock was taken
        shouldThrow<Exception> {
            MatrixSource.login(MatrixConfig(homeserver = "http://127.0.0.1:1"), other, "@me:example.org", "secret", "Werkregie test")
        }
        MatrixSource.tryLock(other.resolve("matrix")).shouldNotBeNull().channel().close()
    }

    // Scenario#12.05
    test("no verification while another process syncs") {
        val other = Files.createTempDirectory("werkregie-state")
        val dir = other.resolve("matrix")
        Files.createDirectories(dir)
        Files.createFile(dir.resolve("client.mv.db"))
        val held = MatrixSource.tryLock(dir)!!
        try {
            shouldThrow<IllegalStateException> { MatrixSource.verify(other) { "secret" } }.message shouldContain "erst stoppen"
        } finally {
            held.channel().close()
        }
    }

    // Scenario#12.06
    test("no verification before the login") {
        val other = Files.createTempDirectory("werkregie-state")
        shouldThrow<IllegalStateException> { MatrixSource.verify(other) { "secret" } }.message shouldContain "matrix login"
    }
})
