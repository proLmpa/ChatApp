package com.chat.share

import java.io.*
import java.nio.ByteBuffer

/**
 * 패킷의 종류를 정의하는 상수 객체입니다.
 * 클라이언트와 서버가 패킷의 용도를 식별하는 데 사용됩니다.
 */
enum class PacketType(val code: Int) {
    CONNECT_SUCCESS(1),
    REGISTER_NAME(10),
    REGISTER_NAME_SUCCESS(11),
    NAME_CANNOT_BE_BLANK(12),
    NAME_CANNOT_BE_DUPLICATED(13),
    USER_ENTERED(19),

    CHAT_MESSAGE(20),

    SERVER_INFO(30),
    UPDATE_NAME(33),
    UPDATE_NAME_SUCCESS(34),

    DISCONNECT_INFO(40),
    DISCONNECT_REQUEST(41),

    WHISPER(50),
    USER_NOT_EXISTS(51),
    WHISPER_TO_SENDER(52),
    WHISPER_TO_TARGET(53),

    FILE_UPLOAD_REQUEST(60),
    FILE_UPLOAD_ACCEPT(61),
    FILE_UPLOAD_COMPLETE(62),
    FILE_DOWNLOAD_REQUEST(63),
    FILE_DOWNLOAD_ACCEPT(64),
    FILE_DOWNLOAD_CHUNK(65),
    FILE_DOWNLOAD_COMPLETE(66),
    FILE_ERROR(67);

    companion object {
        private val map: Map<Int, PacketType> =
            entries.associateBy { it.code }

        fun fromCode(code: Int): PacketType? = map[code]
    }
}

data class ServerInfoDTO(val message: String)
data class RegisterNameDTO(val name: String)
data class NameRegisteredDTO(val id: String, val name: String)
data class UpdateNameDTO(val newName: String)
data class NameUpdatedDTO (val oldName: String?, val newName: String)
data class ChatMessageDTO(val sender: String?, val message: String)
data class WhisperDTO(val sender: String, val target: String, val message: String)
data class DisconnectDTO(val target: String, val sent: Int, val received: Int)
data class FileUploadRequestDTO(val filename: String, val size: Long)

data class FileChunkDTO(val index: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileChunkDTO

        if (index != other.index) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + data.contentHashCode()
        return result
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
    val type : PacketType,
    val body : ByteArray
) {
    inline fun <reified T> toDTO(): T {
        return JsonUtil.deserializeFromJsonBytes<T>(body)
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
        val bodyBytes = JsonUtil.serializeToJsonBytes(data)
        val length = 8 + bodyBytes.size

        val buffer = ByteBuffer.allocate(length)
        buffer.putInt(length)
        buffer.putInt(type.code)
        buffer.put(bodyBytes)

        return buffer.array()
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