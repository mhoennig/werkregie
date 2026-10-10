package de.hoennig.werkregie.dashboard.sources

import de.hoennig.werkregie.dashboard.DashboardConfig
import de.hoennig.werkregie.dashboard.MatrixConfig
import de.hoennig.werkregie.dashboard.ServerConfig
import de.hoennig.werkregie.dashboard.localSources
import de.hoennig.werkregie.dashboard.mapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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
})
