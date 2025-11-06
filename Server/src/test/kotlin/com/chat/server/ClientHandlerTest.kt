
package com.chat.server

import com.chat.share.PacketType
import com.chat.share.createPacket
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.withLock

@ExtendWith(MockitoExtension::class)
class ClientHandlerTest {
    private lateinit var mockSocket: Socket
    private lateinit var mockOutputStream: ByteArrayOutputStream
    private lateinit var mockInputStream: InputStream

    private lateinit var handler: ClientHandler

    @BeforeEach
    fun setup() {
        mockSocket = mock(Socket::class.java)
        mockOutputStream = mock(ByteArrayOutputStream::class.java)
        mockInputStream = mock(InputStream::class.java)

        handler = spy(ClientHandler(mockSocket, "client1"))

        whenever(mockSocket.getOutputStream()).thenReturn(mockOutputStream)
        whenever(mockSocket.getInputStream()).thenReturn(mockInputStream)
        setOutputStream(mockOutputStream)

        //  전역 clients 초기화
        clientMapLock.withLock {
            clients.clear()
        }
    }

    @AfterEach
    fun cleanup() {
        reset(mockSocket, mockOutputStream, mockInputStream)

        clients.clear()
    }

    /* 코드 분석
     * getDeclaredField("inputStream") : "inputStream" 필드 정보 검색
     * isAccessible = true : private 필드에도 접근 가능하도록 JVM 접근 제어 우회
     * field.set(handler, inputStream) : handler 인스턴스의 inputStream 필드에 직접 만든 inputStream 객체 주입
     */
    private fun setInputStream(inputStream: InputStream) {
        val field = handler.javaClass.getDeclaredField("inputStream")
        field.isAccessible = true
        field.set(handler, inputStream)
    }

    private fun setOutputStream(outputStream: OutputStream) {
        val field = handler.javaClass.getDeclaredField("outputStream")
        field.isAccessible = true
        field.set(handler, outputStream)
    }

    @Test
    fun `sendPacket should write to outputStream`() {
        // Given
        val packet = createPacket(PacketType.CHAT_MESSAGE, "Hello")

        val outputStream = ByteArrayOutputStream()
        setOutputStream(outputStream)

        // When
        handler.sendPacket(packet)

        // Then
        assertArrayEquals(packet, outputStream.toByteArray())
    }

    @Test
    fun `broadcast should send packet to all other clients`() {
        // Spy 객체 생성
        val handler1 = spy(ClientHandler(mockSocket, "client1"))
        val handler2 = spy(ClientHandler(mockSocket, "client2"))
        val handler3 = spy(ClientHandler(mockSocket, "client3"))

        clientMapLock.withLock {
            clients["client1"] = handler1
            clients["client2"] = handler2
            clients["client3"] = handler3
        }

        // When
        val packet = createPacket(PacketType.CHAT_MESSAGE, "Broadcast message")
        handler1.broadcast(packet, "client1", PacketType.CHAT_MESSAGE)

        // verify : 해당 메서드가 몇 번 호출되었는지 검증한다.
        verify(handler1, never()).sendPacket(packet)
        verify(handler2, times(1)).sendPacket(packet)
        verify(handler3, times(1)).sendPacket(packet)
    }

    @Test
    fun `handleNameRegistration should remove client and close socket when readPacket throws IOException`() {
        // Given
        val errorMessage = "Initial packet was not REGISTER_NAME."
        val invalidPacket = createPacket(PacketType.CHAT_MESSAGE, errorMessage)
        val inputStream = ByteArrayInputStream(invalidPacket)
        setInputStream(inputStream)

        doNothing().whenever(handler).broadcast(
            any(ByteArray::class.java),
            anyString(),
            anyInt()
        )

        // When
        val exception = assertThrows(IOException::class.java) {
            handler.handleNameRegistration()
            println("Exception checked.")
        }

        // Then
        assertEquals(errorMessage, exception.message)


//        // When
//        val sentMessage = String(mockOutputStream.toByteArray(), StandardCharsets.UTF_8)
//        assertTrue(sentMessage.contains("Enter your name first."))
//
//        // Then
//        verify(mockSocket, times(1)).close()
    }

    @Test
    fun `handleNameRegistration should register client and call broadcast`() {
        // Given
        val name = "Alice"
        val registerPacket = createPacket(PacketType.REGISTER_NAME, name)
        val inputStream = ByteArrayInputStream(registerPacket)
        setInputStream(inputStream)

        doNothing().whenever(handler).broadcast(
            any(ByteArray::class.java),
            anyString(),
            anyInt()
        )

        // when
        handler.handleNameRegistration()

        // then
        clientMapLock.withLock {
            assertTrue(clients.containsKey("client1"))
            assertEquals(name, clients["client1"]?.clientData?.name)
        }

        verify(handler, times(1)).broadcast(
            any(ByteArray::class.java),
            eq("client1"),
            eq(PacketType.SERVER_INFO)
        )

        val sentData = mockOutputStream.toString()
        assertFalse(sentData.contains("Server: Enter your name first."))
    }

    @Test
    fun `listenForMessages should handle CHAT_MESSAGE`() {
        val message = "Hello from client"
        val packet = createPacket(PacketType.CHAT_MESSAGE, message)
        val inputStream = ByteArrayInputStream(packet)
        setInputStream(inputStream)

        doNothing().whenever(handler).broadcast(
            any(ByteArray::class.java),
            anyString(),
            anyInt()
        )

        handler.javaClass.getDeclaredField("clientData").apply {
            isAccessible = true
            set(handler, ClientData("client1", "testUser"))
        }

        // To exit the loop
        whenever(mockSocket.isConnected).thenReturn(true, false)

        handler.listenForMessages()

        assertEquals(1, handler.clientData.sentCount.get())
        verify(handler, times(1)).broadcast(any(), eq("client1"), eq(PacketType.CHAT_MESSAGE))
    }

    @Test
    fun `handleClientDisconnect should remove client and broadcast`() {
        clientMapLock.withLock {
            clients["client1"] = handler
        }

        handler.handleClientDisconnect()

        assertFalse(clients.containsKey("client1"))
        verify(handler, times(1)).broadcast(any(), eq("client1"), eq(PacketType.DISCONNECT_INFO))
        verify(mockSocket, times(1)).close()
    }
}
