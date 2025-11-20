package com.chat.server

import com.chat.share.ConnectionService
import com.chat.share.Packet
import com.chat.share.PacketType
import com.chat.share.Protocol
import com.chat.share.Protocol.createPacket

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

    protected fun packet(type: PacketType, body: String): Packet {
        val bytes = createPacket(type, body)
        return Protocol.readPacket(bytes.inputStream())
    }

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
