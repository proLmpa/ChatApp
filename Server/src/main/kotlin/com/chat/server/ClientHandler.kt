package com.chat.server

import com.chat.share.Packet
import com.chat.share.PacketType
import com.chat.share.Protocol.createPacket
import com.chat.share.Protocol.readPacket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
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
    private val clientSocket: Socket,
    private val clientId: String
): Thread() {
    val clientData = ClientData(clientId)
    private lateinit var inputStream: InputStream
    private lateinit var outputStream: OutputStream

    private val clientOutputLock = ReentrantLock()

    /**
     * 클라이언트에게 패킷 바이트를 전송합니다.
     * 이 함수는 순수하게 I/O만 담당하며, 카운팅 로직은 호출부(broadcast)에서 처리합니다.
     * @param packetBytes 전송할 패킷의 바이트 배열
     */
    fun sendPacket(packetBytes: ByteArray) {
        clientOutputLock.withLock {
            try {
                if (!::outputStream.isInitialized) return

                outputStream.write(packetBytes)
                outputStream.flush()
            } catch (_: IOException) {
                println("[Server] Error: Failed to send packet to ${clientData.name ?: clientId}.")
                clientSocket.close()
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
            clients.values.forEach { handler ->
                if (handler.clientData.id != senderId) {
                    handler.sendPacket(packetBytes!!)

                    if (packetType == PacketType.CHAT_MESSAGE) {
                        handler.clientData.receivedCount.incrementAndGet()
                    }
                }
            }
        }
    }

    // 메인 로직
    override fun run() = try {
        inputStream = clientSocket.getInputStream()
        outputStream = clientSocket.getOutputStream()

        clientMapLock.withLock {
            clients[clientId] = this
        }

        println("Client temporary connection accepted: $clientId (waiting for name)")
        sendPacket(createPacket(PacketType.SERVER_INFO, "Welcome! Please register your name."))

        listenForMessages()
    } catch (_: Exception) {
        val clientNameOrId = clientData.name ?: clientId
        println("Client $clientNameOrId disconnected.")
    } finally {
        if (!clientSocket.isClosed) {
            handleClientDisconnect()
        }
    }

    // 클라이언트 패킷 유형에 따른 처리 리스너
    internal fun listenForMessages() {
        while (clientSocket.isConnected && !clientSocket.isInputShutdown) {
            val packet = readPacket(inputStream)

            when (packet.type) {
                PacketType.REGISTER_NAME -> handleNameRegistration(packet)
                PacketType.CHAT_MESSAGE -> {
                    val sender = clientData.name ?: clientId
                    val message = packet.getBodyAsString().trim()
                    val chatMessage = "[$sender] $message"

                    broadcast(createPacket(PacketType.CHAT_MESSAGE, chatMessage), clientData.id, PacketType.CHAT_MESSAGE)
                    println(chatMessage)

                    clientData.sentCount.incrementAndGet()
                }
                PacketType.UPDATE_NAME -> {
                    handleUpdateNameRequest(packet)
                }
                PacketType.WHISPER -> {
                    handleWhisper(packet)
                    clientData.sentCount.incrementAndGet()
                }
                PacketType.DISCONNECT_REQUEST -> {
                    val sender = clientData.name ?: clientId
                    println("Client $sender sent DISCONNECT_REQUEST.")
                    return
                }

                else -> {}
            }
        }
    }

    // 클라이언트 추가 및 핸들러 등록
    internal fun handleNameRegistration(packet: Packet) {
        val clientName = packet.getBodyAsString().trim()

        if (handleNameIsBlank(clientName, true)) return
        if (handleNameDuplication(clientName, true)) return

        clientData.name = clientName

        val connectedMessage = "$clientName entered."
        sendPacket(createPacket(PacketType.SERVER_SUCCESS, "Welcome, $clientName!"))
        broadcast(createPacket(PacketType.SERVER_INFO, connectedMessage), clientData.id, PacketType.SERVER_INFO)
        println(connectedMessage)
    }

    // 클라이언트 이름 공백 확인
    internal fun handleNameIsBlank(clientName: String, isInitial: Boolean = false): Boolean {
        return when {
            clientName.isEmpty() -> {
                val type = if (isInitial) PacketType.INITIAL_NAME_CHANGE_FAILED else PacketType.UPDATE_NAME_FAILED
                sendPacket(createPacket(type, "Name cannot be empty."))
                true
            }
            else -> false
        }
    }

    // 클라이언트 이름 중복 확인
    internal fun handleNameDuplication(clientName: String, isInitial: Boolean): Boolean {
        var duplicateFlag = false
        val senderId = clientData.id

        clientMapLock.withLock {
            for (handler in clients.values) {
                val existingName = handler.clientData.name

                if (existingName != null && existingName == clientName && handler.clientData.id != senderId) {
                    duplicateFlag = true
                    break
                }
            }
        }

        if (duplicateFlag) {
            val type = if (isInitial) PacketType.INITIAL_NAME_CHANGE_FAILED else PacketType.UPDATE_NAME_FAILED
            sendPacket(createPacket(type, "Name is duplicated."))
        }

        return duplicateFlag
    }

    internal fun handleUpdateNameRequest(packet: Packet) {
        val clientName = packet.getBodyAsString().trim()

        if (handleNameIsBlank(clientName, false)) return
        if (handleNameDuplication(clientName, false)) return

        val oldName = clientData.name
        clientData.name = clientName

        val nameUpdateMessage = "User '$oldName' is changed to '$clientName'."
        sendPacket(createPacket(PacketType.SERVER_SUCCESS, nameUpdateMessage))
        broadcast(createPacket(PacketType.SERVER_INFO, nameUpdateMessage), clientData.id, PacketType.SERVER_INFO)
        println(nameUpdateMessage)
    }

    internal fun handleWhisper(packet: Packet) {
        val body = packet.getBodyAsString()
        val parts = body.split(" ", limit = 2)
        val targetName = parts[0]
        val message = parts[1]

        val targetHandler = clientMapLock.withLock {
            clients.values.firstOrNull { it.clientData.name == targetName }
        }

        if (targetHandler == null) {
            sendPacket(createPacket(PacketType.USER_NOT_EXISTS, "User '$targetName' does not exist."))
            return
        }

        val msgToTarget = "[${clientData.name} -> ${targetHandler.clientData.name}] $message"
        val msgToSender = "[You -> ${targetHandler.clientData.name}] $message"

        targetHandler.sendPacket(createPacket(PacketType.WHISPER, msgToTarget))
        targetHandler.clientData.receivedCount.incrementAndGet()

        sendPacket(createPacket(PacketType.WHISPER, msgToSender))
    }

    // 클라이언트 제거 및 disconnect 통지
    internal fun handleClientDisconnect() {
        val name = clientData.name ?: clientId
        val sent = clientData.sentCount.get()
        val received = clientData.receivedCount.get()

        clientMapLock.withLock {
            clients.remove(clientId)
        }

        val disconnectedMessage = "$name disconnected. (Send: $sent, Received: $received)"
        val disconnectedPacket = createPacket(PacketType.DISCONNECT_INFO, disconnectedMessage)

        sendPacket(disconnectedPacket)
        broadcast(disconnectedPacket, clientData.id, PacketType.DISCONNECT_INFO)
        println(disconnectedMessage)

        clientSocket.close()
    }
}