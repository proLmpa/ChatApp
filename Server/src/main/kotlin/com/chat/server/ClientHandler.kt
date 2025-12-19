package com.chat.server

import com.chat.share.ChatMessageDTO
import com.chat.share.ConnectionService
import com.chat.share.ConnectionService.Companion.peekTransferId
import com.chat.share.DisconnectDTO
import com.chat.share.FileSendCompleteDTO
import com.chat.share.FileSendRequestDTO
import com.chat.share.FrameType
import com.chat.share.NameRegisteredDTO
import com.chat.share.NameUpdatedDTO
import com.chat.share.Packet
import com.chat.share.PacketType
import com.chat.share.Protocol.createPacket
import com.chat.share.Protocol.decodePacket
import com.chat.share.RegisterNameDTO
import com.chat.share.ServerInfoDTO
import com.chat.share.UpdateNameDTO
import com.chat.share.WhisperDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    private val transferTargets = ConcurrentHashMap<String, String>()

    fun sendPacket(packet: Packet) {
        clientOutputLock.withLock {
            try {
                conn.writePacket(packet)
            } catch (_: IOException) {
                logger.error { "Failed to send packet to ${clientData.name ?: clientId}."}
                conn.close()
            }
        }
    }

    internal fun broadcast(packet: Packet, senderId: String? = null) {
        clientMapLock.withLock {
            clients.values
                .filter { it.clientData.id != senderId }
                .forEach { handler ->
                    handler.sendPacket(packet)
                    if (packet.type == PacketType.CHAT_MESSAGE) {
                        handler.clientData.receivedCount.incrementAndGet()
                    }
                }
        }
    }

    // 메인 로직
    override fun run() = try {
        clientMapLock.withLock { clients[clientId] = this }
        logger.info { "Client [$clientId] connected (awaiting name)" }

        sendPacket(createPacket(
            PacketType.CONNECT_SUCCESS,
            ServerInfoDTO("Please register your name."))
        )

        listenForFrames()
    } catch (_: IOException) {
        logger.info { "Client ${clientData.name ?: clientId} disconnected normally." }
    } catch (e: Exception) {
        logger.error(e) { "Unexpected error for client ${clientData.name ?: clientId}" }
    } finally {
        clientMapLock.withLock {
            clients.remove(clientId)
        }
        try {
            conn.close()
        } catch (_: Exception) {}
    }

    // 클라이언트 패킷 유형에 따른 처리 리스너
    internal fun listenForFrames() {
        while (conn.isConnected() && !conn.inputShutDown()) {
            val (frameType, payload) = conn.readFrame()

            when (frameType) {
                FrameType.JSON_PACKET -> {
                    val packet = decodePacket(payload)
                    handleJsonPacket(packet)
                }

                FrameType.FILE_CHUNK -> {
                    handleFileChunk(payload)
                }

                else -> logger.warn { "Unknown frame type: $frameType" }
            }
        }
    }

    private fun handleJsonPacket(packet: Packet) {
        when (packet.type) {
            PacketType.REGISTER_NAME ->
                handleNameRegistration(packet.toDTO<RegisterNameDTO>())
            PacketType.CHAT_MESSAGE ->
                handleChatMessage(packet.toDTO<ChatMessageDTO>())
            PacketType.UPDATE_NAME ->
                handleUpdateName(packet.toDTO<UpdateNameDTO>())
            PacketType.WHISPER ->
                handleWhisper(packet.toDTO<WhisperDTO>())
            PacketType.FILE_SEND_REQUEST ->
                handleFileSendRequest(packet.toDTO<FileSendRequestDTO>())
            PacketType.FILE_SEND_COMPLETE ->
                handleFileSendComplete(packet.toDTO<FileSendCompleteDTO>())
            PacketType.DISCONNECT_REQUEST -> {
                logger.info { "Client ${clientData.name ?: clientId} sent DISCONNECT_REQUEST." }
                handleClientDisconnect()
                return
            }

            else -> {}
        }
    }

    private fun handleFileChunk(payload: ByteArray) {
        val transferId = peekTransferId(payload)

        val targetId = transferTargets[transferId] ?: run {
            logger.warn {
                "FILE_CHUNK received but no target stored for sender=${clientData.name}"
            }
        }

        val targetHandler = clientMapLock.withLock { clients[targetId] } ?: return

        targetHandler.conn.writeRawFileChunk(payload)
        logger.debug { "Forwarded FILE_CHUNK from ${clientData.name} to $targetId" }
    }

    // 클라이언트 추가 및 핸들러 등록
    private fun handleNameRegistration(dto: RegisterNameDTO) {
        val clientName = dto.name

        if (handleNameIsBlank(clientName)) return
        if (handleNameDuplication(clientName)) return

        clientData.name = clientName
        logger.info { "Client $clientId registered name: $clientName" }

        val dto = NameRegisteredDTO(clientData.id, clientName)
        sendPacket(createPacket(PacketType.REGISTER_NAME_SUCCESS, dto))
        broadcast(createPacket(PacketType.USER_ENTERED, dto), clientData.id)
        logger.info { "User $clientName entered." }
    }

    private fun handleChatMessage(dto: ChatMessageDTO) {
        val sender = clientData.name ?: clientId

        broadcast(createPacket(PacketType.CHAT_MESSAGE, ChatMessageDTO(sender, dto.message)), clientData.id)
        logger.info { "[$sender] ${dto.message}" }

        clientData.sentCount.incrementAndGet()
    }

    private fun handleUpdateName(dto: UpdateNameDTO) {
        val newName = dto.newName

        if (handleNameIsBlank(newName)) return
        if (handleNameDuplication(newName)) return

        val oldName = clientData.name
        clientData.name = newName

        val dto = NameUpdatedDTO(oldName!!, newName)
        sendPacket(createPacket(PacketType.UPDATE_NAME_SUCCESS, dto))
        broadcast(createPacket(PacketType.UPDATE_NAME_SUCCESS, dto), clientData.id)

        logger.info { "User '$oldName' is changed to '$newName'." }
    }

    // 클라이언트 이름 공백 확인
    private fun handleNameIsBlank(clientName: String): Boolean {
        return when {
            clientName.isEmpty() -> {
                sendPacket(createPacket(PacketType.NAME_CANNOT_BE_BLANK, ServerInfoDTO("Name cannot be empty.")))
                true
            }
            else -> false
        }
    }

    // 클라이언트 이름 중복 확인
    private fun handleNameDuplication(clientName: String): Boolean {
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

    private fun handleWhisper(dto: WhisperDTO) {
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
    private fun handleClientDisconnect() {
        val name = clientData.name ?: clientId
        val sent = clientData.sentCount.get()
        val received = clientData.receivedCount.get()

        clientMapLock.withLock {
            clients.remove(clientId)
        }

        val dto = DisconnectDTO(name, sent, received)
        val packet = createPacket(PacketType.DISCONNECT_INFO, dto)

        sendPacket(packet)
        broadcast(packet, clientData.id)
        logger.info { "$name disconnected. (Send: $sent, Received: $received)" }

        conn.close()
    }

    private fun handleFileSendRequest(dto: FileSendRequestDTO) {
        val targetHandler = clientMapLock.withLock {
            clients.values.firstOrNull { it.clientData.name == dto.target }
        } ?: run {
            sendPacket(
                createPacket(
                    PacketType.USER_NOT_EXISTS,
                    ServerInfoDTO("The user does not exist.")
                )
            )
            return
        }

        transferTargets[dto.transferId] = targetHandler.clientData.id

        targetHandler.sendPacket(
            createPacket(
                PacketType.FILE_SEND_REQUEST,
                dto
            )
        )

        logger.info { "Forward FILE_SEND_REQUEST transferId=${dto.transferId} ${clientData.name} -> ${dto.target}" }
    }

    private fun handleFileSendComplete(dto: FileSendCompleteDTO) {
        val targetId = transferTargets.remove(dto.transferId) ?: return

        val targetHandler = clientMapLock.withLock { clients[targetId] } ?: return
        targetHandler.sendPacket(
            createPacket(
                PacketType.FILE_SEND_COMPLETE,
                dto
            )
        )

        logger.info { "Forward FILE_SEND_COMPLETE transferId=${dto.transferId}" }
    }
}