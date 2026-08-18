package blue.anika.wolle.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based envelope for backup payloads (SNA-15) - PBKDF2WithHmacSHA256 (210k iterations,
 * OWASP's current minimum) derives a 256-bit AES key from the backup password, then
 * AES/GCM/NoPadding encrypts the payload. Same scheme as the sibling apps' BackupCrypto
 * (syncwich/nyetbox: PBKDF2 210k -> AES-GCM), just with Stricknani's own magic bytes so a backup
 * file is unambiguously attributable to this app.
 *
 * Wire format: `"STB1"` (4 bytes) + flag (1 byte: 0=plain, 1=encrypted) +, only if encrypted,
 * salt (16 bytes) + iv (12 bytes), then the (cipher)text.
 */
object BackupCrypto {
    private val MAGIC = "STB1".toByteArray(Charsets.US_ASCII)
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val FLAG_PLAIN: Byte = 0
    private const val FLAG_ENCRYPTED: Byte = 1

    fun encode(payload: ByteArray, password: String?): ByteArray {
        if (password.isNullOrBlank()) {
            return MAGIC + byteArrayOf(FLAG_PLAIN) + payload
        }
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return MAGIC + byteArrayOf(FLAG_ENCRYPTED) + salt + iv + cipher.doFinal(payload)
    }

    /**
     * @throws BackupPasswordRequiredException the payload is encrypted and [password] is null/blank
     * @throws BackupDecryptionException bad magic header, wrong password, or corrupted data
     */
    fun decode(data: ByteArray, password: String?): ByteArray {
        if (data.size < MAGIC.size + 1 || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupDecryptionException("Not a Stricknani backup file")
        }
        var offset = MAGIC.size
        val flag = data[offset]
        offset += 1
        if (flag == FLAG_PLAIN) {
            return data.copyOfRange(offset, data.size)
        }
        if (password.isNullOrBlank()) throw BackupPasswordRequiredException()
        if (data.size < offset + SALT_LENGTH_BYTES + IV_LENGTH_BYTES) {
            throw BackupDecryptionException("Corrupted backup file")
        }
        val salt = data.copyOfRange(offset, offset + SALT_LENGTH_BYTES)
        offset += SALT_LENGTH_BYTES
        val iv = data.copyOfRange(offset, offset + IV_LENGTH_BYTES)
        offset += IV_LENGTH_BYTES
        val ciphertext = data.copyOfRange(offset, data.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw BackupDecryptionException("Wrong password or corrupted backup")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
