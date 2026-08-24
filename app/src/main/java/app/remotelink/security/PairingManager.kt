package app.remotelink.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

class PairingManager {
    private val random = SecureRandom()
    private var codeHash: ByteArray? = null
    private var expiresAtMs: Long = 0L
    private val failures = AtomicInteger(0)

    @Synchronized
    fun newCode(validForMs: Long = 5 * 60_000L): PairingWindow {
        val code = (100000 + random.nextInt(900000)).toString()
        codeHash = sha256(code.toByteArray(Charsets.UTF_8))
        expiresAtMs = System.currentTimeMillis() + validForMs
        failures.set(0)
        return PairingWindow(code, expiresAtMs)
    }

    @Synchronized
    fun verify(candidate: String): VerifyResult {
        val expected = codeHash ?: return VerifyResult.CLOSED
        if (System.currentTimeMillis() > expiresAtMs) { invalidate(); return VerifyResult.EXPIRED }
        if (failures.get() >= MAX_FAILURES) { invalidate(); return VerifyResult.LOCKED }
        val ok = MessageDigest.isEqual(expected, sha256(candidate.trim().toByteArray(Charsets.UTF_8)))
        if (!ok) {
            val count = failures.incrementAndGet()
            if (count >= MAX_FAILURES) { invalidate(); return VerifyResult.LOCKED }
            return VerifyResult.INVALID
        }
        codeHash = null
        expiresAtMs = 0L
        failures.set(0)
        return VerifyResult.OK
    }

    @Synchronized
    fun issueSessionToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        invalidate()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    @Synchronized
    fun invalidate() {
        codeHash = null
        expiresAtMs = 0L
        failures.set(0)
    }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    data class PairingWindow(val code: String, val expiresAtMs: Long)
    enum class VerifyResult { OK, INVALID, EXPIRED, LOCKED, CLOSED }
    companion object { const val MAX_FAILURES = 5 }
}
