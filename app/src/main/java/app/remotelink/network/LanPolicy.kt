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

    /** Only host ICE candidates on the current LAN (or browser mDNS host candidates) are accepted. */
    fun isAllowedIceCandidate(candidate: String, binding: WifiBinding): Boolean {
        val parts = candidate.trim().split(Regex("\\s+"))
        if (parts.size < 8) return false
        val typeIndex = parts.indexOf("typ")
        if (typeIndex < 0 || typeIndex + 1 >= parts.size || parts[typeIndex + 1] != "host") return false
        val address = parts.getOrNull(4) ?: return false
        if (address.endsWith(".local", ignoreCase = true)) return true
        val octets = address.split('.')
        if (octets.size != 4) return false
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val n = octets[i].toIntOrNull() ?: return false
            if (n !in 0..255) return false
            bytes[i] = n.toByte()
        }
        val parsed = InetAddress.getByAddress(bytes)
        return isSameSubnet(binding.address, parsed, binding.prefixLength)
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
}
