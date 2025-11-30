package com.chat.client

import com.chat.share.ChatMessageDTO
import com.chat.share.ConnectionService
import com.chat.share.Packet
import com.chat.share.PacketType
import com.chat.share.Protocol.createPacket
import com.chat.share.RegisterNameDTO
import com.chat.share.ServerInfoDTO
import com.chat.share.UpdateNameDTO
import com.chat.share.WhisperDTO
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
    internal val shutdownFlag = ShutdownFlag(false)
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
     * @param dto Server, Client 요청에 따라 정해진 JSON format
     */
    internal inline fun <reified T> sendPacket(type: PacketType, dto: T) {
        val bytes = createPacket(type, dto)

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
        when (packet.type) {
            PacketType.SERVER_INFO -> {
                val dto = packet.toDTO<ServerInfoDTO>()
                println("Info: ${dto.message}")
            }
            PacketType.SERVER_SUCCESS -> {
                val dto = packet.toDTO<ServerInfoDTO>()
                println("Success: ${dto.message}")
                println("You can now chat. (type '/n <name>' to rename, '/w <user> <msg>' to whisper, and 'exit' to quit)")

                clientState.isRegistered = true
            }
            PacketType.INITIAL_NAME_CHANGE_FAILED -> {
                val dto = packet.toDTO<ServerInfoDTO>()
                println("Warning: ${dto.message}")
                println("Please enter another name")

//                clientState.isRegistered = false
            }
            PacketType.UPDATE_NAME_FAILED -> {
                val dto = packet.toDTO<ServerInfoDTO>()
                println("Warning: ${dto.message}")
                println("Try another name with /n <new_name>")
            }
            PacketType.USER_NOT_EXISTS -> {
                val dto = packet.toDTO<ServerInfoDTO>()
                println("Info: ${dto.message}")
            }
            PacketType.CHAT_MESSAGE -> {
                val dto = packet.toDTO<ChatMessageDTO>()
                println(dto.message)
            }
            PacketType.WHISPER -> {
                val dto = packet.toDTO<WhisperDTO>()
                println(dto.message)
            }
            PacketType.DISCONNECT_INFO -> {
                val dto = packet.toDTO<ServerInfoDTO>()
                println("Disconnect: ${dto.message}")
            }
            else -> {}
        }
    }

    fun sendMessageLoop() {
        while (true) {
            val input = readlnOrNull()?.trim() ?: continue

            if (input.equals("exit", ignoreCase = true)) {
                sendPacket(PacketType.DISCONNECT_REQUEST, ServerInfoDTO(""))
                shutdownFlag.isIntentional = true
                break
            }

            if (!clientState.isRegistered) {
                val name = input.trim()
                if (name.isEmpty()) {
                    println("Name cannot be empty.")
                    continue
                }
                if (name.contains(" ")) {
                    println("Name cannot contain spaces.")
                    continue
                }

                sendPacket(PacketType.REGISTER_NAME, RegisterNameDTO(name))
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

                sendPacket(PacketType.UPDATE_NAME, UpdateNameDTO(name))
                continue
            }

            if (input.startsWith("/w ")) {
                val args = input.removePrefix("/w ").trim()
                val parts = args.split(" ", limit=2)

                if (parts.size < 2) {
                    println("Usage: /w <user_name> <chat>")
                    continue
                }

                sendPacket(
                    PacketType.WHISPER,
                    WhisperDTO(parts[0], parts[1])
                )
                continue
            }

            if (input.isNotBlank()) {
                sendPacket(PacketType.CHAT_MESSAGE, ChatMessageDTO(input))
            }
        }
    }
}