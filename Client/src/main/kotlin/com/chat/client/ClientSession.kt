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
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import kotlin.concurrent.thread

/**
 * 클라이언트의 의도적 종료(graceful shutdown) 상태를 스레드 간에 공유하기 위한 플래그.
 * 메인 스레드(송신 루프)가 'exit'을 입력했을 때, 수신 스레드가 이를 정상 종료로 인식하게 돕습니다.
 */
data class ShutdownFlag (var isIntentional: Boolean = false)
data class ClientState (@Volatile var isRegistered: Boolean = false)

class ClientSession(
    private val conn: ConnectionService,
    private val inputProvider: () -> String? = { readlnOrNull() } // 기본은 콘솔 입력
) {
    private val logger = KotlinLogging.logger {}

    internal val shutdownFlag = ShutdownFlag(false)
    internal val clientState = ClientState(false)

    fun start() {
        logger.info { "Client session started. Launching receiver thread.."}

        val receiveThread = thread(isDaemon = true, name = "client-receiver-thread") {
            receivePacket()
        }

        sendMessageLoop()

        receiveThread.join()
        logger.info { "Client session finished" }
    }

    /**
     * 패킷을 생성하고 서버로 전송하는 함수.
     * @param type 패킷 유형 (예: CHAT_MESSAGE, REGISTER_NAME)
     * @param dto Server, Client 요청에 따라 정해진 JSON format
     */
    internal inline fun <reified T> sendPacket(type: PacketType, dto: T) {
        val bytes = createPacket(type, dto)
        logger.debug { "Sending packet type=$type body=$dto"}

        try {
            conn.writePacket(bytes)
        } catch (e: IOException) {
            println("Error: Failed to send packet: ${e.message}")
            logger.error(e) { "Failed to send packet type=$type" }
        }
    }

    private fun receivePacket() {
        try {
            while (conn.isConnected() && !shutdownFlag.isIntentional) {
                val packet = conn.readPacket()
                logger.debug { "Received packet: type=${packet.type}, length=${packet.length}" }
                handlePacket(packet)
            }
        } catch (_: IOException) {
            if (shutdownFlag.isIntentional) {
                println("Local shutdown complete.")
                logger.info { "Client closed connection intentionally." }
            } else {
                println("Error: Server disconnected.")
                logger.warn { "Server disconnected unexpectedly." }
            }
        } catch (e: Exception) {
            logger.error { "Received thread - Error: ${e.message}" }
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
            else -> logger.warn { "Unknown packet type: ${packet.type}" }
        }
    }

    internal fun sendMessageLoop() {
        while (true) {
            val raw = inputProvider() ?: continue
            val input = raw.trim()

            if (handleExit(input)) break
            if (handleInitialRegister(input)) continue
            if (handleNameChange(input)) continue
            if (handleWhisper(input)) continue
            if (handleChat(input)) continue
        }
    }

    private fun handleExit(input: String): Boolean {
        if (!input.equals("exit", ignoreCase = true)) return false

        logger.info { "User entered exit command. Sending DISCONNECT_REQUEST." }
        sendPacket(PacketType.DISCONNECT_REQUEST, ServerInfoDTO(""))
        shutdownFlag.isIntentional = true
        return true
    }

    private fun handleInitialRegister(input: String): Boolean {
        if (clientState.isRegistered) return false

        val name = input.trim()
        if (name.isEmpty()) {
            println("Name cannot be empty.")
            return true
        }

        if (name.contains(" ")) {
            println("Name cannot contain spaces.")
            return true
        }

        logger.info { "Sending REGISTER_NAME: $name" }
        sendPacket(PacketType.REGISTER_NAME, RegisterNameDTO(name))
        return true
    }

    private fun handleNameChange(input: String): Boolean {
        if (!input.startsWith("/n ")) return false

        val name = input.removePrefix("/n ").trim()

        if (name.isEmpty()) {
            println("Usage: /n <new_name>")
            return true
        }

        if (name.contains(" ")) {
            println("Name cannot contain spaces.")
            return true
        }

        logger.info { "Sending UPDATE_NAME: $name" }
        sendPacket(PacketType.UPDATE_NAME, UpdateNameDTO(name))
        return true
    }

    private fun handleWhisper(input: String): Boolean {
        if (!input.startsWith("/w ")) return false

        val args = input.removePrefix("/w ").trim()
        val parts = args.split(" ", limit = 2)

        if (parts.size < 2) {
            println("Usage: /w <user_name> <chat>")
            return true
        }

        val target = parts[0].trim()
        val message = parts[1].trim()

        if (target.isEmpty() || message.isEmpty()) {
            println("Usage: /w <user_name> <chat>")
            return true
        }

        logger.info { "Sending WHISPER to=$target msg=$message" }
        sendPacket(PacketType.WHISPER, WhisperDTO(target, message))
        return true
    }

    internal fun handleChat(input: String): Boolean {
        if (input.isBlank()) return false

        logger.debug { "Sending CHAT MESSAGE: $input" }
        sendPacket(PacketType.CHAT_MESSAGE, ChatMessageDTO(input))
        return true
    }
}