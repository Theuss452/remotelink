package app.remotelink.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

object LanPolicy {
    data class WifiBinding(val address: Inet4Address, val prefixLength: Int)

    fun findWifiBinding(context: Context): WifiBinding? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val props = cm.getLinkProperties(network) ?: return null
        val link = props.linkAddresses.firstOrNull {
            it.address is Inet4Address &&
                !it.address.isLoopbackAddress &&
                !it.address.isLinkLocalAddress &&
                it.address.isSiteLocalAddress
        } ?: return null
        val prefix = link.prefixLength
        if (prefix !in 8..30) return null
        return WifiBinding(link.address as Inet4Address, prefix)
    }

    /**
     * Validates a browser ICE candidate and binds it to the exact IP that made
     * the authenticated HTTP request. Chromium commonly replaces host IPv4
     * addresses with ephemeral mDNS names. Native libwebrtc resolution of those
     * names is not reliable on every Android build, so a .local host candidate
     * is rewritten to the already authenticated browser IPv4.
     *
     * LAN default (allowRelay=false): only UDP host candidates are accepted.
     * srflx/relay/prflx and candidates for other LAN devices are rejected
     * deliberately in LAN-only mode. Callers LAN (LocalControlServer) usam o
     * default e não passam a flag.
     *
     * Internet (allowRelay=true): aceita host/srflx/relay sobre udp/tcp, pois o
     * caminho pode exigir relay TURN autenticado com credencial temporária. O
     * binding mesma-sub-rede é dispensado — a autenticação da sessão (Bearer +
     * SAS/HMAC) continua obrigatória antes de qualquer candidate chegar aqui.
     */
    fun normalizeRemoteIceCandidate(
        candidate: String,
        remoteIp: String,
        binding: WifiBinding,
        allowRelay: Boolean = false
    ): String? {
        val parts = candidate.trim().split(Regex("\\s+")).toMutableList()
        if (parts.size < 8) return null

        val typeIndex = parts.indexOf("typ")
        if (typeIndex < 0 || typeIndex + 1 >= parts.size) return null
        val candidateType = parts[typeIndex + 1].lowercase()
        if (allowRelay) {
            if (candidateType !in setOf("host", "srflx", "relay")) return null
        } else {
            if (candidateType != "host") return null
        }

        val protocol = parts.getOrNull(2) ?: return null
        if (allowRelay) {
            if (!protocol.equals("udp", ignoreCase = true) && !protocol.equals("tcp", ignoreCase = true)) return null
        } else {
            if (!protocol.equals("udp", ignoreCase = true)) return null
        }

        val approvedAddress = parsePrivateIpv4(remoteIp)
        if (!allowRelay) {
            val approved = approvedAddress ?: return null
            if (!isSameSubnet(binding.address, approved, binding.prefixLength)) return null
        }

        val candidateAddress = parts.getOrNull(4) ?: return null
        if (allowRelay && candidateType == "relay") {
            // Candidate de relay carrega o IP do TURN autenticado, não o do
            // browser — aceita sem binding de endereço (sessão já autenticada).
        } else when {
            candidateAddress.endsWith(".local", ignoreCase = true) -> {
                if (allowRelay) return candidate.trim().split(Regex("\\s+")).joinToString(" ")
                parts[4] = (approvedAddress?.hostAddress ?: return null)
            }
            else -> {
                if (allowRelay) {
                    // Internet: srflx/host podem carregar IP público — valida só
                    // a sintaxe IPv4; a confiança vem da sessão autenticada.
                    if (!isValidIpv4Literal(candidateAddress)) return null
                } else {
                    val literal = parsePrivateIpv4(candidateAddress) ?: return null
                    // A paired browser may advertise only the same interface/IP that
                    // originated its authenticated RemoteLink HTTP session.
                    if (literal.hostAddress != approvedAddress?.hostAddress) return null
                }
            }
        }

        val port = parts.getOrNull(5)?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return parts.joinToString(" ")
    }

    /** Only expose the Android Wi-Fi IPv4 host candidate to the paired browser. */
    fun isAllowedLocalIceCandidate(candidate: String, binding: WifiBinding): Boolean {
        val parts = candidate.trim().split(Regex("\\s+"))
        if (parts.size < 8) return false
        val typeIndex = parts.indexOf("typ")
        if (typeIndex < 0 || typeIndex + 1 >= parts.size) return false
        if (!parts[typeIndex + 1].equals("host", ignoreCase = true)) return false
        if (!parts.getOrNull(2).equals("udp", ignoreCase = true)) return false
        val address = parsePrivateIpv4(parts.getOrNull(4) ?: return false) ?: return false
        return address.hostAddress == binding.address.hostAddress
    }

    fun isSameSubnet(local: Inet4Address, remote: InetAddress, prefixLength: Int): Boolean {
        if (remote.isLoopbackAddress) return true
        if (remote !is Inet4Address || !remote.isSiteLocalAddress) return false
        val a = local.address
        val b = remote.address
        var bits = prefixLength
        for (i in 0 until 4) {
            if (bits <= 0) break
            val take = minOf(bits, 8)
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= take
        }
        return true
    }

    private fun parsePrivateIpv4(raw: String): Inet4Address? {
        val octets = raw.split('.')
        if (octets.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val n = octets[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            bytes[i] = n.toByte()
        }
        val parsed = InetAddress.getByAddress(bytes) as? Inet4Address ?: return null
        return parsed.takeIf { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }

    private fun isValidIpv4Literal(raw: String): Boolean {
        val octets = raw.split('.')
        if (octets.size != 4) return false
        return octets.all { it.toIntOrNull() in 0..255 }
    }
}
