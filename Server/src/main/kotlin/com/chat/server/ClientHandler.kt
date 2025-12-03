package com.chat.server

import com.chat.share.ChatMessageDTO
import com.chat.share.ConnectionService
import com.chat.share.DisconnectDTO
import com.chat.share.NameRegisteredDTO
import com.chat.share.NameUpdatedDTO
import com.chat.share.PacketType
import com.chat.share.Protocol.createPacket
import com.chat.share.RegisterNameDTO
import com.chat.share.ServerInfoDTO
import com.chat.share.UpdateNameDTO
import com.chat.share.WhisperDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 클라이언트의 세션 정보를 저장하는 데이터 클래스.
 * sentCount: 클라이언트가 보낸 채팅 메시지 수 (CHAT_MESSAGE 기준)
 * receivedCount: 클라이언트가 받은 채팅 메시지 수 (CHAT_MESSAGE 기준)
 */
data class ClientData (
    val id: String,
    var name: String? = null,
    val sentCount: AtomicInteger = AtomicInteger(0),
    val receivedCount: AtomicInteger = AtomicInteger(0)
)

val clients = mutableMapOf<String, ClientHandler>()
val clientMapLock = ReentrantLock()

// 개별 클라이언트의 통신 및 세션 관리를 담당하는 스레드 핸들러
class ClientHandler(
    private val conn: ConnectionService,
    private val clientId: String
): Thread() {
    private val logger = KotlinLogging.logger {}

    val clientData = ClientData(clientId)
    private val clientOutputLock = ReentrantLock()

    /**
     * 클라이언트에게 패킷 바이트를 전송합니다.
     * 이 함수는 순수하게 I/O만 담당하며, 카운팅 로직은 호출부(broadcast)에서 처리합니다.
     * @param packetBytes 전송할 패킷의 바이트 배열
     */
    fun sendPacket(packetBytes: ByteArray) {
        clientOutputLock.withLock {
            try {
                conn.writePacket(packetBytes)
            } catch (_: IOException) {
                logger.error { "Failed to send packet to ${clientData.name ?: clientId}."}
                conn.close()
            }
        }
    }

    /**
     * 자신을 제외한 모든 클라이언트에게 패킷을 전파(Broadcast)합니다.
     * 카운팅 로직이 이 함수 내부에서 처리됩니다.
     * @param packetBytes 전송할 패킷의 바이트 배열
     * @param senderId 메시지 발신자의 ID (브로드캐스트에서 제외)
     * @param packetType 카운팅 여부를 결정하기 위한 패킷 타입
     */
    internal fun broadcast(packetBytes: ByteArray?, senderId: String? = null, packetType: PacketType) {
        clientMapLock.withLock {
            val targets = clients.values.filter { it.clientData.id != senderId }

            if (!targets.any()) return

            targets.forEach { handler ->
                handler.sendPacket(packetBytes!!)

                if (packetType == PacketType.CHAT_MESSAGE) {
                    handler.clientData.receivedCount.incrementAndGet()
                }
            }
        }
    }

    // 메인 로직
    override fun run() = try {

        clientMapLock.withLock { clients[clientId] = this }
        logger.info { "Client [$clientId] connected (awaiting name)" }

        sendPacket(createPacket(PacketType.CONNECT_SUCCESS, ServerInfoDTO("Please register your name.")))

        listenForMessages()
    } catch (_: Exception) {
        val clientNameOrId = clientData.name ?: clientId
        logger.error { "Client $clientNameOrId disconnected." }
    }

    // 클라이언트 패킷 유형에 따른 처리 리스너
    internal fun listenForMessages() {
        while (conn.isConnected() && !conn.inputShutDown()) {
            val packet = conn.readPacket()
            logger.debug { "Received packet: type=${packet.type}, length=${packet.length}" }

            when (packet.type) {
                PacketType.REGISTER_NAME ->
                    handleNameRegistration(packet.toDTO<RegisterNameDTO>())
                PacketType.CHAT_MESSAGE -> {
                    handleChatMessage(packet.toDTO<ChatMessageDTO>())
                }
                PacketType.UPDATE_NAME -> {
                    handleUpdateName(packet.toDTO<UpdateNameDTO>())
                }
                PacketType.WHISPER -> {
                    handleWhisper(packet.toDTO<WhisperDTO>())
                }
                PacketType.DISCONNECT_REQUEST -> {
                    logger.info { "Client ${clientData.name ?: clientId} sent DISCONNECT_REQUEST." }
                    handleClientDisconnect()
                    return
                }

                else -> {}
            }
        }
    }

    // 클라이언트 추가 및 핸들러 등록
    internal fun handleNameRegistration(dto: RegisterNameDTO) {
        val clientName = dto.name

        if (handleNameIsBlank(clientName)) return
        if (handleNameDuplication(clientName)) return

        clientData.name = clientName
        logger.info { "Client $clientId registered name: $clientName" }

        val dto = NameRegisteredDTO(clientData.id, clientName)
        sendPacket(createPacket(PacketType.REGISTER_NAME_SUCCESS, dto))
        broadcast(createPacket(PacketType.USER_ENTERED, dto), clientData.id, PacketType.SERVER_INFO)
        logger.info { "User $clientName entered." }
    }

    internal fun handleChatMessage(dto: ChatMessageDTO) {
        val sender = clientData.name ?: clientId

        broadcast(createPacket(PacketType.CHAT_MESSAGE, ChatMessageDTO(sender, dto.message)), clientData.id, PacketType.CHAT_MESSAGE)
        logger.info { "[$sender] ${dto.message}" }

        clientData.sentCount.incrementAndGet()
    }

    internal fun handleUpdateName(dto: UpdateNameDTO) {
        val newName = dto.newName

        if (handleNameIsBlank(newName)) return
        if (handleNameDuplication(newName)) return

        val oldName = clientData.name
        clientData.name = newName

        val dto = NameUpdatedDTO(oldName!!, newName)
        sendPacket(createPacket(PacketType.UPDATE_NAME_SUCCESS, dto))
        broadcast(createPacket(PacketType.UPDATE_NAME_SUCCESS, dto), clientData.id, PacketType.SERVER_INFO)

        logger.info { "User '$oldName' is changed to '$newName'." }
    }

    // 클라이언트 이름 공백 확인
    internal fun handleNameIsBlank(clientName: String): Boolean {
        return when {
            clientName.isEmpty() -> {
                sendPacket(createPacket(PacketType.NAME_CANNOT_BE_BLANK, ServerInfoDTO("Name cannot be empty.")))
                true
            }
            else -> false
        }
    }

    // 클라이언트 이름 중복 확인
    internal fun handleNameDuplication(clientName: String): Boolean {
        val senderId = clientData.id

        val isDuplicate = clientMapLock.withLock {
            clients.values.any { handler ->
                handler.clientData.id != senderId &&
                handler.clientData.name == clientName
            }
        }

        if (isDuplicate) {
            sendPacket(createPacket(PacketType.NAME_CANNOT_BE_DUPLICATED, ServerInfoDTO("Name is duplicated.")))
        }

        return isDuplicate
    }

    internal fun handleWhisper(dto: WhisperDTO) {
        val targetHandler = clientMapLock.withLock {
            clients.values.firstOrNull { it.clientData.name == dto.target }
        }

        if (targetHandler == null) {
            sendPacket(createPacket(PacketType.USER_NOT_EXISTS, ServerInfoDTO("The user does not exist.")))
            return
        }

        val whisperDTO = WhisperDTO(clientData.name!!, dto.target, dto.message)

        targetHandler.sendPacket(createPacket(PacketType.WHISPER_TO_TARGET, whisperDTO))
        targetHandler.clientData.receivedCount.incrementAndGet()
        sendPacket(createPacket(PacketType.WHISPER_TO_SENDER, whisperDTO))
        clientData.sentCount.incrementAndGet()

        logger.info { "[${clientData.name} -> ${targetHandler.clientData.name}] ${dto.message}" }
    }

    // 클라이언트 제거 및 disconnect 통지
    internal fun handleClientDisconnect() {
        val name = clientData.name ?: clientId
        val sent = clientData.sentCount.get()
        val received = clientData.receivedCount.get()

        clientMapLock.withLock {
            clients.remove(clientId)
        }

        val dto = DisconnectDTO(name, sent, received)
        val packet = createPacket(PacketType.DISCONNECT_INFO, dto)

        sendPacket(packet)
        broadcast(packet, clientData.id, PacketType.DISCONNECT_INFO)
        logger.info { "$name disconnected. (Send: $sent, Received: $received)" }

        conn.close()
    }
}