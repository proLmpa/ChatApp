package com.chat.server

import com.chat.share.PacketType
import com.chat.share.Protocol
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientHandlerMessageTests : ClientHandlerBaseTest() {

    @Test
    fun `when CHAT_MESSAGE received then broadcast`() {
        // Given
        handler.clientData.name = "Alice"

        val packet = packet(PacketType.CHAT_MESSAGE, "Hello, world!")

        whenever(conn.readPacket())
            .thenReturn(packet)
            .thenThrow(RuntimeException("stop"))

        whenever(conn.isConnected())
            .thenReturn(true)
            .thenReturn(false)

        whenever(conn.writePacket(any())).then {}

        addClient("client2", "Bob")
        addClient("client3", "John")

        // When
        handler.listenForMessages()

        // Then
        verify(clients["client2"], times(1))!!.sendPacket(any())
        verify(clients["client3"], times(1))!!.sendPacket(any())
        assertEquals(1, handler.clientData.sentCount.get())
        assertEquals(1, clients["client2"]!!.clientData.receivedCount.get())
        assertEquals(1, clients["client3"]!!.clientData.receivedCount.get())
    }

    @Test
    fun `when WHISPER_MESSAGE received then send it to the target`() {
        // Given
        handler.clientData.name = "Alice"

        addClient("client2", "Bob")
        val packet = packet(PacketType.WHISPER, "Bob Hi, Bob")

        whenever(conn.readPacket())
            .thenReturn(packet)
            .thenThrow(RuntimeException("stop"))

        whenever(conn.isConnected())
            .thenReturn(true)
            .thenReturn(false)

        // When
        handler.listenForMessages()

        // Then
        verify(handler, times(1)).handleWhisper(any())
        verify(clients["client2"], times(1))!!.sendPacket(any())
        assertEquals(1, handler.clientData.sentCount.get())
        assertEquals(1, clients["client2"]!!.clientData.receivedCount.get())
    }

    @Test
    fun `when WHISPER_MESSAGE received and USER_NOT_EXISTS then send failed`() {
        // Given
        val packet = packet(PacketType.WHISPER, "Ghost hi")

        whenever(conn.readPacket())
            .thenReturn(packet)
            .thenThrow(RuntimeException("stop"))

        whenever(conn.isConnected())
            .thenReturn(true)
            .thenReturn(false)

        // When
        handler.listenForMessages()

        // Then
        verify(handler, times(1)).sendPacket(
            argThat { Protocol.readPacket(this.inputStream()).type == PacketType.USER_NOT_EXISTS }
        )

    }

}
