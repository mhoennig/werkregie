package de.hoennig.werkregie.dashboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class AccessTokenTest : FunSpec({
    test("is created once, readable only by its owner, and kept across restarts") {
        val file = Files.createTempDirectory("state").resolve("werkregie/access-token")

        val token = AccessToken(file).token

        token shouldMatch Regex("^[0-9a-f]{48}$")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
        AccessToken(file).token shouldBe token
    }

    test("matches only itself") {
        val accessToken = AccessToken(Files.createTempDirectory("state").resolve("access-token"))

        accessToken.matches(accessToken.token) shouldBe true
        accessToken.matches(accessToken.token + "x") shouldBe false
        accessToken.matches(null) shouldBe false
    }
})
