package com.chat.share

import java.io.*
import java.nio.charset.StandardCharsets

/**
 * 패킷의 종류를 정의하는 상수 객체입니다.
 * 클라이언트와 서버가 패킷의 용도를 식별하는 데 사용됩니다.
 */
enum class PacketType(val code: Int) {
    REGISTER_NAME(1),
    CHAT_MESSAGE(2),
    SERVER_INFO(3),
    DISCONNECT_INFO(4),
    DISCONNECT_REQUEST(5);

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

/**
 * 패킷 종류와 문자열 데이터를 받아 네트워크 전송을 위한 바이트 배열(byte[])로 변환합니다.
 * @param type 패킷의 종류 (PacketType)
 * @param bodyData 패킷 바디에 포함할 문자열 데이터
 * @return 전송 준비가 완료된 패킷의 바이트 배열
 */
fun createPacket(type: PacketType, bodyData: String): ByteArray {
    val bodyBytes = bodyData.toByteArray()
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
 * 네트워크 입력 스트림(InputStream)에서 패킷을 읽어와 Packet 객체로 역직렬화합니다.
 * @param inputStream 네트워크 소켓의 입력 스트림
 * @return 읽어온 Packet 객체
 * @throws IOException 연결이 끊겼거나 I/O 오류 발생 시 예외 발생
 */
fun readPacket(inputStream: InputStream): Packet {
    val dis = DataInputStream(inputStream)

    val length = try {
        dis.readInt()
    } catch (_: Exception) {
        throw IOException("Connection closed by peer (EOF).")
    }

    val type = PacketType.fromCode(dis.readInt())

    val body = ByteArray(length - 8)
    dis.readFully(body)

    return Packet(length, type, body)
}