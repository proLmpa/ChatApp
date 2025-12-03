package com.chat.server

import com.chat.share.ConnectionService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.ServerSocket
import java.util.UUID

private val logger = KotlinLogging.logger {}

fun main() {
    val port = 8080
    logger.info { "Starting server on $port..." }

    val serverSocket = ServerSocket(port)
    logger.info { "Server socket established on port $port." }
    println("[Server] Server Socket established on port $port")

    try {
        while (true) {
            val clientSocket = serverSocket.accept()
            val clientId = UUID.randomUUID().toString()
            logger.info { "Client connected from ${clientSocket.inetAddress.hostAddress}. ClientId: $clientId" }

            val conn = ConnectionService(clientSocket)
            ClientHandler(conn, clientId).start()
        }
    } catch (e: Exception) {
        logger.error(e) { "Server crashed on startup: ${e.message}" }
    } finally {
        serverSocket.close()
    }
}