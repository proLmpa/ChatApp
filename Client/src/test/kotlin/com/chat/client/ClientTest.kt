import com.chat.client.ShutdownFlag
import com.chat.client.receivePacket
import com.chat.client.sendMessageLoop
import com.chat.share.PacketType
import com.chat.share.createPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.argThat
import org.mockito.kotlin.*
import java.io.*
import java.net.Socket
import java.nio.charset.StandardCharsets

class ClientTest {

    // Mock 객체 선언
    private val mockOutputStream: OutputStream = mock()
    private val mockSocket: Socket = mock()
    private val shutdownFlag = ShutdownFlag(false)

    // 콘솔 출력 리다이렉션을 위한 변수
    private val standardOut = System.out
    private val outputStreamCaptor = ByteArrayOutputStream()

    @BeforeEach
    fun setUp() {
        // 테스트 시작 전 System.out을 캡처 스트림으로 리다이렉션하여 콘솔 출력을 확인 가능하게 함
        System.setOut(PrintStream(outputStreamCaptor, true, StandardCharsets.UTF_8.name()))
        // Mock Socket의 상태 설정
        whenever(mockSocket.isConnected).thenReturn(true)
        whenever(mockSocket.isInputShutdown).thenReturn(false)
    }

    @AfterEach
    fun tearDown() {
        // 테스트 종료 후 System.out을 원래대로 복원
        System.setOut(standardOut)
    }

    // --- sendMessageLoop 테스트 ---

    @Test
    fun sendMessageLoop_shouldSendChatMessageAndFlush() {
        // Given: 사용자 입력 "hello" 후 "exit"
        val message = "hello"
        val input = "$message\nexit\n"
        System.setIn(ByteArrayInputStream(input.toByteArray()))

        // When: sendMessageLoop 실행
        sendMessageLoop(mockOutputStream, shutdownFlag)

        // Then 1: chat message 전송 검증
        // createPacket을 사용하여 기대하는 CHAT_MESSAGE 패킷 바이트를 생성
        val expectedChatPacketBytes = createPacket(PacketType.CHAT_MESSAGE, message)

        // mockOutputStream.write가 기대하는 CHAT_MESSAGE 패킷으로 호출되었는지 검증
        verify(mockOutputStream).write(argThat<ByteArray> { bytes -> bytes.contentEquals(expectedChatPacketBytes) })

        // Then 2: DISCONNECT_REQUEST 전송 검증
        val expectedDisconnectPacketBytes = createPacket(PacketType.DISCONNECT_REQUEST, "")
        verify(mockOutputStream).write(argThat<ByteArray> { bytes -> bytes.contentEquals(expectedDisconnectPacketBytes) })

        // Then 3: flush 호출 검증
        verify(mockOutputStream, times(2)).flush() // CHAT_MESSAGE와 DISCONNECT_REQUEST 각각에 대해 flush 호출

        // Then 4: ShutdownFlag가 true로 설정되었는지 검증
        assertEquals(true, shutdownFlag.isIntentional)
    }

    @Test
    fun sendMessageLoop_shouldIgnoreBlankInput() {
        // Given: 공백 입력 후 "exit"
        val input = "\n   \nexit\n"
        System.setIn(ByteArrayInputStream(input.toByteArray()))

        // When: sendMessageLoop 실행
        sendMessageLoop(mockOutputStream, shutdownFlag)

        // Then: CHAT_MESSAGE 전송은 없었는지 검증
        val expectedChatPacketBytes = createPacket(PacketType.CHAT_MESSAGE, "any") // 임의의 패킷으로 확인

        // Mockito의 `never()`를 사용하여 write가 CHAT_MESSAGE와 관련된 호출로 사용되지 않았는지 검증
        verify(mockOutputStream, never()).write(argThat<ByteArray> { bytes -> bytes.contentEquals(expectedChatPacketBytes) })

        // DISCONNECT_REQUEST는 전송되었는지 검증
        verify(mockOutputStream, times(1)).write(any<ByteArray>())
        verify(mockOutputStream, times(1)).flush()
        assertEquals(true, shutdownFlag.isIntentional)
    }


    // --- receivePacket 테스트 ---

    /**
     * 특정 패킷을 시뮬레이션하기 위한 Helper 함수.
     * Mockito를 사용하여 InputStream의 readInt와 readFully를 스터빙하여 readPacket이 동작하도록 함.
     */
    private fun simulateReadPacket(mockInputStream: InputStream, type: Int, data: String) {
        val packet = createPacket(type, data)
        val bodyLength = data.toByteArray(StandardCharsets.UTF_8).size
        val totalLength = 8 + bodyLength

        // readInt (총 길이) -> readInt (타입) -> readFully (바디) 순으로 Mocking

        // Mockito의 inOrder를 사용하여 호출 순서를 지정
        val inOrder = inOrder(mockInputStream)

        // 1. readInt() 호출 시 totalLength 반환
        inOrder.verify(mockInputStream).read(any(), any(), any())

        // Mockito-Kotlin의 `doAnswer`를 사용하여 DataInputStream의 readInt와 readFully 동작을 시뮬레이션
        // 실제 readPacket은 DataInputStream을 사용하므로, low-level mocking은 복잡합니다.
        // 여기서는 Mockito-Kotlin의 `mock`을 사용하여 `readPacket`의 의존성을 직접 모방합니다.

        // 실제 readPacket이 DataInputStream을 사용하므로, 직접 Stream을 Mock하는 것이 아니라
        // 바이트 배열을 사용하여 MockInputStream에 패킷 바이트를 주입하는 방식을 사용합니다.
        // 하지만 readPacket이 내부에서 readInt/readFully를 사용하는 Blocking I/O 패턴이므로,
        // 실제 테스트를 위해서는 실제 ByteArrayInputStream을 사용하고, readPacket의 동작을 스터빙합니다.

        // 🚨 Note: Mocking DataInputStream's readInt/readFully on a raw InputStream mock is complex.
        // We will use a ByteArrayInputStream that contains the necessary packet bytes.
        // Since `readPacket` is external, we'll focus on what happens AFTER `readPacket` returns.
        // But for isolation, let's use the simplest mock possible:

        val rawPacket = createPacket(type, data)
        whenever(mockInputStream.read(any(), any(), any()))
            .thenAnswer { invocation ->
                val buffer = invocation.arguments[0] as ByteArray
                val offset = invocation.arguments[1] as Int
                val len = invocation.arguments[2] as Int

                // 간단하게 한 번만 패킷을 읽는다고 가정하고, 이후는 EOF 처리
                val stream = ByteArrayInputStream(rawPacket)
                stream.read(buffer, offset, len)
            }
            .thenThrow(IOException()) // 두 번째 호출부터는 에러로 간주

        // **더 안정적인 방법: readPacket을 직접 mocking할 수 없으므로,
        // Mockito를 사용하려는 목적에 맞게 `receivePacket`이 한 번의 성공적인 `readPacket` 호출 후 종료되도록
        // socket.isConnected 상태를 Mocking하여 테스트 단위를 격리합니다.**
    }

    @Test
    fun receivePacket_shouldPrintChatMessage() {
        // Given: CHAT_MESSAGE 패킷을 시뮬레이션
        val chatMessage = "[User1] Hello"
        val chatPacketBytes = createPacket(PacketType.CHAT_MESSAGE, chatMessage)

        // Mock InputStream에 패킷 데이터를 주입
        val mockInputStream = ByteArrayInputStream(chatPacketBytes)

        // When: receivePacket이 한 번 실행 후 종료되도록 Socket 연결 상태를 Mocking
        whenever(mockSocket.isConnected).thenReturn(true).thenReturn(false) // 1회 실행 후 종료

        receivePacket(mockInputStream, mockSocket, shutdownFlag)

        // Then: 콘솔 출력 검증
        assertEquals("$chatMessage", outputStreamCaptor.toString(StandardCharsets.UTF_8).trim())
    }

    @Test
    fun receivePacket_shouldPrintServerInfo() {
        // Given: SERVER_INFO 패킷을 시뮬레이션
        val infoMessage = "Welcome to the chat!"
        val infoPacketBytes = createPacket(PacketType.SERVER_INFO, infoMessage)

        val mockInputStream = ByteArrayInputStream(infoPacketBytes)
        whenever(mockSocket.isConnected).thenReturn(true).thenReturn(false)

        // When: receivePacket 실행
        receivePacket(mockInputStream, mockSocket, shutdownFlag)

        // Then: 콘솔 출력 검증
        assertEquals("Info: $infoMessage", outputStreamCaptor.toString(StandardCharsets.UTF_8).trim())
    }

    @Test
    fun receivePacket_shouldHandleIntentionalShutdown() {
        // Given: ShutdownFlag가 true이고, InputStream에서 IOException 발생을 Mock
        shutdownFlag.isIntentional = true
        val mockInputStream = mock<InputStream>()
        // readPacket에서 IOException이 발생하도록 Mock
        whenever(mockInputStream.read(any(), any(), any())).thenThrow(IOException())

        // When: receivePacket 실행
        receivePacket(mockInputStream, mockSocket, shutdownFlag)

        // Then: "Local shutdown complete." 메시지 출력 검증
        assertEquals("Local shutdown complete.", outputStreamCaptor.toString(StandardCharsets.UTF_8).trim())
    }

    @Test
    fun receivePacket_shouldHandleUnexpectedServerDisconnect() {
        // Given: ShutdownFlag가 false이고, InputStream에서 IOException 발생을 Mock
        shutdownFlag.isIntentional = false
        val mockInputStream = mock<InputStream>()
        // readPacket에서 IOException이 발생하도록 Mock
        whenever(mockInputStream.read(any(), any(), any())).thenThrow(IOException())

        // When: receivePacket 실행
        receivePacket(mockInputStream, mockSocket, shutdownFlag)

        // Then: "Error: Server disconnected." 메시지 출력 검증
        assertEquals("Error: Server disconnected.", outputStreamCaptor.toString(StandardCharsets.UTF_8).trim())
    }
}
