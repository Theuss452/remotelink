package app.remotelink.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Signaling via Internet (MVP): o celular mantém apenas conexões HTTPS de SAÍDA
 * para o servidor — nenhuma porta pública é aberta no aparelho.
 *
 * Contrato espelhado da LAN (LocalControlServer): Bearer auth, corpo ≤ 256KB,
 * SDP ≤ 180K chars, sessão única. Tudo via HTTPS (TLS do sistema); URLs http://
 * são recusadas. Nenhum segredo/token é escrito em log.
 */
class InternetSignalingClient(
    private val baseUrl: String,
    private val bearerToken: String,
    client: OkHttpClient? = null
) {
    data class TurnCredentials(
        val uris: List<String>,
        val username: String,
        val password: String,
        val ttlSeconds: Long
    )

    data class SignalCandidate(
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String
    )

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    private val normalizedBase: String = baseUrl.trim().trimEnd('/')

    init {
        require(normalizedBase.startsWith("https://")) { "Internet signaling exige HTTPS" }
        require(bearerToken.length in 32..512) { "Bearer inválido" }
    }

    /** Envia o offer do browser e retorna o answer SDP. Mesmos limites da LAN. */
    fun sendOffer(offerSdp: String): String {
        require(offerSdp.length in 20..MAX_SDP_CHARS) { "offer SDP fora do limite" }
        val body = JSONObject().put("sdp", offerSdp).toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val res = post("/api/webrtc/offer", body)
        val answer = res.optString("sdp", "")
        require(answer.length in 20..MAX_SDP_CHARS) { "answer SDP fora do limite" }
        return answer
    }

    fun sendCandidate(candidate: SignalCandidate) {
        require(candidate.candidate.length <= MAX_CANDIDATE_CHARS) { "candidate fora do limite" }
        val body = JSONObject()
            .put("candidate", candidate.candidate)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        post("/api/webrtc/candidate", body)
    }

    fun pollCandidates(): List<SignalCandidate> {
        val res = get("/api/webrtc/candidates")
        val arr: JSONArray = res.optJSONArray("candidates") ?: JSONArray()
        val out = ArrayList<SignalCandidate>(arr.length().coerceAtMost(MAX_CANDIDATES_PER_POLL))
        for (i in 0 until arr.length().coerceAtMost(MAX_CANDIDATES_PER_POLL)) {
            val o = arr.optJSONObject(i) ?: continue
            val candidate = o.optString("candidate", "")
            if (candidate.isBlank() || candidate.length > MAX_CANDIDATE_CHARS) continue
            out += SignalCandidate(
                sdpMid = o.optString("sdpMid", null),
                sdpMLineIndex = o.optInt("sdpMLineIndex", 0),
                candidate = candidate
            )
        }
        return out
    }

    /**
     * Credencial TURN temporária (TURN REST): username `expiry:deviceId`,
     * password HMAC do segredo compartilhado — gerada no SERVIDOR para sessão
     * autenticada, TTL ~1h. O cliente nunca vê o segredo compartilhado.
     */
    fun getTurnCredentials(deviceId: String): TurnCredentials {
        require(deviceId.isNotBlank() && deviceId.length <= 64) { "deviceId inválido" }
        val res = get("/api/turn?deviceId=" + urlEncode(deviceId))
        val uris = ArrayList<String>()
        val arr = res.optJSONArray("uris") ?: JSONArray()
        for (i in 0 until arr.length().coerceAtMost(MAX_TURN_URIS)) {
            val uri = arr.optString(i, "")
            if (uri.startsWith("turn:") || uri.startsWith("turns:")) uris += uri
        }
        require(uris.isNotEmpty()) { "sem URIs TURN" }
        val username = res.optString("username", "")
        val password = res.optString("password", "")
        require(username.isNotBlank() && password.isNotBlank()) { "credencial TURN incompleta" }
        return TurnCredentials(
            uris = uris,
            username = username,
            password = password,
            ttlSeconds = res.optLong("ttl", DEFAULT_TURN_TTL_S).coerceIn(60L, 86400L)
        )
    }

    private fun post(path: String, body: okhttp3.RequestBody): JSONObject {
        val request = Request.Builder()
            .url(normalizedBase + path)
            .header("Authorization", "Bearer $bearerToken")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        return execute(request)
    }

    private fun get(path: String): JSONObject {
        val request = Request.Builder()
            .url(normalizedBase + path)
            .header("Authorization", "Bearer $bearerToken")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: ""
            if (raw.length > MAX_BODY_BYTES) error("resposta excede o limite")
            if (!response.isSuccessful) {
                // Mensagem genérica: nunca vazar corpo/token em exceção/log.
                error("signaling HTTP ${response.code}")
            }
            return try {
                JSONObject(raw)
            } catch (_: Exception) {
                error("resposta de signaling inválida")
            }
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "RemoteLink-Internet/0.9.13-alpha"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 15_000L
        // Espelhos dos limites LAN (LocalControlServer).
        private const val MAX_BODY_BYTES = 262_144
        private const val MAX_SDP_CHARS = 180_000
        private const val MAX_CANDIDATE_CHARS = 8_192
        private const val MAX_CANDIDATES_PER_POLL = 64
        private const val MAX_TURN_URIS = 8
        private const val DEFAULT_TURN_TTL_S = 3_600L
    }
}
