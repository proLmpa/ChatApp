package com.chat.share

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ConnectionService(
    private val socket: Socket
) {

    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    private val sendQueue = LinkedBlockingQueue<Pair<FrameType, ByteArray>>(256)

    private val writerThread = Thread {
        try {
            while (!socket.isClosed) {
                val (type, payload) = sendQueue.take()

                output.writeByte(type.code.toInt())
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
            }
        } catch (_: Exception) {
            close()
        }}.apply {
            isDaemon = true
            name = "connection-writer-${socket.port}"
            start()
        }

    fun readFrame(): Pair<FrameType, ByteArray> {
        val typeCode = input.readByte()
        val frameType = FrameType.fromCode(typeCode)
            ?: throw IOException("Unknown frameType: $typeCode")

        val length = input.readInt()
        if (length < 0) throw IOException("Negative frame length: $length")

        val payload = ByteArray(length)
        input.readFully(payload)

        return frameType to payload
    }

    fun readPacket(): Packet {
        val (frameType, payload) = readFrame()
        if (frameType != FrameType.JSON_PACKET) {
            throw IOException("Expected JSON_PACKET, but got $frameType")
        }

        return Protocol.decodePacket(payload)
    }

    fun readFileChunk(payload: ByteArray): FileChunk {
        val dis = DataInputStream(payload.inputStream())

        val transferId = dis.readUTF()
        val seq = dis.readInt()
        val length = dis.readInt()
        val buffer = ByteArray(length)
        dis.readFully(buffer)

        return FileChunk(transferId, seq, length, buffer)
    }

    private fun writeFrame(frameType: FrameType, payload: ByteArray) {
        if (!sendQueue.offer(frameType to payload, 3, TimeUnit.SECONDS)) {
            throw IOException("Send queue fully (backpressure)")
        }
    }

    fun writePacket(packet: Packet) {
        val payload = Protocol.encodePacket(packet)
        writeFrame(FrameType.JSON_PACKET, payload)
    }

    fun writeFileChunk(chunk: FileChunk) {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeUTF(chunk.transferId)
        dos.writeInt(chunk.seq)
        dos.writeInt(chunk.length)
        dos.write(chunk.data, 0, chunk.length)
        dos.flush()

        writeFrame(FrameType.FILE_CHUNK, baos.toByteArray())
    }

    fun writeRawFileChunk(bytes: ByteArray) {
        writeFrame(FrameType.FILE_CHUNK, bytes)
    }

    companion object {
        fun peekTransferId(payload: ByteArray): String {
            val dis = DataInputStream(ByteArrayInputStream(payload))
            return dis.readUTF()
        }
    }

    fun isConnected(): Boolean =
        socket.isConnected && !socket.isClosed

    fun inputShutDown(): Boolean = socket.isInputShutdown

    fun close() {
        try {
            writerThread.interrupt()
        } catch (_: Exception) {}

        socket.close()
    }
}