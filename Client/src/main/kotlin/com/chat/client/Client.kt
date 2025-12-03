package com.chat.client

import com.chat.share.ConnectionService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.Socket

private val logger = KotlinLogging.logger {}

fun main() {
    val server = "localhost"
    val port = 8080

    val socket = try {
        val s = Socket(server, port)
        logger.info { "Connected to server at $server:$port" }
        s
    } catch (e: IOException) {
        logger.error(e) { "Error: Couldn't connect to server: ${e.message}" }
    } as Socket?

    if (socket != null) {
        val conn = ConnectionService(socket)
        val session = ClientSession(conn)

        session.start()
    } else {
        logger.info { "Client crashed on startup: Couldn't start server at $server:$port"}
        return
    }
}