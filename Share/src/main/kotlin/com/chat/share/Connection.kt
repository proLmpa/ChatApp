package com.chat.share

interface Connection {
    fun readPacket(): Packet
    fun writePacket(bytes: ByteArray)
    fun isConnected(): Boolean
    fun close()
}