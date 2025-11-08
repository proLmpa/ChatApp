package com.chat.client

import com.chat.share.PacketType
import com.chat.share.Protocol.createPacket
import com.chat.share.Protocol.readPacket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 클라이언트의 의도적 종료(graceful shutdown) 상태를 스레드 간에 공유하기 위한 플래그.
 * 메인 스레드(송신 루프)가 'exit'을 입력했을 때, 수신 스레드가 이를 정상 종료로 인식하게 돕습니다.
 */
data class ShutdownFlag (var isIntentional: Boolean = false)
data class ClientState (@Volatile var isRegistered: Boolean = false)

fun main() {
    val server = "localhost"
    val port = 8080

    val socket = try {
        val s = Socket(server, port)
        println("Connected to server at $server:$port")
        s
    } catch (e: IOException) {
        println("Error: Couldn't connect to server: ${e.message}")
    } as Socket?

    if (socket != null) {

        try {
            val shutdownFlag = ShutdownFlag(false)
            val clientState = ClientState(false)

            val outputStream = socket.getOutputStream()
            val inputStream = socket.getInputStream()

            // 서버 메시지 수신 전용 스레드
            val receiveThread = thread(isDaemon = true) {
                receivePacket(inputStream, socket, shutdownFlag, clientState)
            }

            // 메시지 송신 루프 (메인 스레드)
            sendMessageLoop(outputStream, shutdownFlag, clientState)

            receiveThread.join()
        } catch (e: Exception) {
            println("Error: ${e.message}")
        } finally {
            if (!socket.isClosed) socket.close()
            println("Chat disconnected.")
        }
    }
}

/**
 * 패킷을 생성하고 서버로 전송하는 함수.
 * @param outputStream 서버 소켓의 출력 스트림
 * @param type 패킷 유형 (예: CHAT_MESSAGE, REGISTER_NAME)
 * @param data 패킷 바디에 포함할 문자열 데이터
 */
internal fun sendPacket(
    outputStream: OutputStream,
    type: PacketType,
    data: String
) {
    val packetBytes = createPacket(type, data)

    try {
        outputStream.write(packetBytes)
        outputStream.flush()
    } catch (e: IOException) {
        println("Error: Failed to send packet: ${e.message}")
    }

}

/**
 * 서버로부터 메시지를 지속적으로 수신하는 스레드 로직.
 * @param inputStream 서버 소켓의 입력 스트림
 * @param socket 연결 소켓 객체 (상태 확인용)
 * @param shutdownFlag 의도적 종료 상태 플래그
 */
internal fun receivePacket(
    inputStream: InputStream,
    socket: Socket,
    shutdownFlag: ShutdownFlag,
    clientState: ClientState
) {
    try {
        while (socket.isConnected && !socket.isInputShutdown) {
            val packet = readPacket(inputStream)
            val message = packet.getBodyAsString()

            when (packet.type) {
                PacketType.SERVER_INFO -> println("Info: $message")
                PacketType.INITIAL_NAME_CHANGE_FAILED -> {
                    println("Warning: $message")
                    clientState.isRegistered = false
                    println("Please enter another name")
                }
                PacketType.SERVER_SUCCESS -> {
                    println("Success: $message")
                    clientState.isRegistered = true
                    println("You can now chat. (type '/n <name>' to rename, 'exit' to quit)")
                }
                PacketType.UPDATE_NAME_FAILED -> {
                    println("Warning: $message")
                    println("Try another name with /n <new_name>")
                }
                PacketType.CHAT_MESSAGE -> println(message)
                PacketType.USER_NOT_EXISTS -> println("Info: $message")
                PacketType.WHISPER -> println(message)
                PacketType.DISCONNECT_INFO -> println("Disconnect: $message")
                else -> {}
            }
        }
    } catch (_: IOException) {
        if (shutdownFlag.isIntentional) {
            println("Local shutdown complete.")
        } else {
            println("Error: Server disconnected.")
        }
    } catch (e: Exception) {
        println("Received thread - Error: ${e.message}")
    }
}

/**
 * 사용자 입력을 받아 서버로 메시지를 보내는 메인 스레드의 루프.
 * @param outputStream 서버 소켓의 출력 스트림
 * @param shutdownFlag 의도적 종료 상태 플래그
 */
internal fun sendMessageLoop(
    outputStream: OutputStream,
    shutdownFlag: ShutdownFlag,
    clientState: ClientState
) {
    while (true) {
        val input = readlnOrNull()?.trim() ?: continue

        if (input.equals("exit", ignoreCase = true)) {
            sendPacket(outputStream, PacketType.DISCONNECT_REQUEST, "")
            shutdownFlag.isIntentional = true
            break
        }

        if (!clientState.isRegistered) {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                println("Name cannot be empty.")
                continue
            }
            if (trimmed.contains(" ")) {
                println("Name cannot contain spaces.")
                continue
            }

            sendPacket(outputStream, PacketType.REGISTER_NAME, trimmed)
            continue
        }

        if (input.startsWith("/n ")) {
            val name = input.removePrefix("/n ").trim()
            if (name.isEmpty()) {
                println("Usage: /n <new_name>")
                continue
            }
            if (name.contains(" ")) {
                println("Name cannot contain spaces.")
                continue
            }

            sendPacket(outputStream, PacketType.UPDATE_NAME, name)
            continue
        }

        if (input.startsWith("/w ")) {
            val args = input.removePrefix("/w ").trim()
            val parts = args.split(" ", limit=2)

            if (parts.size < 2) {
                println("Usage: /w <user_name> <chat>")
                continue
            }

            val target = parts[0].trim()
            val message = parts[1].trim()

            if (target.isEmpty() || message.isEmpty()) {
                println("Usage: /w <user_name> <chat>")
                continue
            }

            val payload = "$target $message"
            sendPacket(outputStream, PacketType.WHISPER, payload)
            continue
        }

        if (input.isNotBlank()) {
            sendPacket(outputStream, PacketType.CHAT_MESSAGE, input)
        }
    }
}
