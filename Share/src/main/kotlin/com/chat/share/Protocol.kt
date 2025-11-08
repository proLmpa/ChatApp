package com.chat.share

import java.io.*
import java.nio.charset.StandardCharsets

/**
 * 패킷의 종류를 정의하는 상수 객체입니다.
 * 클라이언트와 서버가 패킷의 용도를 식별하는 데 사용됩니다.
 */
enum class PacketType(val code: Int) {
    REGISTER_NAME(10),
    CHAT_MESSAGE(20),
    SERVER_INFO(30),
    SERVER_SUCCESS(31),
    INITIAL_NAME_CHANGE_FAILED(32),
    UPDATE_NAME(33),
    UPDATE_NAME_FAILED(34),
    DISCONNECT_INFO(40),
    DISCONNECT_REQUEST(41),
    WHISPER(50),
    USER_NOT_EXISTS(51),
    FILE_TRANSFER(60);

    companion object {
        fun fromCode(code: Int): PacketType? =
            entries.find { it.code == code }
    }
}

/**
 * 프로토콜에 정의된 기본 패킷 구조를 나타내는 데이터 클래스입니다.
 * @param length 헤더(8바이트)와 바디를 포함한 패킷의 전체 길이 (4바이트 정수)
 * @param type 패킷의 종류 (PacketType)
 * @param body 실제 데이터가 담긴 바이트 배열
 */
data class Packet (
    val length : Int,
    val type : PacketType?,
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
        result = 31 * result + type.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

object Protocol {
    /**
     * 객체 데이터를 포함한 패킷을 생성합니다.
     * @param type 패킷 종류
     * @param data 패킷 바디로 직렬화할 객체 (ex: data class)
     */
    inline fun <reified T> createPacket(type: PacketType, data: T): ByteArray {
        val bodyBytes = when(data) {
            is String -> data.toByteArray(StandardCharsets.UTF_8)
            else -> JsonUtil.toJsonBytes(data)
        }
        val length = 8 + bodyBytes.size

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeInt(length)
        dos.writeInt(type.code)
        dos.write(bodyBytes)
        dos.flush()

        return baos.toByteArray()
    }

    /**
     * 입력 스트림에서 패킷을 읽어 Packet 객체로 변환합니다.
     * @throws IOException EOF 또는 연결 종료 시
     */
    fun readPacket(inputStream: InputStream): Packet {
        val dis = DataInputStream(inputStream)

        val length = try {
            dis.readInt()
        } catch (_: Exception) {
            throw IOException("Connection closed by peer (EOF).")
        }

        val type = PacketType.fromCode(dis.readInt())
            ?: throw IOException("Unknown PacketType code")

        val body = ByteArray(length - 8)
        dis.readFully(body)

        return Packet(length, type, body)
    }
}