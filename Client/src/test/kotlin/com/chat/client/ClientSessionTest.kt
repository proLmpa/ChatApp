package com.chat.client

import com.chat.share.ChatMessageDTO
import com.chat.share.ConnectionService
import com.chat.share.PacketType
import com.chat.share.Protocol
import com.chat.share.RegisterNameDTO
import com.chat.share.UpdateNameDTO
import com.chat.share.WhisperDTO
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.kotlin.argThat
import org.mockito.kotlin.verify
import kotlin.test.Test

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

    private fun decode(bytes: ByteArray) = Protocol.readPacket(bytes.inputStream())

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
        verify(conn, atLeastOnce()).writePacket(
            argThat {
                val p = decode(this)
                p.type == PacketType.REGISTER_NAME && p.toDTO<RegisterNameDTO>().name == "Alice"
            }
        )
    }

    @Test
    fun `when registered then normal chat message sends CHAT_MESSAGE`() {
        // Given
        client = ClientSession(conn, scriptedInput("hello", "exit"))
        markRegisteredForTest()

        // When
        client.sendMessageLoop()

        // Then
        verify(conn, atLeastOnce()).writePacket(
            argThat {
                val p = decode(this)
                p.type == PacketType.CHAT_MESSAGE && p.toDTO<ChatMessageDTO>().message == "hello"
            }
        )
    }

    @Test
    fun `when exit typed then send DISCONNECT_REQUEST` () {
        // Given
        client = ClientSession(conn, scriptedInput("exit"))
        markRegisteredForTest()

        // When
        client.sendMessageLoop()

        // Then
        verify(conn).writePacket(
            argThat {
                val p = decode(this)
                p.type == PacketType.DISCONNECT_REQUEST
            }
        )
    }

    @Test
    fun `when rename keyword used then send UPDATE_NAME packet`() {
        // Given
        client = ClientSession(conn, scriptedInput("/n Bob", "exit"))
        markRegisteredForTest()

        // When
        client.sendMessageLoop()

        // Then
        verify(conn, atLeastOnce()).writePacket(
            argThat {
                val p = decode(this)
                p.type == PacketType.UPDATE_NAME && p.toDTO<UpdateNameDTO>().newName == "Bob"
            }
        )
    }

    @Test
    fun `when whisper used then send WHISPER packet`() {
        // Given
        client = ClientSession(conn, scriptedInput("/w Bob hello, bob", "exit"))
        markRegisteredForTest()

        // When
        client.sendMessageLoop()

        // Then
        verify(conn, atLeastOnce()).writePacket(
            argThat {
                val p = decode(this)
                p.type == PacketType.WHISPER &&
                        p.toDTO<WhisperDTO>().target == "Bob" &&
                        p.toDTO<WhisperDTO>().message == "hello, bob"
            }
        )
    }
}