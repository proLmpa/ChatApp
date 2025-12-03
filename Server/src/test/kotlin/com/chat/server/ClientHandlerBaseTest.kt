package com.chat.server

import com.chat.share.ChatMessageDTO
import com.chat.share.ConnectionService
import com.chat.share.Packet
import com.chat.share.PacketType
import com.chat.share.Protocol
import com.chat.share.Protocol.createPacket
import com.chat.share.RegisterNameDTO
import com.chat.share.ServerInfoDTO
import com.chat.share.UpdateNameDTO
import com.chat.share.WhisperDTO

import org.junit.jupiter.api.*
import org.mockito.Mockito.*
import org.mockito.kotlin.spy
import kotlin.concurrent.withLock

open class ClientHandlerBaseTest {

    protected lateinit var conn: ConnectionService
    protected lateinit var handler: ClientHandler

    @BeforeEach
    fun setup() {
        clientMapLock.withLock {
            clients.clear()
        }

        conn = mock(ConnectionService::class.java)
        handler = spy(ClientHandler(conn,"client1"))
    }

    protected inline fun <reified T> packet(type: PacketType, dto: T): Packet {
        val bytes = createPacket(type, dto)
        return Protocol.readPacket(bytes.inputStream())
    }

    protected fun registerNamePacket(name: String): Packet =
        packet(PacketType.REGISTER_NAME, RegisterNameDTO(name))

    protected fun updateNamePacket(newName: String): Packet =
        packet(PacketType.UPDATE_NAME, UpdateNameDTO(newName))

    protected fun chatMessagePacket(sender: String, message: String): Packet =
        packet(PacketType.CHAT_MESSAGE, ChatMessageDTO(sender, message))

    protected fun whisperPacket(sender: String, target: String, message: String): Packet =
        packet(PacketType.WHISPER, WhisperDTO(sender, target, message))

    protected fun disconnectPacket(): Packet =
        packet(PacketType.DISCONNECT_REQUEST, ServerInfoDTO(""))

    protected fun addClient(id: String, name: String? = null): ClientHandler {
        val conn = mock(ConnectionService::class.java)
        val handler = spy(ClientHandler(conn, id))

        handler.clientData.name = name

        clientMapLock.withLock {
            clients[id] = handler
        }

        return handler
    }
}
