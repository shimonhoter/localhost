package com.shimonhoter.localhost

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal static-file HTTP server bound to 127.0.0.1 only (never reachable
 * from outside the device). Serves every file under [rootDir] so that an
 * .html page and its relative css/js/image assets all resolve correctly.
 *
 * Call [start] to bind an ephemeral free port, and [stop] to close the
 * socket and release the port immediately (e.g. when the page/activity
 * closes).
 */
class LocalHttpServer(private val rootDir: File) {

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    val port: Int
        get() = serverSocket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        // port 0 => OS assigns a free ephemeral port; loopback only, never exposed on the network
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        running = true
        pool.execute { acceptLoop() }
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (running) {
            try {
                val client = socket.accept()
                pool.execute { handleClient(client) }
            } catch (e: IOException) {
                if (running) {
                    // socket was likely closed by stop(); loop exits naturally
                }
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 10_000
                val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                // Drain remaining request headers
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(sock, 400, "text/plain", "Bad Request".toByteArray())
                    return
                }
                var path = parts[1].substringBefore("?")
                path = java.net.URLDecoder.decode(path, "UTF-8")
                if (path == "/") path = "/index.html"

                val requested = File(rootDir, path).canonicalFile
                val rootCanonical = rootDir.canonicalFile

                if (!requested.path.startsWith(rootCanonical.path) || !requested.exists() || requested.isDirectory) {
                    writeResponse(sock, 404, "text/plain", "Not Found".toByteArray())
                    return
                }

                val bytes = requested.readBytes()
                writeResponse(sock, 200, mimeTypeFor(requested.name), bytes)
            } catch (e: IOException) {
                // client disconnected mid-request; nothing to do
            }
        }
    }

    private fun writeResponse(sock: Socket, status: Int, contentType: String, body: ByteArray) {
        val statusText = if (status == 200) "OK" else if (status == 404) "Not Found" else "Bad Request"
        val out = BufferedOutputStream(sock.getOutputStream())
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun mimeTypeFor(name: String): String = when {
        name.endsWith(".html") || name.endsWith(".htm") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }

    /** Closes the listening socket and releases the port immediately. */
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // already closed
        }
        pool.shutdownNow()
    }
}
