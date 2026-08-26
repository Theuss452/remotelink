package app.remotelink.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

object LanPolicy {
    data class WifiBinding(val address: Inet4Address, val prefixLength: Int)

    /**
     * Finds a private IPv4 that belongs to a real Wi-Fi transport.
     *
     * Do not rely only on ConnectivityManager.activeNetwork: when Android VpnService apps
     * (SocksLite, Rev Hunter, WireGuard, etc.) become the default route, activeNetwork points
     * at TRANSPORT_VPN even though the local Wi-Fi interface is still connected and is exactly
     * where RemoteLink must remain bound. We prefer the active network when it is Wi-Fi, then
     * inspect the remaining networks for a Wi-Fi transport. VPN/cellular interfaces are never
     * returned, so enabling a VPN does not expose the RemoteLink listener inside the tunnel.
     */
    fun findWifiBinding(context: Context): WifiBinding? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        if (active != null) {
            wifiBindingFor(cm, active)?.let { return it }
        }
        for (network in cm.allNetworks) {
            if (network == active) continue
            wifiBindingFor(cm, network)?.let { return it }
        }
        return null
    }

    private fun wifiBindingFor(cm: ConnectivityManager, network: Network): WifiBinding? {
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        // Be explicit: a vendor/VPN implementation must never make a tunnel eligible simply
        // because it also reports an underlying Wi-Fi transport.
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
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
     * Only UDP host candidates are accepted. srflx/relay/prflx and candidates
     * for other LAN devices are rejected deliberately in LAN-only mode.
     */
    fun normalizeRemoteIceCandidate(
        candidate: String,
        remoteIp: String,
        binding: WifiBinding
    ): String? {
        val parts = candidate.trim().split(Regex("\\s+")).toMutableList()
        if (parts.size < 8) return null

        val typeIndex = parts.indexOf("typ")
        if (typeIndex < 0 || typeIndex + 1 >= parts.size) return null
        if (!parts[typeIndex + 1].equals("host", ignoreCase = true)) return null

        val protocol = parts.getOrNull(2) ?: return null
        if (!protocol.equals("udp", ignoreCase = true)) return null

        val approvedAddress = parsePrivateIpv4(remoteIp) ?: return null
        if (!isSameSubnet(binding.address, approvedAddress, binding.prefixLength)) return null

        val candidateAddress = parts.getOrNull(4) ?: return null
        when {
            candidateAddress.endsWith(".local", ignoreCase = true) -> {
                parts[4] = approvedAddress.hostAddress ?: return null
            }
            else -> {
                val literal = parsePrivateIpv4(candidateAddress) ?: return null
                // A paired browser may advertise only the same interface/IP that
                // originated its authenticated RemoteLink HTTP session.
                if (literal.hostAddress != approvedAddress.hostAddress) return null
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
}
