package de.hoennig.werkregie.dashboard

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/**
 * The one secret of a server, as Werkator's control token: random, created on the first start in a
 * file only its user can read; deleting the file rotates it. Browsers send it as a cookie, relays
 * as a bearer header.
 */
class AccessToken(private val file: Path) {
    val token: String by lazy { read() ?: create() }

    fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    private fun read(): String? = file.takeIf { Files.exists(it) }?.let { Files.readString(it).trim() }?.takeIf { it.isNotEmpty() }

    private fun create(): String {
        Files.createDirectories(file.parent)
        val token = HexFormat.of().formatHex(ByteArray(24).also { SecureRandom().nextBytes(it) })
        val owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val temporary = Files.createTempFile(file.parent, ".access-token", ".tmp", owner)
        Files.writeString(temporary, token + "\n")
        Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return token
    }

    companion object {
        const val COOKIE = "werkregie_token"
        const val FILE_NAME = "access-token"
    }
}
