package org.highbridge.app.pro

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected AES-256-GCM backup.
 * File format v2: "OMB2" | iterations(4) | salt(16) | iv(12) | ciphertext+tag.
 * Iterations raised from 100,000 to 600,000 (current OWASP guidance for PBKDF2-SHA256).
 * v1 files (salt|iv|ciphertext, 100k iterations) still open.
 */
object EncryptedBackupManager {
    private val MAGIC = "OMB2".toByteArray()
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 600_000
    private const val V1_ITERATIONS = 100_000
    private val rng = SecureRandom()

    fun encrypt(plainBytes: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 8) { "Use a backup password of at least 8 characters" }
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val key = deriveKey(password, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(MAGIC)
        }
        return MAGIC + ByteBuffer.allocate(4).putInt(ITERATIONS).array() + salt + iv + cipher.doFinal(plainBytes)
    }

    /** Throws AEADBadTagException if the password is wrong or the file was changed. */
    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        if (blob.size > 4 && blob.copyOfRange(0, 4).contentEquals(MAGIC)) {
            val iters = ByteBuffer.wrap(blob, 4, 4).int
            require(iters in 10_000..10_000_000) { "Not a valid backup file" }
            var o = 8
            val salt = blob.copyOfRange(o, o + SALT_LEN); o += SALT_LEN
            val iv = blob.copyOfRange(o, o + IV_LEN); o += IV_LEN
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iters), GCMParameterSpec(128, iv)); updateAAD(MAGIC)
            }
            return cipher.doFinal(blob, o, blob.size - o)
        }
        require(blob.size > SALT_LEN + IV_LEN + 16) { "Not a valid backup file" }
        val salt = blob.copyOfRange(0, SALT_LEN)
        val iv = blob.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(password, salt, V1_ITERATIONS), GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(blob, SALT_LEN + IV_LEN, blob.size - SALT_LEN - IV_LEN)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }
}

enum class PinResult { UNLOCKED, DURESS_WIPE, INVALID, LOCKED_OUT }

/**
 * PIN lock with an optional duress PIN that wipes the app.
 * The old version stored plain SHA-256 of the PIN, which a phone can reverse for every 4–8 digit
 * PIN in under a second. Now: random salt + PBKDF2 (slow on purpose), constant-time comparison,
 * and a growing lockout after wrong guesses. Store the record in EncryptedSharedPreferences.
 */
class AppSecurityManager(
    private val unlock: PinRecord,
    private val duress: PinRecord? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class PinRecord(val saltB64: String, val hashB64: String, val iterations: Int = 120_000)

    private var failures = 0
    private var lockedUntilMs = 0L

    @Synchronized
    fun verify(pin: String): PinResult {
        if (clock() < lockedUntilMs) return PinResult.LOCKED_OUT
        // Check both records every time so timing doesn't reveal which PIN matched.
        val isUnlock = matches(pin, unlock)
        val isDuress = duress?.let { matches(pin, it) } ?: false
        return when {
            isDuress -> PinResult.DURESS_WIPE   // duress wins if someone set both PINs the same
            isUnlock -> { failures = 0; PinResult.UNLOCKED }
            else -> {
                failures++
                if (failures >= 5) lockedUntilMs = clock() + 30_000L * (1L shl minOf(failures - 5, 6))
                PinResult.INVALID
            }
        }
    }

    companion object {
        private val rng = SecureRandom()
        private val b64e = Base64.getEncoder(); private val b64d = Base64.getDecoder()

        fun create(pin: String, iterations: Int = 120_000): PinRecord {
            require(pin.length in 4..12 && pin.all { it.isDigit() }) { "PIN must be 4–12 digits" }
            val salt = ByteArray(16).also { rng.nextBytes(it) }
            return PinRecord(b64e.encodeToString(salt), b64e.encodeToString(hash(pin, salt, iterations)), iterations)
        }

        private fun matches(pin: String, rec: PinRecord): Boolean =
            MessageDigest.isEqual(hash(pin, b64d.decode(rec.saltB64), rec.iterations), b64d.decode(rec.hashB64))

        private fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        }
    }
}

object MorseCodec {
    private val charToMorse = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---",
        '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.", '.' to ".-.-.-", ',' to "--..--", '?' to "..--..",
        '/' to "-..-.", '-' to "-....-", '@' to ".--.-.", '\'' to ".----.", '!' to "-.-.--",
        '(' to "-.--.", ')' to "-.--.-", '&' to ".-...", ':' to "---...", ';' to "-.-.-.",
        '=' to "-...-", '+' to ".-.-.", '"' to ".-..-.", '_' to "..--.-", '$' to "...-..-",
    )
    private val morseToChar = charToMorse.entries.associate { (k, v) -> v to k }

    /** Unknown characters become "?" instead of silently disappearing. */
    fun encode(text: String): String = text.trim().uppercase().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" / ") { w -> w.map { charToMorse[it] ?: charToMorse.getValue('?') }.joinToString(" ") }

    fun decode(morse: String): String = morse.trim().split(Regex("\\s*/\\s*"))
        .joinToString(" ") { w -> w.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { morseToChar[it] ?: '?' }.joinToString("") }
}
