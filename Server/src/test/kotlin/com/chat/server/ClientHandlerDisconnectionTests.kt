package com.chat.server

import com.chat.share.PacketType
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test

class ClientHandlerDisconnectionTests : ClientHandlerBaseTest() {

    @Test
    fun `when DISCONNECT_REQUEST received then quit connection`() {
        // Given
        val packet = disconnectPacket()

        whenever(conn.readPacket())
            .thenReturn(packet)
            .thenThrow(RuntimeException("stop"))

        whenever(conn.isConnected()).thenReturn(true)

        doNothing().whenever(handler).handleClientDisconnect()

        // When
        handler.run()

        // Then
        verify(handler, times(1)).handleClientDisconnect()
    }

    @Test
    fun `when DISCONNECT_REQUEST received then remove user` () {
        // When
        handler.handleClientDisconnect()

        // Then
        assert(!clients.containsKey("client1"))
        verify(handler, times(1)).broadcast(any(), eq("client1"), eq(PacketType.DISCONNECT_INFO) )
    }
}
