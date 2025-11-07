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

// 서버의 모든 클라이언트 정보를 담는 공유 자원 (Map)
val clients = mutableMapOf<String, ClientHandler>()

// 공유 자원에 대한 접근을 동기화하기 위한 락
val clientMapLock = ReentrantLock()

// 개별 클라이언트의 통신 및 세션 관리를 담당하는 스레드 핸들러
class ClientHandler(
    private val clientSocket: Socket,
    private val clientId: String
): Thread() {
    val clientData = ClientData(clientId)

    // ::isInitialized 검사를 위한 late init 사용
    private lateinit var inputStream: InputStream
    private lateinit var outputStream: OutputStream

    // 특정 클라이언트의 출력 스트림 접근을 동기화하기 위한 락
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

    // 클라이언트 추가 및 핸들러 등록
    internal fun handleNameRegistration(registerPacket: Packet) {

        val clientName = registerPacket.getBodyAsString().trim()
        if (clientName.isEmpty()) {
            sendPacket(createPacket(PacketType.SERVER_INFO, "Server: Name cannot be empty."))
            return
        }

        clientData.name = clientName

        val connectedMessage = "$clientName entered."
        broadcast(createPacket(PacketType.SERVER_INFO, connectedMessage), clientData.id, PacketType.SERVER_INFO)
        println(connectedMessage)
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
                    println("Chat from $sender: $message")

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