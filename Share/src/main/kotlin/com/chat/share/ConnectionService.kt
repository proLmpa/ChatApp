package com.chat.share

import java.net.Socket

class ConnectionService(
    private val socket: Socket
) : Connection {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun readPacket(): Packet =
        Protocol.readPacket(input)

    override fun writePacket(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun isConnected(): Boolean =
        socket.isConnected && !socket.isClosed

    override fun close() {
        socket.close()
    }

    fun inputShutDown(): Boolean = socket.isInputShutdown
}