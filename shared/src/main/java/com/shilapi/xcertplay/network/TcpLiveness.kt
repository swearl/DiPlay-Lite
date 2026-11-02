package com.shilapi.xcertplay.network

import java.net.Socket

/** Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure. */
internal object TcpLiveness {
    fun configure(socket: Socket, @Suppress("UNUSED_PARAMETER") diagnostic: (String) -> Unit) {
        socket.keepAlive = true
    }
}
