package com.chat.server

import com.chat.share.PacketType
import com.chat.share.createPacket
import com.chat.share.readPacket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

class ClientHandler(private val clientSocket: Socket, private val clientId: String): Thread() {
    private val clientData = ClientData(clientId)
    // ::isInitialized 검사를 위해 lateinit 사용
    private lateinit var inputStream: InputStream
    private lateinit var outputStream: OutputStream

    // 특정 클라이언트의 출력 스트림 접근을 동기화하기 위한 락
    private val clientOutputLock = ReentrantLock()

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

    private fun broadcast(packetBytes: ByteArray, senderId: String? = null, packetType: Int) {
        clientMapLock.withLock {
            clients.values.forEach { handler ->
                if (handler.clientData.id != senderId) {
                    handler.sendPacket(packetBytes)

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

        // 1. 이름 등록 대기
        handleNameRegistration()

        // 2. 메시지 수신 종료
        listenForMessages()
    } catch (_: Exception) {
        val clientNameOrId = clientData.name ?: clientId
        println("Client $clientNameOrId disconnected.")
    } finally {
        // 3. 접속 종료 처리
        if (!clientSocket.isClosed) {
            handleClientDisconnect()
        }
    }

    // Add client
    private fun handleNameRegistration() {
        clientMapLock.withLock {
            clients[clientId] = this
        }

        val registerPacket = try {
            readPacket(inputStream)
        } catch  (e: IOException) {
            clientSocket.close()
            clientMapLock.withLock { clients.remove(clientId) }
            throw e
        }

        if (registerPacket.type != PacketType.REGISTER_NAME) {
            sendPacket(createPacket(PacketType.SERVER_INFO, "Server: Enter your name first."))
            clientSocket.close()
            throw IOException("Initial packet was not REGISTER_NAME.")
        }

        val clientName = registerPacket.getBodyAsString().trim()
        if (clientName.isEmpty()) {
            sendPacket(createPacket(PacketType.SERVER_INFO, "Server: You must enter a name first."))
            clientSocket.close()
            throw IOException("Initial packet was not REGISTER_NAME.")
        }

        clientData.name = clientName

        val connectedMessage = "'$clientName' entered."
        broadcast(createPacket(PacketType.SERVER_INFO, connectedMessage), clientData.id, PacketType.SERVER_INFO)
        println(connectedMessage)
    }

    // Listener - Handle Client Messages
    private fun listenForMessages() {
        while (clientSocket.isConnected && !clientSocket.isInputShutdown) {
            val packet = readPacket(inputStream)

            when (packet.type) {
                PacketType.CHAT_MESSAGE -> {
                    clientData.sentCount.incrementAndGet()

                    val message = packet.getBodyAsString().trim()
                    val chatMessage = "[${clientData.name}] $message"

                    broadcast(createPacket(PacketType.CHAT_MESSAGE, chatMessage), clientData.id, PacketType.CHAT_MESSAGE)
                    println("Chat from ${clientData.name}: $message")
                }
                PacketType.DISCONNECT_REQUEST -> {
                    println("Client '${clientData.name}' sent DISCONNECT_REQUEST.")
                    return
                }
            }
        }
    }

    // Remove client
    private fun handleClientDisconnect() {
        val name = clientData.name ?: clientId
        val sent = clientData.sentCount.get()
        val received = clientData.receivedCount.get()

        clientMapLock.withLock {
            clients.remove(clientId)
        }

        val disconnectedMessage = "'$name' disconnected. (Send: $sent, Received: $received)"
        val disconnectedPacket = createPacket(PacketType.DISCONNECT_INFO, disconnectedMessage)

        sendPacket(disconnectedPacket)
        broadcast(disconnectedPacket, clientData.id, PacketType.DISCONNECT_INFO)
        println(disconnectedMessage)

        clientSocket.close()
    }
}