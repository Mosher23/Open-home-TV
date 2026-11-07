package dev.hatv.launcher.phone

import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalSetupServerTest {
    @Test
    fun servesAssetsButRequiresEncryptedRequestsWithMatchingOriginAndClosesListener() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val server =
            LocalSetupServer(
                scope,
                InetAddress.getByName("127.0.0.1"),
                { "test-page".toByteArray() },
                {
                    calls.incrementAndGet()
                    JSONObject().put("ok", true).put("op", it.getString("op"))
                },
            )
        val url = server.url.substringBefore('#')
        val key = Base64.getUrlDecoder().decode(server.url.substringAfter('#'))
        val http = OkHttpClient()
        fun send(body: String, origin: String = url.trimEnd('/')) =
            http
                .newCall(
                    Request.Builder()
                        .url(url + "api")
                        .header("Origin", origin)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                )
                .execute()
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals("test-page", it.body!!.string())
                assertTrue(it.header("Cache-Control")!!.contains("no-store"))
            }
            send("{} ").use { assertEquals(400, it.code) }
            val envelope = PhonePeer.request(key, JSONObject().put("op", "pair"))
            send(envelope.toString(), "https://attacker.example").use { assertEquals(400, it.code) }
            send(envelope.toString()).use {
                assertEquals(200, it.code)
                assertEquals(
                    "pair",
                    PhonePeer.reply(key, JSONObject(it.body!!.string()), envelope.getString("iv"))
                        .getString("op"),
                )
            }
            send(envelope.toString()).use { assertEquals(400, it.code) }
            assertEquals(1, calls.get())
            server.close()
            // A blocked accept may release its native descriptor just after close() returns.
            // Wait for that bounded OS handoff rather than racing the listener thread on CI.
            runBlocking {
                withTimeout(2000) {
                    while (
                        runCatching { Socket("127.0.0.1", server.port).close() }.isSuccess
                    ) delay(10)
                }
            }
        } finally {
            server.close()
            scope.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun rejectsDnsRebindingChunkedAndOversizedRequestsWithoutInvokingHandler() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val server =
            LocalSetupServer(
                scope,
                InetAddress.getByName("127.0.0.1"),
                { byteArrayOf() },
                {
                    calls.incrementAndGet()
                    JSONObject()
                },
            )
        fun raw(headers: String): String =
            Socket("127.0.0.1", server.port).use { s ->
                s.soTimeout = 2000
                s.getOutputStream().write(headers.toByteArray())
                s.getOutputStream().flush()
                s.getInputStream().bufferedReader().readLine()
            }
        try {
            assertTrue(raw("GET / HTTP/1.1\r\nHost: attacker.example\r\n\r\n").contains("400"))
            assertTrue(
                raw(
                        "POST /api HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nTransfer-Encoding: chunked\r\n\r\n"
                    )
                    .contains("400")
            )
            assertTrue(
                raw(
                        "POST /api HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nContent-Type: application/json\r\nContent-Length: 99999999\r\n\r\n"
                    )
                    .contains("400")
            )
            assertEquals(0, calls.get())
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
