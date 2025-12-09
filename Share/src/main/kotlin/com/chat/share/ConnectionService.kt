package com.chat.share

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket

class ConnectionService(
    private val socket: Socket
) {

    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    fun readPacket(): Packet =
        Protocol.readPacket(input)

    fun writePacket(bytes: ByteArray) =
        Protocol.writePacket(output, bytes)

    fun readChunk(): FileChunk {
        val length: Int
        val buffer: ByteArray

        synchronized(input) {
            length = try {
                input.readInt()
            } catch (e: EOFException) {
                throw e
            }

            if (length < 0) {
                throw IllegalStateException("Negative chunk length: $length")
            }

            buffer = ByteArray(length)
            input.readFully(buffer)
        }

        return FileChunk(length, buffer)
    }

    fun writeChunk(chunk: ByteArray, len: Int = chunk.size) {
        require(len >= 0 && len <= chunk.size) {
            "Invalid length $len for chunk size ${chunk.size}"
        }

        synchronized(output) {
            output.writeInt(len)
            output.write(chunk, 0, len)
            output.flush()
        }
    }

    fun isConnected(): Boolean =
        socket.isConnected && !socket.isClosed

    fun close() {
        socket.close()
    }

    fun inputShutDown(): Boolean = socket.isInputShutdown
}