package app.remotelink.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Persiste a identidade de pareamento forte (pairId + segredo) para o modo internet.
 *
 * MVP: usa SharedPreferences privado do app (sandbox do Android). NÃO é um bypass
 * de pareamento — apenas reexpõe a mesma janela HMAC/SAS (`remotelink-pair-v1` /
 * `remotelink-sas-v1`) após morte do processo, com o TTL original preservado.
 * Caminho de evolução: migrar para androidx.security:security-crypto
 * (EncryptedSharedPreferences) sem mudar esta interface.
 */
class DeviceIdentityStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Identity(
        val pairId: String,
        val secretB64: String,
        val expiresAtMs: Long
    )

    @Synchronized
    fun saveIdentity(pairId: String, secretB64: String, expiresAtMs: Long): Boolean {
        if (pairId.isBlank() || secretB64.isBlank() || expiresAtMs <= 0L) return false
        // Limites coerentes com PairingManager: pairId 12 bytes b64url, segredo 32 bytes b64url.
        if (pairId.length !in 12..80 || secretB64.length !in 40..60) return false
        prefs.edit()
            .putString(KEY_PAIR_ID, pairId)
            .putString(KEY_SECRET, secretB64)
            .putLong(KEY_EXPIRES_AT, expiresAtMs)
            .apply()
        return true
    }

    @Synchronized
    fun loadIdentity(): Identity? {
        val pairId = prefs.getString(KEY_PAIR_ID, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (pairId.isBlank() || secret.isBlank() || expiresAt <= 0L) return null
        if (System.currentTimeMillis() > expiresAt) {
            clear()
            return null
        }
        return Identity(pairId, secret, expiresAt)
    }

    /**
     * Apaga a identidade. O valor do segredo é sobrescrito (best-effort) antes
     * da remoção; callers também devem zerar cópias em memória.
     */
    @Synchronized
    fun clear() {
        val secretLen = prefs.getString(KEY_SECRET, null)?.length ?: 0
        if (secretLen > 0) {
            prefs.edit().putString(KEY_SECRET, "0".repeat(secretLen)).apply()
        }
        prefs.edit().remove(KEY_PAIR_ID).remove(KEY_SECRET).remove(KEY_EXPIRES_AT).apply()
    }

    companion object {
        private const val PREFS_NAME = "remotelink_device_identity"
        private const val KEY_PAIR_ID = "pair_id"
        private const val KEY_SECRET = "secret_b64"
        private const val KEY_EXPIRES_AT = "expires_at_ms"
    }
}
