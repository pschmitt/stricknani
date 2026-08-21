package blue.anika.wolle.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {

    private val payload = "hello stricknani".toByteArray(Charsets.UTF_8)

    @Test
    fun `unencrypted round-trip`() {
        val encoded = BackupCrypto.encode(payload, password = null)
        val decoded = BackupCrypto.decode(encoded, password = null)
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun `encrypted round-trip with correct password`() {
        val encoded = BackupCrypto.encode(payload, password = "correct horse battery staple")
        val decoded = BackupCrypto.decode(encoded, password = "correct horse battery staple")
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun `decoding encrypted payload without a password throws password-required`() {
        val encoded = BackupCrypto.encode(payload, password = "secret")
        assertThrows(BackupPasswordRequiredException::class.java) {
            BackupCrypto.decode(encoded, password = null)
        }
    }

    @Test
    fun `wrong password throws decryption exception`() {
        val encoded = BackupCrypto.encode(payload, password = "secret")
        assertThrows(BackupDecryptionException::class.java) {
            BackupCrypto.decode(encoded, password = "wrong")
        }
    }

    @Test
    fun `garbage input throws decryption exception`() {
        assertThrows(BackupDecryptionException::class.java) {
            BackupCrypto.decode("not a backup".toByteArray(), password = null)
        }
    }

    @Test
    fun `tampered ciphertext throws decryption exception`() {
        val encoded = BackupCrypto.encode(payload, password = "secret")
        encoded[encoded.size - 1] = (encoded[encoded.size - 1] + 1).toByte()
        assertThrows(BackupDecryptionException::class.java) {
            BackupCrypto.decode(encoded, password = "secret")
        }
    }
}
