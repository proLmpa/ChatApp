package com.chat.client

import com.chat.share.ConnectionService
import com.chat.share.PacketType
import com.chat.share.Protocol
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mock
import org.mockito.kotlin.argThat
import org.mockito.kotlin.verify
import java.io.ByteArrayInputStream
import kotlin.test.Test

class ClientSessionTest {
    private lateinit var conn: ConnectionService
    private lateinit var client: ClientSession

    @BeforeEach
    fun setUp() {
        conn = mock(ConnectionService::class.java)
        client = ClientSession(conn)
    }

    private fun provideInput(chat: String) {
        System.setIn(ByteArrayInputStream(chat.toByteArray()))
    }

    @Test
    fun `when REGISTER_NAME sent then writePacket is called`() {
        // Given
        provideInput("Alice\nexit\n")

        // When
        client.sendMessageLoop()

        // Then
        verify(conn).writePacket(
            argThat { String(this).contains("Alice") }
        )
    }

    @Test
    fun `when registered then normal chat message sends CHAT_MESSAGE`() {
        // Given
        provideInput("Alice\nhello\nexit\n")

        // When
        client.sendMessageLoop()

        // Then
        verify(conn).writePacket(
            argThat { String(this).contains("hello")}
        )
    }

    @Test
    fun `when exit typed then send DISCONNECT_REQUEST` () {
        // Given
        provideInput("exit\n")

        // When
        client.sendMessageLoop()

        // Then
        verify(conn).writePacket(
            argThat { Protocol.readPacket(this.inputStream()).type == PacketType.DISCONNECT_REQUEST }
        )
    }

    @Test
    fun `when rename keyword used then send UPDATE_NAME packet`() {
        // Given
        provideInput("Alice\n/n Bob\nexit\n")

        // When
        client.sendMessageLoop()

        // Then
        verify(conn).writePacket(
            argThat { String(this).contains("Bob") }
        )
    }

    @Test
    fun `when whisper used then send WHISPER packet`() {
        // Given
        provideInput("Alice\n/w Bob hello, bob\nexit\n")

        // When
        client.sendMessageLoop()

        // Then
        verify(conn).writePacket(
            argThat { String(this).contains("hello, bob")}
        )
    }

}