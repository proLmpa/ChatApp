package com.chat.client

import com.chat.share.ChatMessageDTO
import com.chat.share.ConnectionService
import com.chat.share.Packet
import com.chat.share.PacketType
import com.chat.share.RegisterNameDTO
import com.chat.share.UpdateNameDTO
import com.chat.share.WhisperDTO
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientSessionTest {
    private lateinit var conn: ConnectionService
    private lateinit var client: ClientSession

    @BeforeEach
    fun setUp() {
        conn = mock(ConnectionService::class.java)
    }

    private fun scriptedInput(vararg lines: String): () -> String? {
        val iter = lines.toList().iterator()
        return { if (iter.hasNext()) iter.next() else null }
    }

    private fun markRegisteredForTest() {
        client.clientState.isRegistered = true
    }

    @Test
    fun `when REGISTER_NAME sent then writePacket is called`() {
        // Given
        client = ClientSession(conn, scriptedInput("Alice", "exit"))

        // When
        client.sendMessageLoop()

        // Then
        val captor = argumentCaptor<Packet>()
        verify(conn, atLeastOnce()).writePacket(captor.capture())

        val packet = captor.firstValue
        assertEquals(PacketType.REGISTER_NAME, packet.type)
        assertEquals("Alice", packet.toDTO<RegisterNameDTO>().name)
    }

    @Test
    fun `when registered then normal chat message sends CHAT_MESSAGE`() {
        // Given
        client = ClientSession(conn, scriptedInput("hello", "exit"))
        markRegisteredForTest()

        // When
        client.sendMessageLoop()

        // Then
        val captor = argumentCaptor<Packet>()
        verify(conn, atLeastOnce()).writePacket(captor.capture())

        val packet = captor.allValues.first { it.type == PacketType.CHAT_MESSAGE }
        assertEquals("hello", packet.toDTO<ChatMessageDTO>().message)
    }

    @Test
    fun `when exit typed then send DISCONNECT_REQUEST` () {
        // Given
        client = ClientSession(conn, scriptedInput("exit"))
        markRegisteredForTest()

        // When
        client.sendMessageLoop()

        // Then
        val captor = argumentCaptor<Packet>()
        verify(conn).writePacket(captor.capture())

        val packet = captor.firstValue
        assertEquals(PacketType.DISCONNECT_REQUEST, packet.type)
    }

    @Test
    fun `when rename keyword used then send UPDATE_NAME packet`() {
        // Given
        client = ClientSession(conn, scriptedInput("/n Bob", "exit"))
        markRegisteredForTest()

        // When
        client.sendMessageLoop()

        // Then
        val captor = argumentCaptor<Packet>()
        verify(conn, atLeastOnce()).writePacket(captor.capture())

        val packet = captor.allValues.first { it.type == PacketType.UPDATE_NAME }
        val dto = packet.toDTO<UpdateNameDTO>()

        assertEquals("Bob", dto.newName)
    }

    @Test
    fun `when whisper used then send WHISPER packet`() {
        // Given
        client = ClientSession(conn, scriptedInput("/w Bob hello, bob", "exit"))
        markRegisteredForTest()

        // When
        client.sendMessageLoop()

        // Then
        val captor = argumentCaptor<Packet>()
        verify(conn, atLeastOnce()).writePacket(captor.capture())

        val packet = captor.allValues.first { it.type == PacketType.WHISPER }
        val dto = packet.toDTO<WhisperDTO>()

        assertEquals("Bob", dto.target)
        assertEquals("hello, bob", dto.message)
    }
}