package com.chat.client

import com.chat.share.ConnectionService
import com.chat.share.Packet
import com.chat.share.PacketType
import com.chat.share.Protocol.createPacket
import java.io.IOException
import kotlin.concurrent.thread

/**
 * 클라이언트의 의도적 종료(graceful shutdown) 상태를 스레드 간에 공유하기 위한 플래그.
 * 메인 스레드(송신 루프)가 'exit'을 입력했을 때, 수신 스레드가 이를 정상 종료로 인식하게 돕습니다.
 */
data class ShutdownFlag (var isIntentional: Boolean = false)
data class ClientState (@Volatile var isRegistered: Boolean = false)

class ClientSession(
    private val conn: ConnectionService
) {
    private val shutdownFlag = ShutdownFlag(false)
    private val clientState = ClientState(false)

    fun start() {
        val receiveThread = thread(isDaemon = true) {
            receivePacket()
        }

        sendMessageLoop()

        receiveThread.join()
    }

    /**
     * 패킷을 생성하고 서버로 전송하는 함수.
     * @param type 패킷 유형 (예: CHAT_MESSAGE, REGISTER_NAME)
     * @param data 패킷 바디에 포함할 문자열 데이터
     */
    fun sendPacket(
        type: PacketType,
        data: String
    ) {
        val bytes = createPacket(type, data)

        try {
            conn.writePacket(bytes)
        } catch (e: IOException) {
            println("Error: Failed to send packet: ${e.message}")
        }
    }

    fun receivePacket() {
        try {
            while (conn.isConnected() && !shutdownFlag.isIntentional) {
                    val packet = conn.readPacket()
                    handlePacket(packet)
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

    private fun handlePacket(packet: Packet) {
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

    fun sendMessageLoop() {
        while (true) {
            val input = readlnOrNull()?.trim() ?: continue


            if (input.equals("exit", ignoreCase = true)) {
                sendPacket(PacketType.DISCONNECT_REQUEST, "")
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

                sendPacket(PacketType.REGISTER_NAME, trimmed)
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

                sendPacket(PacketType.UPDATE_NAME, name)
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
                sendPacket(PacketType.WHISPER, payload)
                continue
            }

            if (input.isNotBlank()) {
                sendPacket(PacketType.CHAT_MESSAGE, input)
            }
        }
    }
}