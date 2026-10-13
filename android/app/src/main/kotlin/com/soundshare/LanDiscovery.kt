package com.soundshare

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Una ventana corta de respuestas; la PC más usada gana, no la primera en contestar. */
class LanDiscovery(private val context: Context, private val store: LanStore) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private fun wifiNetwork() = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun known(): List<LinkEndpoint> {
        val network = wifiNetwork() ?: return emptyList()
        return store.paired().mapNotNull { pc -> pc.address?.let { LinkEndpoint(it, pc.port, pc, store.phoneId, network) } }
    }

    internal fun discover(run: PlayerGeneration): List<LinkEndpoint> {
        val network = wifiNetwork() ?: return emptyList()
        val paired = store.paired()
        if (paired.isEmpty() || !run.active) return emptyList()
        val found = LanCandidates(paired)
        val destinations = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        connectivity.getLinkProperties(network)?.linkAddresses?.forEach { link ->
            LanProtocol.broadcast(link.address.address, link.prefixLength)?.let { destinations.add(InetAddress.getByAddress(it)) }
        }
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = manager.createMulticastLock("soundshare-discovery").apply { setReferenceCounted(false) }
        val socket = DatagramSocket(null)
        if (!run.attach(socket)) return emptyList()
        try {
            lock.acquire()
            socket.broadcast = true
            network.bindSocket(socket)
            socket.bind(null)
            val request = LanProtocol.discoveryRequest(store.phoneId)
            // Un byte extra permite rechazar un datagrama truncado que exceda el contrato.
            val bytes = ByteArray(LanProtocol.MAX_DATAGRAM + 1)
            val end = System.nanoTime() + 1_500_000_000L
            var nextSend = 0L
            while (run.active && System.nanoTime() < end) {
                val now = System.nanoTime()
                if (now >= nextSend) {
                    for (address in destinations) {
                        runCatching { socket.send(DatagramPacket(request, request.size, address, LanProtocol.DISCOVERY_PORT)) }
                    }
                    nextSend = now + 300_000_000L
                }
                socket.soTimeout = maxOf(1, (minOf(nextSend, end) - System.nanoTime()) / 1_000_000L).toInt()
                val packet = DatagramPacket(bytes, bytes.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val reply = LanProtocol.parseReply(bytes, packet.length) ?: continue
                val address = packet.address.hostAddress ?: continue
                run.ifActive { found.add(reply, address) }
            }
        } finally {
            socket.close()
            run.detach(socket)
            if (lock.isHeld) lock.release()
        }
        return found.values().map { pc -> LinkEndpoint(checkNotNull(pc.address), pc.port, pc, store.phoneId, network) }
    }
}
