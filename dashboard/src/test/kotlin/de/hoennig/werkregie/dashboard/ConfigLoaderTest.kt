package de.hoennig.werkregie.dashboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ConfigLoaderTest : FunSpec({
    val tea = Files.createTempFile("tea", ".yml").also {
        Files.writeString(
            it,
            """
            logins:
              - name: javagil
                url: https://git.example
                token: token-of-mi
                user: mi
              - name: other
                url: https://other.example/
                token: token-of-mi-elsewhere
                user: mi
            """.trimIndent(),
        )
    }

    test("finds a tea login by its name") {
        ConfigLoader.teaToken("javagil", "https://anything.example", tea) shouldBe "token-of-mi"
    }

    test("finds a tea login by its user on the same host") {
        ConfigLoader.teaToken("mi", "https://other.example", tea) shouldBe "token-of-mi-elsewhere"
    }

    test("a user without a login on that host has no token") {
        ConfigLoader.teaToken("mi", "https://third.example", tea) shouldBe null
    }
})
