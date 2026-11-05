package dev.hatv.launcher.phone

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/**
 * Deliberately small HTTP/1.1 subset: one request/socket, bounded headers/body/workers, no uploads.
 */
class LocalSetupServer(
    parent: CoroutineScope,
    address: InetAddress,
    private val assets: (String) -> ByteArray,
    private val handler: suspend (JSONObject) -> JSONObject,
    private val afterApply: () -> Unit = {},
) : Closeable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val listener =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, 0), 4)
        }
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val queue = Channel<Socket>(2)
    private val closed = AtomicBoolean(false)
    private val crypto = PairingCrypto()
    private val expectedHost = "${address.hostAddress}:${listener.localPort}"
    val port
        get() = listener.localPort

    val url
        get() = "http://$expectedHost/#${crypto.fragment}"

    init {
        repeat(2) {
            scope.launch {
                for (socket in queue) {
                    try {
                        socket.use { serve(it) }
                    } catch (_: Exception) {
                        /* Never log request contents. */
                    } finally {
                        clients.remove(socket)
                    }
                }
            }
        }
        scope.launch {
            try {
                while (isActive) {
                    val socket = listener.accept().apply { soTimeout = 6000 }
                    clients.add(socket)
                    if (!queue.trySend(socket).isSuccess) {
                        clients.remove(socket)
                        socket.close()
                    }
                }
            } catch (_: SocketException) {
                /* Expected on close. */
            }
        }
    }

    private suspend fun serve(socket: Socket) {
        val input = socket.getInputStream()
        var headerBytes = 0
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                require(b != -1 && ++headerBytes <= 8192) { "Invalid header" }
                if (b == 10) {
                    val value = bytes.toByteArray()
                    require(value.isNotEmpty() && value.last() == 13.toByte())
                    return String(value, 0, value.size - 1, Charsets.US_ASCII)
                }
                require(b in 13..126 && (b == 13 || b >= 32))
                bytes.write(b)
            }
        }
        try {
            val request = line().split(' ')
            require(request.size == 3 && request[2] == "HTTP/1.1")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val raw = line()
                if (raw.isEmpty()) break
                val i = raw.indexOf(':')
                require(i > 0)
                val name = raw.substring(0, i).lowercase()
                require(name.matches(Regex("[a-z0-9-]+")) && name !in headers)
                headers[name] = raw.substring(i + 1).trim()
            }
            // Host allowlisting prevents DNS rebinding. Loopback aliases only support local ADB
            // forwarding.
            val host = headers["host"] ?: error("Host required")
            val localAlias =
                socket.inetAddress.isLoopbackAddress &&
                    host in setOf("127.0.0.1:$port", "localhost:$port")
            require(host == expectedHost || localAlias)
            require("transfer-encoding" !in headers)
            val origin = headers["origin"]
            require(origin == null || origin == "http://$host")
            when {
                request[0] == "GET" && request[1] in listOf("/", "/setup.js") -> {
                    require((headers["content-length"]?.toIntOrNull() ?: 0) == 0)
                    val path = if (request[1] == "/") "index.html" else "setup.js"
                    respond(
                        socket,
                        200,
                        if (path.endsWith("html")) "text/html; charset=utf-8"
                        else "text/javascript; charset=utf-8",
                        assets(path),
                    )
                }
                request[0] == "POST" && request[1] == "/api" -> {
                    require(headers["content-type"]?.substringBefore(';') == "application/json")
                    val count = headers["content-length"]?.toIntOrNull() ?: error("Length required")
                    require(count in 1..180000)
                    val body = ByteArray(count)
                    var read = 0
                    while (read < count) {
                        val n = input.read(body, read, count - read)
                        require(n > 0)
                        read += n
                    }
                    val (nonce, message) = crypto.open(JSONObject(String(body, Charsets.UTF_8)))
                    val reply =
                        try {
                            withTimeout(25_000) { handler(message) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalArgumentException) {
                            JSONObject()
                                .put("ok", false)
                                .put("error", e.message ?: "Invalid selection")
                        } catch (_: Exception) {
                            JSONObject()
                                .put("ok", false)
                                .put(
                                    "error",
                                    "Request failed. Check the server URL, token and TV connection.",
                                )
                        }
                    respond(
                        socket,
                        200,
                        "application/json",
                        crypto.seal(reply, nonce).toString().toByteArray(),
                    )
                    if (message.optString("op") == "apply" && reply.optBoolean("ok")) afterApply()
                }
                else -> respond(socket, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            runCatching {
                respond(socket, 400, "text/plain", "Invalid or expired setup request".toByteArray())
            }
        }
    }

    private fun respond(socket: Socket, code: Int, type: String, body: ByteArray) {
        val headers =
            "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store, max-age=0\r\nPragma: no-cache\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nContent-Security-Policy: default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener.close()
        queue.close()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        job.cancel()
        crypto.destroy()
    }
}
