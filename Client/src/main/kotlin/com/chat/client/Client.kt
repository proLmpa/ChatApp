package com.chat.client

import com.chat.share.ConnectionService
import java.io.IOException
import java.net.Socket

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
        val conn = ConnectionService(socket)
        val session = ClientSession(conn)

        session.start()
    } else {
        println("Error: Couldn't start server at $server:$port")
        return
    }
}