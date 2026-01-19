//package com.chat.server
//
//import com.chat.share.Packet
//import com.chat.share.PacketType
//import org.mockito.Mockito.times
//import org.mockito.kotlin.any
//import org.mockito.kotlin.argumentCaptor
//import org.mockito.kotlin.eq
//import org.mockito.kotlin.verify
//import org.mockito.kotlin.whenever
//import kotlin.test.Test
//import kotlin.test.assertEquals
//
//class ClientHandlerNameTests : ClientHandlerBaseTest() {
//
//    @Test
//    fun `when REGISTER_NAME received then register name`() {
//        // Given
//        val name = "Alice"
//        val packet = registerNamePacket(name)
//
//        whenever(conn.readPacket())
//            .thenReturn(packet)
//            .thenThrow(RuntimeException("stop"))
//
//        whenever(conn.isConnected())
//            .thenReturn(true)
//            .thenReturn(false)
//
//        // When
//        handler.listenForFrames()
//
//        // Then
//        assertEquals(name, handler.clientData.name)
//
//        verify(handler, times(1)).broadcast(
//            any(), eq("client1")
//        )
//    }
//
//    @Test
//    fun `when REGISTER_NAME is duplicated then send warning`() {
//        // Given
//        handler.clientData.name = "Alice"
//
//        addClient("client2", "Alice")
//        val packet = registerNamePacket("Alice")
//
//        whenever(conn.readPacket())
//            .thenReturn(packet)
//            .thenThrow(RuntimeException("stop"))
//
//        whenever(conn.isConnected())
//            .thenReturn(true)
//            .thenReturn(false)
//
//        // When
//        handler.listenForFrames()
//
//        // Then
//        val captor = argumentCaptor<Packet>()
//        verify(handler).sendPacket(captor.capture())
//
//        assertEquals(
//            PacketType.NAME_CANNOT_BE_DUPLICATED,
//            captor.firstValue.type
//        )
//    }
//
//    @Test
//    fun `when UPDATE_NAME received then name changes`() {
//        // Given
//        handler.clientData.name = "Alice"
//
//        val newName = "Alice2"
//        val packet = updateNamePacket(newName)
//
//        whenever(conn.readPacket())
//            .thenReturn(packet)
//            .thenThrow(RuntimeException("stop"))
//
//        whenever(conn.isConnected())
//            .thenReturn(true)
//            .thenReturn(false)
//
//        // When
//        handler.listenForFrames()
//
//        // Then
//        assertEquals(handler.clientData.name, newName)
//        verify(handler, times(1)).sendPacket(any())
//        verify(handler, times(1)).broadcast(any(), eq("client1"))
//    }
//
//    @Test
//    fun `when UPDATE_NAME duplicated then send failed`() {
//        // Given
//        handler.clientData.name = "Alice"
//
//        addClient("client2", "Bob")
//        val packet = updateNamePacket("Bob")
//
//        whenever(conn.readPacket())
//            .thenReturn(packet)
//            .thenThrow(RuntimeException("stop"))
//
//        whenever(conn.isConnected())
//            .thenReturn(true)
//            .thenReturn(false)
//
//        // when
//        handler.listenForFrames()
//
//        // Then
//        val captor = argumentCaptor<Packet>()
//        verify(handler).sendPacket(captor.capture())
//
//        assertEquals(
//            PacketType.NAME_CANNOT_BE_DUPLICATED,
//            captor.firstValue.type
//        )
//    }
//}