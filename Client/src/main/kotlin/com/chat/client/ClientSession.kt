package com.chat.client

import com.chat.share.ChatMessageDTO
import com.chat.share.ConnectionService
import com.chat.share.DisconnectDTO
import com.chat.share.FileChunk
import com.chat.share.FileSendCompleteDTO
import com.chat.share.FileSendRequestDTO
import com.chat.share.FrameType
import com.chat.share.NameRegisteredDTO
import com.chat.share.NameUpdatedDTO
import com.chat.share.Packet
import com.chat.share.PacketType
import com.chat.share.Protocol.createPacket
import com.chat.share.Protocol.decodePacket
import com.chat.share.RegisterNameDTO
import com.chat.share.ServerInfoDTO
import com.chat.share.UpdateNameDTO
import com.chat.share.WhisperDTO
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class ShutdownFlag (var isIntentional: Boolean = false)
data class ClientState (@Volatile var isRegistered: Boolean = false)

data class IncomingFileContext(
    val originalFileName: String,
    val totalSize: Long,
    var receivedSize: Long,
    val out: BufferedOutputStream
)

class ClientSession(
    private val conn: ConnectionService,
    private val inputProvider: () -> String? = { readlnOrNull() } // 기본은 콘솔 입력
) {
    private val logger = KotlinLogging.logger {}

    private val shutdownFlag = ShutdownFlag(false)
    private val clientState = ClientState(false)

    private val incomingFile = ConcurrentHashMap<String, IncomingFileContext>()

    fun start() {
        logger.info { "Client session started. Launching receiver thread.."}

        val receiveThread = thread(isDaemon = true, name = "client-receiver-thread") {
            receiveLoop()
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
        val packet = createPacket(type, dto)
        logger.debug { "Sending packet type=$type body=$dto"}

        try {
            conn.writePacket(packet)
        } catch (e: IOException) {
            println("Error: Failed to send packet: ${e.message}")
            logger.error(e) { "Failed to send packet type=$type" }
        }
    }

    private fun receiveLoop() {
        try {
            while (conn.isConnected() && !shutdownFlag.isIntentional) {
                val (frameType, payload) = conn.readFrame()

                when (frameType) {
                    FrameType.JSON_PACKET -> {
                        val packet = decodePacket(payload)
                        logger.debug { "Received JSON packet: type=${packet.type}, length=${packet.length}" }
                        handleJsonPacket(packet)
                    }

                    FrameType.FILE_CHUNK -> {
                        handleFileChunkFrame(payload)
                    }

                    else -> {
                        logger.warn { "Received unsupported frame type=$frameType." }
                    }
                }
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

    private fun handleJsonPacket(packet: Packet) {
        when (packet.type) {
            PacketType.CONNECT_SUCCESS -> {
                println("Please register your name.")
            }
            PacketType.REGISTER_NAME_SUCCESS -> {
                val dto = packet.toDTO<NameRegisteredDTO>()
                println("Welcome, ${dto.name}")
                println("You can now chat. (type '/n <name>' to rename, '/w <user> <msg>' to whisper, and 'exit' to quit)")
                clientState.isRegistered = true
            }
            PacketType.USER_ENTERED -> {
                val dto = packet.toDTO<NameRegisteredDTO>()
                println("Info: ${dto.name} entered.")
            }
            PacketType.UPDATE_NAME_SUCCESS -> {
                val dto = packet.toDTO<NameUpdatedDTO>()
                println("Info: '${dto.oldName}' updated to '${dto.newName}'.")
            }
            PacketType.NAME_CANNOT_BE_BLANK -> {
                println("Warn: Name cannot be blank.")
            }
            PacketType.NAME_CANNOT_BE_DUPLICATED -> {
                println("Warn: This name already exists. Name cannot be duplicated.")
            }
            PacketType.USER_NOT_EXISTS -> {
                println("Info: Target user doesn't exist.")
            }
            PacketType.SERVER_INFO -> {
                val dto = packet.toDTO<ServerInfoDTO>()
                println("Info: ${dto.message}")
            }
            PacketType.CHAT_MESSAGE -> {
                val dto = packet.toDTO<ChatMessageDTO>()
                println("[${dto.sender}] ${dto.message}")
            }
            PacketType.WHISPER_TO_SENDER -> {
                val dto = packet.toDTO<WhisperDTO>()
                println("[You -> ${dto.target}] ${dto.message}")
            }
            PacketType.WHISPER_TO_TARGET -> {
                val dto = packet.toDTO<WhisperDTO>()
                println("[${dto.sender} -> You] ${dto.message}")
            }
            PacketType.FILE_SEND_REQUEST -> {
                val dto = packet.toDTO<FileSendRequestDTO>()
                handleIncomingFileRequest(dto)
            }
            PacketType.FILE_SEND_COMPLETE -> {
                val dto = packet.toDTO<FileSendCompleteDTO>()
                val ctx = incomingFile[dto.transferId] ?: return
                ctx.out.flush()
                ctx.out.close()
                logger.info { "File receive completed by COMPLETE: transferId=${dto.transferId}" }
            }
            PacketType.DISCONNECT_INFO -> {
                val dto = packet.toDTO<DisconnectDTO>()
                println("Info: User '${dto.target}' disconnected. (Send: ${dto.sent}, Received: ${dto.received})")
            }
            else -> logger.warn { "Unknown packet type: ${packet.type}" }
        }
    }

    private fun handleFileChunkFrame(payload: ByteArray) {
        val chunk = conn.readFileChunk(payload)

        val ctx = incomingFile[chunk.transferId] ?: run {
            logger.warn { "FILE_CHUNK for unknown transferId=${chunk.transferId} ignored."}
            return
        }

        ctx.out.write(chunk.data, 0, chunk.length)
        ctx.receivedSize += chunk.length

        if (ctx.receivedSize >= ctx.totalSize) {
            ctx.out.flush()
            ctx.out.close()
            incomingFile.remove(chunk.transferId)

            println("File received successfully: ${ctx.originalFileName}")
            logger.info { "File receive completed: ${ctx.originalFileName}" }
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
            if (handleFileTransfer(input)) continue
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
        sendPacket(PacketType.WHISPER, WhisperDTO("", target, message))
        return true
    }

    internal fun handleChat(input: String): Boolean {
        if (input.isBlank()) return false

        logger.debug { "Sending CHAT MESSAGE: $input" }
        sendPacket(PacketType.CHAT_MESSAGE, ChatMessageDTO("", input))
        return true
    }

    internal fun handleFileTransfer(input: String): Boolean {
        if(!input.startsWith("/f ")) return false

        val parts = input.split(" ", limit = 3)

        if (parts.size < 3) {
            println("Usage: /f <user_name> <file_name_with_path>")
            return true
        }

        val target = parts[1]
        val filePath = parts[2]

        sendFile(target, filePath)
        return true
    }

    private fun sendFile(target: String, filePath: String) {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            println("File not found: $filePath")
            return
        }

        val transferId = UUID.randomUUID().toString()
        val fileName = file.name
        val fileSize = file.length()

        logger.info { "Sending FILE to=$target, filename=$fileName, size=$fileSize" }

        // 1. Send FILE_SEND_REQUEST (metadata)
        sendPacket(PacketType.FILE_SEND_REQUEST, FileSendRequestDTO(target, transferId, fileName, fileSize))

        // 2. Stream file chunks
        val buffer = ByteArray(64 * 1024)
        var seq = 0

        try {
            BufferedInputStream(file.inputStream()).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break

                    val data = buffer.copyOf(read)
                    conn.writeFileChunk(FileChunk(transferId, seq++, read, data))
                }
            }

            // 3. Notify Completion
            sendPacket(PacketType.FILE_SEND_COMPLETE, FileSendCompleteDTO(transferId))

            println("File send finished: $fileName")
            logger.info { "File send finished: $fileName" }
        } catch (e: IOException) {
            logger.error(e) { "Failed to send file: $fileName" }
        }
    }

    private fun handleIncomingFileRequest(dto: FileSendRequestDTO) {
        val fileName = dto.fileName
        val fileSize = dto.fileSize
        val transferId = dto.transferId

        println("Incoming file: $fileName ($fileSize bytes)")

        val downloadDir = File("./downloads").apply { if (!exists()) mkdirs() }

        val originalName = File(fileName).nameWithoutExtension
        val extension = File(fileName).extension

        val savedFileName = if (extension.isNotEmpty()) {
            "${originalName}__${transferId}.${extension}"
        } else {
            "${originalName}__${transferId}"
        }

        val savedAs = File(downloadDir, savedFileName)
        val out = BufferedOutputStream(FileOutputStream(savedAs))

        incomingFile[transferId] = IncomingFileContext(fileName, fileSize, 0L, out)

        logger.info {
            "Prepare receiving file '$fileName' transferId=$transferId > ${savedAs.absolutePath}"
        }
    }
}