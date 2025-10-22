package com.chat.client

import com.chat.share.PacketType
import com.chat.share.createPacket
import com.chat.share.readPacket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

fun main() {
    val server = "localhost"
    val port = 8080

    print("Enter your name: ")
    val name = readlnOrNull()

    if (name.isNullOrBlank()) {
        println("Name is required. Program terminated.")
        return
    }

    val socket = try {
        val s = Socket(server, port)
        println("Connected to server at $server:$port")
        s
    } catch (e: IOException) {
        println("Error: Couldn't connect to server: ${e.message}")
    } as Socket?

    println("'$name' entered. (type 'exit' to escape.)")

    if (socket != null) {
        try {
            val outputStream = socket.getOutputStream()
            val inputStream = socket.getInputStream()

            // 1. 클라이언트 이름 등록
            sendPacket(outputStream, PacketType.REGISTER_NAME, name)

            // 2. 서버 메시지 수신 전용 스레드
            val receiveThread = thread(isDaemon = true) {
                receivePacket(inputStream, socket)
            }

            // 3. 메시지 송신 루프 (메인 스레드)
            sendMessageLoop(outputStream)

            receiveThread.join()
        } catch (e: Exception) {
            println("Error: ${e.message}")
        } finally {
            if (!socket.isClosed) socket.close()
            println("Chat disconnected.")
        }
    }
}

private fun sendPacket(outputStream: OutputStream, type: Int, data: String) {
    val packetBytes = createPacket(type, data)

    try {
        outputStream.write(packetBytes)
        outputStream.flush()
    } catch (_: IOException) {
        println("Error: Message could not be sent.")
    }

}

private fun receivePacket(inputStream: InputStream, socket: Socket) {
    try {
        while (socket.isConnected && !socket.isInputShutdown) {
            val packet = readPacket(inputStream)
            val message = packet.getBodyAsString()

            when (packet.type) {
                PacketType.CHAT_MESSAGE -> println(message)
                PacketType.SERVER_INFO -> println("Info: $message")
                PacketType.DISCONNECT_INFO -> println("Disconnect: $message")
                else -> println("Unknown: $message")
            }
        }
    } catch (_: IOException) {
        println("Error: Server disconnected.")
    } catch (e: Exception) {
        println("Received thread - Error: ${e.message}")
    }
}

private fun sendMessageLoop(outputStream: OutputStream) {
    while (true) {
        val input = readlnOrNull() ?: continue

        if (input.equals("exit", ignoreCase = true)) {
            sendPacket(outputStream, PacketType.DISCONNECT_REQUEST, "")
            break
        }

        if (input.isNotBlank()) {
            sendPacket(outputStream, PacketType.CHAT_MESSAGE, input)
        }
    }
}
