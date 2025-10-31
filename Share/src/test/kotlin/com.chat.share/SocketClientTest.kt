package com.chat.share

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

class SocketClientTest {
    private lateinit var mockSocket: Socket
    private lateinit var mockInputStream: InputStream
    private lateinit var mockOutputStream: OutputStream

    @BeforeEach
    fun setUp() {
        mockSocket = Mockito.mock(Socket::class.java)
        mockInputStream = Mockito.mock(InputStream::class.java)
        mockOutputStream = Mockito.mock(OutputStream::class.java)
    }

    @AfterEach
    fun tearDown() {
        Mockito.reset(mockSocket, mockInputStream, mockOutputStream)
    }

    @Test
    fun `InputStream test - read data`() {
        // Given
        val testData = "Hello from server"
        val inputStream = ByteArrayInputStream(testData.toByteArray())
        whenever(mockSocket.getInputStream()).thenReturn(inputStream)

        // When
        val buffer = ByteArray(1024)
        val bytesRead = mockSocket.getInputStream().read(buffer)
        val result = String(buffer, 0, bytesRead)

        // Then
        Assertions.assertEquals(testData, result)
        Mockito.verify(mockSocket, Mockito.times(1)).getInputStream()
    }

    @Test
    fun `OutputStream test - write data`() {
        // Given
        val testData = "Hello to server"
        val outputStream = ByteArrayOutputStream()
        whenever(mockSocket.getOutputStream()).thenReturn(outputStream)

        // When
        mockSocket.getOutputStream().write(testData.toByteArray())
        mockSocket.getOutputStream().flush()

        // Then
        Assertions.assertEquals(testData, outputStream.toString())
        Mockito.verify(mockSocket, Mockito.times(2)).getOutputStream()
    }

    @Test
    fun `Mock InputStream test - verifying read data`() {
        // Given
        val testData = "Test message".toByteArray()
        whenever(mockInputStream.read(ArgumentMatchers.any())).thenAnswer { invocation ->
            val buffer = invocation.getArgument<ByteArray>(0)
            testData.copyInto(buffer)
            testData.size
        }

        // When
        val buffer = ByteArray(1024)
        val bytesRead = mockInputStream.read(buffer)

        // Then
        Assertions.assertEquals(testData.size, bytesRead)
        Assertions.assertEquals("Test message", String(buffer, 0, bytesRead))
        Mockito.verify(mockInputStream).read(ArgumentMatchers.any())
    }

    @Test
    fun `Mock OutputStream test - verifying write data`() {
        // Given
        val testData = "Test output".toByteArray()
        Mockito.doNothing().whenever(mockOutputStream).write(ArgumentMatchers.any<ByteArray>())
        Mockito.doNothing().whenever(mockOutputStream).flush()

        // When
        mockOutputStream.write(testData)
        mockOutputStream.flush()

        // Then
        Mockito.verify(mockOutputStream).write(testData)
        Mockito.verify(mockOutputStream).flush()
    }

    @Test
    fun `Verifying Socket Close`() {
        // Given
        Mockito.doNothing().whenever(mockSocket).close()

        // When
        mockSocket.close()

        // Then
        Mockito.verify(mockSocket).close()
    }

    @Test
    fun `Mock InputStream test - Read multiple times`() {
        // Given
        val messages = listOf("First", "Second", "Third")
        var callCount = 0

        whenever(mockInputStream.read(ArgumentMatchers.any())).thenAnswer { invocation ->
            if (callCount < messages.size) {
                val buffer = invocation.getArgument<ByteArray>(0)
                val data = messages[callCount].toByteArray()
                data.copyInto(buffer)
                callCount++
                data.size
            } else {
                -1 // EOF
            }
        }

        // When & Then
        val results = mutableListOf<String>()
        val buffer = ByteArray(1024)

        repeat(3) {
            val bytesRead = mockInputStream.read(buffer)
            if (bytesRead > 0) {
                results.add(String(buffer, 0, bytesRead))
            }
        }

        Assertions.assertEquals(messages, results)
        Mockito.verify(mockInputStream, Mockito.times(3)).read(ArgumentMatchers.any())
    }

    @Test
    fun `InputStream test - Exception try-catch`() {
        // Given
        whenever(mockInputStream.read(ArgumentMatchers.any())).thenThrow(RuntimeException::class.java)

        // When & Then
        Assertions.assertThrows(RuntimeException::class.java) {
            val buffer = ByteArray(1024)
            mockInputStream.read(buffer)
        }
        Mockito.verify(mockInputStream).read(ArgumentMatchers.any())
    }

    @Test
    fun `Socket Integration test - read & write`() {
        // Given
        val sendMessage = "REQUEST"
        val receiveMessage = "RESPONSE"

        val outputStream = ByteArrayOutputStream()
        val inputStream = ByteArrayInputStream(receiveMessage.toByteArray())

        whenever(mockSocket.getOutputStream()).thenReturn(outputStream)
        whenever(mockSocket.getInputStream()).thenReturn(inputStream)

        // When - Write
        mockSocket.getOutputStream().write(sendMessage.toByteArray())
        mockSocket.getOutputStream().flush()

        // Then - Write
        Assertions.assertEquals(sendMessage, outputStream.toString())

        // When - Read
        val buffer = ByteArray(1024)
        val bytesRead = mockSocket.getInputStream().read(buffer)
        val result = String(buffer, 0, bytesRead)

        // Then - Write
        Assertions.assertEquals(receiveMessage, result)
        Mockito.verify(mockSocket, Mockito.atLeastOnce()).getOutputStream()
        Mockito.verify(mockSocket, Mockito.atLeastOnce()).getInputStream()
    }
}