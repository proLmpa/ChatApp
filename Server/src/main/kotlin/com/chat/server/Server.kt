package com.chat.server

import com.chat.share.ConnectionService
import java.net.ServerSocket
import java.util.UUID

fun main() {
    val port = 8080

    val serverSocket = ServerSocket(port)
    println("[Server] Server Socket established on port $port")

    try {
        while (true) {
            val clientSocket = serverSocket.accept()
            val clientId = UUID.randomUUID().toString()
            println("[Server] Client connected from ${clientSocket.inetAddress.hostAddress}. ClientId: $clientId")

            val conn = ConnectionService(clientSocket)
            ClientHandler(conn, clientId).start()
        }
    } catch (e: Exception) {
        println("[Server] loop error: ${e.message}")
    } finally {
        serverSocket.close()
    }
}