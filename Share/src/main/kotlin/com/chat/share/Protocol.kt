package com.chat.share

import java.io.*
import java.nio.charset.StandardCharsets

/*
 * 프로토콜은 Header + Body 형태로, 개발하기 편한 형태로 구현
 * 다음 구조를 추천 {Header : [길이(4바이트)][패킷종류(4바이트)]} [Body]
 * 모든 패킷은 byte[] 로 변환 후 전송할것
 * ByteArrayOutputStream, ByteArrayInputStream 을 사용하면 편하다
 * 패킷이 byte[] 로 변환 되었을 때, 어떤 구조를 가지는지 그림으로 그릴 것
 */

object PacketType {
    const val REGISTER_NAME = 1
    const val CHAT_MESSAGE = 2
    const val SERVER_INFO = 3
    const val DISCONNECT_INFO = 4
    const val DISCONNECT_REQUEST = 5
}

data class Packet (
    val length : Int,
    val type : Int,
    val body : ByteArray
) {
    fun getBodyAsString(): String {
        return String(body, StandardCharsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Packet

        if (length != other.length) return false
        if (type != other.type) return false
        if (!body.contentEquals(other.body)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + type
        result = 31 * result + body.contentHashCode()
        return result
    }
}

fun createPacket(type: Int, bodyData: String): ByteArray {
    val bodyBytes = bodyData.toByteArray()
    val length = 8 + bodyBytes.size

    val baos = ByteArrayOutputStream()
    val dos = DataOutputStream(baos)

    dos.writeInt(length)
    dos.writeInt(type)
    dos.write(bodyBytes)
    dos.flush()

    return baos.toByteArray()
}

fun readPacket(inputStream: InputStream): Packet {
    val dis = DataInputStream(inputStream)

    val length = try {
        dis.readInt()
    } catch (e: Exception) {
        throw IOException("Connection closed by peer (EOF).")
    }

    val type = dis.readInt()

    val body = ByteArray(length - 8)
    dis.readFully(body)

    return Packet(length, type, body)
}