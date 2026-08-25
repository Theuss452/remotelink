package app.remotelink.network

import android.content.Context
import app.remotelink.BuildConfig
import app.remotelink.capture.ScreenCaptureService
import app.remotelink.control.RemoteAccessibilityService
import app.remotelink.security.PairingManager
import app.remotelink.webrtc.WebRtcHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalControlServer(
    private val context: Context,
    private val binding: LanPolicy.WifiBinding,
    private val pairingManager: PairingManager,
    private val approvalHandler: (PairRequest, (Boolean) -> Unit) -> Unit
) {
    data class PairRequest(
        val requestId: String,
        val remoteIp: String,
        val sas: String? = null,
        val strongPairing: Boolean = false
    )

    private data class Pending(
        var state: State,
        val remoteIp: String,
        val sas: String? = null,
        val strongPairing: Boolean = false,
        var token: String? = null,
        val created: Long = System.currentTimeMillis()
    )

    private data class Session(
        val hash: String,
        val remoteIp: String,
        val expiresAt: Long,
        val acceptedRemoteCandidates: AtomicInteger = AtomicInteger(0),
        val rejectedRemoteCandidates: AtomicInteger = AtomicInteger(0)
    )

    private enum class State { PENDING, APPROVED, DENIED }

    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(6)
    private val slots = Semaphore(12)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val port: Int get() = serverSocket?.localPort ?: 0
    fun hasActiveSession(): Boolean = sessions.isNotEmpty()
    fun activeRemoteIp(): String? = sessions.values.firstOrNull()?.remoteIp

    fun revokeSessionsFromDevice() {
        revokeAllSessions()
        pending.clear()
        pairingManager.invalidate()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = bindRandomPort(binding.address)
        serverSocket = socket
        acceptThread = Thread({ acceptLoop(socket) }, "RemoteLink-Accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        pending.clear()
        sessions.clear()
        pairingManager.invalidate()
        ScreenCaptureService.instance?.stopAll()
        workers.shutdownNow()
    }

    private fun bindRandomPort(address: Inet4Address): ServerSocket {
        val rng = SecureRandom()
        repeat(40) {
            val candidate = 42000 + rng.nextInt(10001)
            try {
                return ServerSocket(candidate, 16, address).apply { reuseAddress = false }
            } catch (_: Exception) {
            }
        }
        return ServerSocket(0, 16, address).apply { reuseAddress = false }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try { server.accept() } catch (_: Exception) { break }
            if (!LanPolicy.isSameSubnet(binding.address, client.inetAddress, binding.prefixLength)) {
                try { client.close() } catch (_: Exception) {}
                continue
            }
            if (!slots.tryAcquire()) {
                try { respondJson(client, 503, JSONObject().put("error", "busy")) }
                finally { try { client.close() } catch (_: Exception) {} }
                continue
            }
            workers.execute {
                try { handle(client) }
                catch (_: Exception) {
                    try { respondJson(client, 400, JSONObject().put("error", "bad_request")) }
                    catch (_: Exception) {}
                } finally {
                    slots.release()
                    try { client.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 8000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLineLimited(input, 4096) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respondJson(socket, 400, JSONObject().put("error", "bad_request"))
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = mutableMapOf<String, String>()
        var totalHeaders = 0

        while (true) {
            val line = readLineLimited(input, 8192) ?: break
            if (line.isEmpty()) break
            totalHeaders += line.length
            if (totalHeaders > 32_768) return respondJson(socket, 431, JSONObject().put("error", "headers_too_large"))
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val expectedHost = "${binding.address.hostAddress}:$port"
        if (!headers["host"].equals(expectedHost, ignoreCase = true)) return respondJson(socket, 403, JSONObject().put("error", "invalid_host"))
        if (method == "POST") {
            val expectedOrigin = "http://$expectedHost"
            if (!headers["origin"].equals(expectedOrigin, ignoreCase = true)) return respondJson(socket, 403, JSONObject().put("error", "invalid_origin"))
        }

        val declaredLength = headers["content-length"]?.toLongOrNull() ?: 0L
        if (declaredLength < 0L || declaredLength > MAX_BODY_BYTES) return respondJson(socket, 413, JSONObject().put("error", "body_too_large"))
        val body = if (declaredLength > 0) readFixed(input, declaredLength.toInt()).toString(StandardCharsets.UTF_8) else ""
        cleanup()

        when {
            method == "GET" && target == "/" -> asset(socket, "web/index.html", "text/html; charset=utf-8")
            method == "GET" && target == "/styles.css" -> asset(socket, "web/styles.css", "text/css; charset=utf-8")
            method == "GET" && target == "/app.js" -> asset(socket, "web/app.js", "application/javascript; charset=utf-8")
            method == "GET" && target == "/strong-pair.js" -> asset(socket, "web/strong-pair.js", "application/javascript; charset=utf-8")
            method == "GET" && target == "/reconnect.js" -> asset(socket, "web/reconnect.js", "application/javascript; charset=utf-8")
            method == "GET" && target == "/transfer.js" -> asset(socket, "web/transfer.js", "application/javascript; charset=utf-8")
            method == "GET" && target == "/api/status" -> status(socket)
            method == "POST" && target == "/api/pair" -> beginPair(socket, body)
            method == "GET" && target.startsWith("/api/pair/status?") -> pairStatus(socket, target)
            method == "POST" && target == "/api/webrtc/offer" -> withSession(socket, headers) { webRtcOffer(socket, it, body) }
            method == "POST" && target == "/api/webrtc/candidate" -> withSession(socket, headers) { addCandidate(socket, it, body) }
            method == "GET" && target == "/api/webrtc/candidates" -> withSession(socket, headers) { localCandidates(socket, it) }
            method == "GET" && target == "/api/webrtc/state" -> withSession(socket, headers) { webRtcState(socket, it) }
            method == "POST" && target == "/api/session/stop" -> withSession(socket, headers) { stopSession(socket, it) }
            else -> respondJson(socket, 404, JSONObject().put("error", "not_found"))
        }
    }

    private fun status(socket: Socket) {
        val capture = ScreenCaptureService.instance?.isReady() == true
        val accessibility = RemoteAccessibilityService.instance != null
        respondJson(socket, 200, JSONObject()
            .put("name", "RemoteLink").put("version", BuildConfig.VERSION_NAME)
            .put("mode", "lan-webrtc").put("secureMedia", true)
            .put("strongPairing", true)
            .put("captureReady", capture).put("accessibilityReady", accessibility)
            .put("singleViewer", true))
    }

    private fun beginPair(socket: Socket, body: String) {
        val ip = socket.inetAddress.hostAddress ?: "desconhecido"
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val obj = try { JSONObject(trimmed) } catch (_: Exception) {
                return respondJson(socket, 400, JSONObject().put("error", "invalid_pair_request"))
            }
            if (obj.optString("mode") == "strong") {
                val result = pairingManager.verifyStrong(
                    obj.optString("pairId"), obj.optString("clientNonce"), obj.optString("proof")
                )
                return handlePairVerification(socket, ip, result.result, result.sas, strong = true)
            }
        }
        val code = parseForm(body)["code"] ?: ""
        handlePairVerification(socket, ip, pairingManager.verify(code), sas = null, strong = false)
    }

    private fun handlePairVerification(socket: Socket, ip: String, result: PairingManager.VerifyResult, sas: String?, strong: Boolean) {
        when (result) {
            PairingManager.VerifyResult.OK -> {
                val id = UUID.randomUUID().toString()
                pending[id] = Pending(State.PENDING, ip, sas = sas, strongPairing = strong)
                approvalHandler(PairRequest(id, ip, sas = sas, strongPairing = strong)) { approved ->
                    val p = pending[id] ?: return@approvalHandler
                    if (approved) {
                        revokeAllSessions()
                        val token = pairingManager.issueSessionToken()
                        p.token = token
                        p.state = State.APPROVED
                        val hash = hashToken(token)
                        sessions[hash] = Session(hash, p.remoteIp, System.currentTimeMillis() + SESSION_TTL_MS)
                    } else p.state = State.DENIED
                }
                val response = JSONObject().put("requestId", id).put("status", "pending").put("strong", strong)
                if (sas != null) response.put("sas", sas)
                respondJson(socket, 202, response)
            }
            PairingManager.VerifyResult.INVALID -> respondJson(socket, 401, JSONObject().put("error", if (strong) "invalid_proof" else "invalid_code"))
            PairingManager.VerifyResult.EXPIRED -> respondJson(socket, 410, JSONObject().put("error", "expired"))
            PairingManager.VerifyResult.LOCKED -> respondJson(socket, 429, JSONObject().put("error", "pairing_locked"))
            PairingManager.VerifyResult.CLOSED -> respondJson(socket, 409, JSONObject().put("error", "pairing_closed"))
        }
    }

    private fun pairStatus(socket: Socket, target: String) {
        val id = parseForm(target.substringAfter('?', ""))["id"] ?: return respondJson(socket, 400, JSONObject().put("error", "missing_id"))
        val p = pending[id] ?: return respondJson(socket, 404, JSONObject().put("error", "unknown_request"))
        val ip = socket.inetAddress.hostAddress ?: ""
        if (ip != p.remoteIp) return respondJson(socket, 403, JSONObject().put("error", "ip_mismatch"))
        when (p.state) {
            State.PENDING -> {
                val response = JSONObject().put("status", "pending").put("strong", p.strongPairing)
                if (p.sas != null) response.put("sas", p.sas)
                respondJson(socket, 200, response)
            }
            State.DENIED -> { pending.remove(id); respondJson(socket, 403, JSONObject().put("status", "denied")) }
            State.APPROVED -> {
                val token = p.token ?: ""
                pending.remove(id)
                respondJson(socket, 200, JSONObject().put("status", "approved").put("token", token).put("strong", p.strongPairing))
            }
        }
    }

    private inline fun withSession(socket: Socket, headers: Map<String, String>, block: (Session) -> Unit) {
        val raw = headers["authorization"] ?: return respondJson(socket, 401, JSONObject().put("error", "missing_session"))
        if (!raw.startsWith("Bearer ", ignoreCase = true)) return respondJson(socket, 401, JSONObject().put("error", "bad_session"))
        val hash = hashToken(raw.substringAfter(' ').trim())
        val session = sessions[hash] ?: return respondJson(socket, 401, JSONObject().put("error", "invalid_session"))
        if (System.currentTimeMillis() > session.expiresAt) {
            sessions.remove(hash)
            ScreenCaptureService.instance?.endSession(hash)
            return respondJson(socket, 401, JSONObject().put("error", "session_expired"))
        }
        val ip = socket.inetAddress.hostAddress ?: ""
        if (ip != session.remoteIp) return respondJson(socket, 403, JSONObject().put("error", "ip_mismatch"))
        block(session)
    }

    private fun webRtcOffer(socket: Socket, session: Session, body: String) {
        val service = ScreenCaptureService.instance ?: return respondJson(socket, 409, JSONObject().put("error", "capture_not_ready"))
        if (!service.isReady()) return respondJson(socket, 409, JSONObject().put("error", "capture_not_ready"))
        val offer = try { JSONObject(body).optString("sdp") } catch (_: Exception) { "" }
        if (offer.length !in 20..MAX_SDP_CHARS) return respondJson(socket, 400, JSONObject().put("error", "invalid_offer"))
        try { respondJson(socket, 200, JSONObject().put("type", "answer").put("sdp", service.createAnswer(session.hash, offer))) }
        catch (e: Exception) { respondJson(socket, 500, JSONObject().put("error", "webrtc_failed").put("detail", (e.message ?: "unknown").take(240))) }
    }

    private fun addCandidate(socket: Socket, session: Session, body: String) {
        val service = ScreenCaptureService.instance ?: return respondJson(socket, 409, JSONObject().put("error", "capture_not_ready"))
        val obj = try { JSONObject(body) } catch (_: Exception) { return respondJson(socket, 400, JSONObject().put("error", "invalid_candidate")) }
        val rawCandidate = obj.optString("candidate")
        if (rawCandidate.isBlank() || rawCandidate.length > 8192) {
            session.rejectedRemoteCandidates.incrementAndGet()
            return respondJson(socket, 400, JSONObject().put("error", "invalid_candidate"))
        }
        val normalizedCandidate = LanPolicy.normalizeRemoteIceCandidate(rawCandidate, session.remoteIp, binding)
        if (normalizedCandidate == null) {
            session.rejectedRemoteCandidates.incrementAndGet()
            return respondJson(socket, 400, JSONObject().put("error", "invalid_candidate"))
        }
        val mid = if (obj.isNull("sdpMid")) null else obj.optString("sdpMid").takeIf { it.isNotEmpty() }
        val line = obj.optInt("sdpMLineIndex", -1)
        if (line < 0) {
            session.rejectedRemoteCandidates.incrementAndGet()
            return respondJson(socket, 400, JSONObject().put("error", "invalid_candidate"))
        }
        val ok = service.addRemoteCandidate(session.hash, WebRtcHost.SignalCandidate(mid, line, normalizedCandidate))
        if (ok) session.acceptedRemoteCandidates.incrementAndGet() else session.rejectedRemoteCandidates.incrementAndGet()
        respondJson(socket, if (ok) 200 else 409, JSONObject().put("ok", ok))
    }

    private fun localCandidates(socket: Socket, session: Session) {
        val service = ScreenCaptureService.instance ?: return respondJson(socket, 409, JSONObject().put("error", "capture_not_ready"))
        val array = JSONArray()
        service.drainLocalCandidates(session.hash)
            .filter { LanPolicy.isAllowedLocalIceCandidate(it.candidate, binding) }
            .forEach { c ->
                array.put(JSONObject().put("sdpMid", c.sdpMid ?: JSONObject.NULL).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.candidate))
            }
        respondJson(socket, 200, JSONObject().put("candidates", array))
    }

    private fun webRtcState(socket: Socket, session: Session) {
        val service = ScreenCaptureService.instance
        val state = service?.diagnosticState(session.hash) ?: JSONObject().put("peer", "closed").put("ice", "closed")
        state.put("state", service?.connectionState(session.hash) ?: "closed")
            .put("captureReady", service?.isReady() == true)
            .put("captureStarted", service?.isCaptureStarted() == true)
            .put("accessibilityReady", RemoteAccessibilityService.instance != null)
            .put("remoteCandidatesAccepted", session.acceptedRemoteCandidates.get())
            .put("remoteCandidatesRejected", session.rejectedRemoteCandidates.get())
        respondJson(socket, 200, state)
    }

    private fun stopSession(socket: Socket, session: Session) {
        ScreenCaptureService.instance?.endSession(session.hash)
        sessions.remove(session.hash)
        respondJson(socket, 200, JSONObject().put("ok", true))
    }

    private fun revokeAllSessions() {
        val hashes = sessions.keys.toList()
        sessions.clear()
        hashes.forEach { ScreenCaptureService.instance?.endSession(it) }
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.created > PENDING_TTL_MS }
        val expired = sessions.values.filter { now > it.expiresAt }
        expired.forEach {
            sessions.remove(it.hash)
            ScreenCaptureService.instance?.endSession(it.hash)
        }
    }

    private fun asset(socket: Socket, path: String, type: String) {
        val bytes = try { context.assets.open(path).use { it.readBytes() } }
        catch (_: Exception) { return respond(socket, 404, "text/plain", "Not found") }
        respondBytes(socket, 200, type, bytes)
    }

    private fun parseForm(raw: String): Map<String, String> = raw.split('&').mapNotNull { part ->
        if (part.isBlank()) return@mapNotNull null
        val i = part.indexOf('=')
        val k = if (i >= 0) part.substring(0, i) else part
        val v = if (i >= 0) part.substring(i + 1) else ""
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }.toMap()

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun readFixed(input: BufferedInputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(data, offset, length - offset)
            if (n < 0) throw IllegalArgumentException("unexpected end of body")
            offset += n
        }
        return data
    }

    private fun readLineLimited(input: BufferedInputStream, max: Int): String? {
        val out = StringBuilder()
        while (out.length < max) {
            val b = input.read()
            if (b == -1) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
        }
        if (out.length >= max) throw IllegalArgumentException("line too long")
        return out.toString()
    }

    private fun respondJson(socket: Socket, code: Int, obj: JSONObject) =
        respond(socket, code, "application/json; charset=utf-8", obj.toString())

    private fun respond(socket: Socket, code: Int, type: String, text: String) =
        respondBytes(socket, code, type, text.toByteArray(StandardCharsets.UTF_8))

    private fun respondBytes(socket: Socket, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 410 -> "Gone"
            413 -> "Payload Too Large"; 429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"
            503 -> "Service Unavailable"; else -> "Error"
        }
        val out = BufferedOutputStream(socket.getOutputStream())
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Cross-Origin-Resource-Policy: same-origin\r\n")
            append("Cross-Origin-Opener-Policy: same-origin\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), bluetooth=()\r\n")
            append("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; media-src 'self' blob:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        out.write(headers)
        out.write(body)
        out.flush()
    }

    companion object {
        private const val MAX_BODY_BYTES = 262_144L
        private const val MAX_SDP_CHARS = 180_000
        private const val SESSION_TTL_MS = 30 * 60_000L
        private const val PENDING_TTL_MS = 90_000L
    }
}
