package app.remotelink.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PairingManager {
    private val random = SecureRandom()
    private var codeHash: ByteArray? = null
    private var strongPairId: String? = null
    private var strongSecret: ByteArray? = null
    private var expiresAtMs: Long = 0L
    private val failures = AtomicInteger(0)

    @Synchronized
    fun newCode(validForMs: Long = 5 * 60_000L): PairingWindow {
        val code = (100000 + random.nextInt(900000)).toString()
        val pairIdBytes = ByteArray(12).also(random::nextBytes)
        val secret = ByteArray(32).also(random::nextBytes)
        val pairId = b64Url(pairIdBytes)

        codeHash = sha256(code.toByteArray(Charsets.UTF_8))
        strongPairId = pairId
        strongSecret = secret
        expiresAtMs = System.currentTimeMillis() + validForMs
        failures.set(0)
        return PairingWindow(code, expiresAtMs, pairId, b64Url(secret))
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
        consumePairingSecrets()
        return VerifyResult.OK
    }

    @Synchronized
    fun verifyStrong(pairId: String, clientNonceB64: String, proofB64: String): StrongVerifyResult {
        val expectedPairId = strongPairId ?: return StrongVerifyResult(VerifyResult.CLOSED)
        val secret = strongSecret ?: return StrongVerifyResult(VerifyResult.CLOSED)
        if (System.currentTimeMillis() > expiresAtMs) { invalidate(); return StrongVerifyResult(VerifyResult.EXPIRED) }
        if (failures.get() >= MAX_FAILURES) { invalidate(); return StrongVerifyResult(VerifyResult.LOCKED) }
        if (!constantTimeStringEquals(expectedPairId, pairId)) return failStrong()

        val nonce = decodeB64Url(clientNonceB64) ?: return failStrong()
        val suppliedProof = decodeB64Url(proofB64) ?: return failStrong()
        if (nonce.size !in 16..32 || suppliedProof.size != 32) return failStrong()

        val challenge = "remotelink-pair-v1|$pairId|$clientNonceB64".toByteArray(Charsets.UTF_8)
        val expectedProof = hmacSha256(secret, challenge)
        if (!MessageDigest.isEqual(expectedProof, suppliedProof)) return failStrong()

        val sasBytes = hmacSha256(secret, "remotelink-sas-v1|$pairId|$clientNonceB64".toByteArray(Charsets.UTF_8))
        val sasNumber = (((sasBytes[0].toLong() and 0xff) shl 24) or
            ((sasBytes[1].toLong() and 0xff) shl 16) or
            ((sasBytes[2].toLong() and 0xff) shl 8) or
            (sasBytes[3].toLong() and 0xff)) % 1_000_000L
        val sas = sasNumber.toString().padStart(6, '0')
        consumePairingSecrets()
        return StrongVerifyResult(VerifyResult.OK, sas)
    }

    @Synchronized
    fun issueSessionToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        invalidate()
        return b64Url(bytes)
    }

    @Synchronized
    fun invalidate() {
        codeHash = null
        strongPairId = null
        strongSecret?.fill(0)
        strongSecret = null
        expiresAtMs = 0L
        failures.set(0)
    }

    private fun consumePairingSecrets() {
        codeHash = null
        strongPairId = null
        strongSecret?.fill(0)
        strongSecret = null
        expiresAtMs = 0L
        failures.set(0)
    }

    private fun failStrong(): StrongVerifyResult {
        val count = failures.incrementAndGet()
        if (count >= MAX_FAILURES) {
            invalidate()
            return StrongVerifyResult(VerifyResult.LOCKED)
        }
        return StrongVerifyResult(VerifyResult.INVALID)
    }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    private fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value)
    }

    private fun b64Url(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeB64Url(value: String): ByteArray? = try {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    } catch (_: Exception) { null }

    private fun constantTimeStringEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    data class PairingWindow(
        val code: String,
        val expiresAtMs: Long,
        val strongPairId: String,
        val strongSecretB64: String
    )

    data class StrongVerifyResult(val result: VerifyResult, val sas: String? = null)
    enum class VerifyResult { OK, INVALID, EXPIRED, LOCKED, CLOSED }
    companion object { const val MAX_FAILURES = 5 }
}
