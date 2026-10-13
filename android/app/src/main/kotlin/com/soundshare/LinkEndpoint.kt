package com.soundshare

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket

/** PLAYER y CAPTURE heredan exactamente el destino y la identidad de su CONTROL. */
data class LinkEndpoint(val address: String = "127.0.0.1", val port: Int = Protocol.PORT,
                        val pc: LanStore.Pc? = null, val phoneId: String? = null, val network: Network? = null) {
    val wifi get() = pc != null

    fun connect(socket: Socket, timeout: Int = 2000) {
        socket.tcpNoDelay = true
        if (wifi) {
            network?.bindSocket(socket)
            socket.keepAlive = true
        }
        socket.connect(InetSocketAddress(address, port), timeout)
        if (wifi) {
            socket.soTimeout = 3000
            LanAuth.authenticate(socket.getInputStream(), socket.getOutputStream(),
                LanProtocol.bytes(checkNotNull(phoneId)), LanProtocol.bytes(checkNotNull(pc).secret))
            socket.soTimeout = 0
        }
    }
}
