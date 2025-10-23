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

/**
 * 패킷의 종류를 정의하는 상수 객체입니다.
 * 클라이언트와 서버가 패킷의 용도를 식별하는 데 사용됩니다.
 */
object PacketType {
    const val REGISTER_NAME = 1
    const val CHAT_MESSAGE = 2
    const val SERVER_INFO = 3
    const val DISCONNECT_INFO = 4
    const val DISCONNECT_REQUEST = 5
}

/**
 * 프로토콜에 정의된 기본 패킷 구조를 나타내는 데이터 클래스입니다.
 * @param length 헤더(8바이트)와 바디를 포함한 패킷의 전체 길이 (4바이트 정수)
 * @param type 패킷의 종류를 나타내는 코드 (PacketType 참고, 4바이트 정수)
 * @param body 실제 데이터가 담긴 바이트 배열
 */
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

/**
 * 패킷 종류와 문자열 데이터를 받아 네트워크 전송을 위한 바이트 배열(byte[])로 변환합니다.
 * @param type 패킷의 종류 (PacketType)
 * @param bodyData 패킷 바디에 포함할 문자열 데이터
 * @return 전송 준비가 완료된 패킷의 바이트 배열
 */
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
    } catch (e: Exception) {
        throw IOException("Connection closed by peer (EOF).")
    }

    val type = dis.readInt()

    val body = ByteArray(length - 8)
    dis.readFully(body)

    return Packet(length, type, body)
}