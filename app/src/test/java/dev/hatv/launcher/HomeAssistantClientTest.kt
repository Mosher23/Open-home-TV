package dev.hatv.launcher

import dev.hatv.launcher.ha.HomeAssistantClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeAssistantClientTest {
    @Test
    fun authenticatesSubscribesAppliesBufferedEventsAndCommands() = runBlocking {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var subscriptionId = 0
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            ws.send("""{"type":"auth_required"}""")
                        }

                        override fun onMessage(ws: WebSocket, text: String) {
                            val j = JSONObject(text)
                            val id = j.optInt("id")
                            when (j.getString("type")) {
                                "auth" -> {
                                    assertEquals("local-test-token", j.getString("access_token"))
                                    ws.send("""{"type":"auth_ok"}""")
                                }
                                "subscribe_events" -> {
                                    subscriptionId = id
                                    ws.send(
                                        """{"id":$id,"type":"result","success":true,"result":null}"""
                                    )
                                }
                                "get_states" -> {
                                    ws.send(
                                        """{"id":$subscriptionId,"type":"event","event":{"data":{"entity_id":"light.desk","new_state":{"entity_id":"light.desk","state":"on","attributes":{}}}}}"""
                                    )
                                    ws.send(
                                        """{"id":$id,"type":"result","success":true,"result":[{"entity_id":"light.desk","state":"off","attributes":{}}]}"""
                                    )
                                }
                                "camera/webrtc/offer" -> {
                                    ws.send(
                                        """{"id":$id,"type":"result","success":true,"result":null}"""
                                    )
                                    ws.send(
                                        """{"id":$id,"type":"event","event":{"type":"session","session_id":"test-session"}}"""
                                    )
                                    ws.send(
                                        """{"id":$id,"type":"event","event":{"type":"answer","answer":"test-sdp"}}"""
                                    )
                                }
                                "unsubscribe_events" ->
                                    ws.send(
                                        """{"id":$id,"type":"result","success":true,"result":null}"""
                                    )
                                "camera/stream" ->
                                    ws.send(
                                        """{"id":$id,"type":"result","success":true,"result":{"url":"/api/hls/test/master_playlist.m3u8"}}"""
                                    )
                            }
                        }
                    }
                )
        )
        server.start()
        val client = HomeAssistantClient(scope, commandTimeoutMs = 1000)
        try {
            client.connect(server.url("/").toString(), "local-test-token")
            withTimeout(5000) { client.status.first { it == HomeAssistantClient.Status.ONLINE } }
            assertEquals("on", client.states.value["light.desk"]?.state)
            val timeout = runCatching { client.command("no_reply") }.exceptionOrNull()
            assertTrue(timeout is java.io.IOException)
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(HomeAssistantClient.Status.ONLINE, client.status.value)
            assertEquals(
                server.url("/api/hls/test/master_playlist.m3u8").toString(),
                client.stream("camera.test"),
            )
            server.enqueue(
                MockResponse()
                    .setBody(
                        """[{"summary":"Meeting","start":{"dateTime":"2026-11-07T12:00:00+01:00"},"end":{"dateTime":"2026-11-07T13:00:00+01:00"}}]"""
                    )
            )
            val events =
                client.calendarEvents(
                    "calendar.home",
                    java.time.Instant.parse("2026-11-07T00:00:00Z"),
                    java.time.Instant.parse("2026-11-14T00:00:00Z"),
                    java.time.ZoneId.of("Europe/Berlin"),
                )
            assertEquals("Meeting", events.single().title)
            server.takeRequest(2, TimeUnit.SECONDS) // WebSocket upgrade
            val calendarRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/calendars/calendar.home", calendarRequest.requestUrl!!.encodedPath)
            assertEquals("Bearer local-test-token", calendarRequest.getHeader("Authorization"))
            assertEquals("2026-11-14T00:00:00Z", calendarRequest.requestUrl!!.queryParameter("end"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
            assertArrayEquals(byteArrayOf(1, 2, 3), client.cameraSnapshot("camera.test"))
            val snapshotRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/camera_proxy/camera.test", snapshotRequest.requestUrl!!.encodedPath)
            assertEquals("Bearer local-test-token", snapshotRequest.getHeader("Authorization"))
            val rtc = client.cameraWebRtc("camera.test", "offer-sdp")
            withTimeout(2000) {
                assertEquals("session", rtc.events.receive().getString("type"))
                assertEquals("test-sdp", rtc.events.receive().getString("answer"))
            }
            rtc.close()
            assertTrue(rtc.events.receiveCatching().isClosed)
            val active = client.cameraWebRtc("camera.test", "offer-sdp")
            client.disconnect()
            // Disconnect closes active signaling sessions as well as command waiters.
            withTimeout(2000) { while (!active.events.receiveCatching().isClosed) {} }
            assertEquals(HomeAssistantClient.Status.OFFLINE, client.status.value)
        } finally {
            client.disconnect()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun invalidAuthenticationStopsRetrying() = runBlocking {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            ws.send("""{"type":"auth_required"}""")
                        }

                        override fun onMessage(ws: WebSocket, text: String) {
                            ws.send("""{"type":"auth_invalid","message":"Invalid token"}""")
                        }
                    }
                )
        )
        server.start()
        val client = HomeAssistantClient(scope)
        try {
            client.connect(server.url("/").toString(), "invalid")
            withTimeout(5000) {
                client.status.first { it == HomeAssistantClient.Status.AUTH_ERROR }
            }
            delay(1300)
            assertEquals(1, server.requestCount)
        } finally {
            client.disconnect()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun reconnectsAndReloadsSnapshotAfterServerDisconnect() = runBlocking {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun socketResponse(state: String) =
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            ws.send("""{"type":"auth_required"}""")
                        }

                        override fun onMessage(ws: WebSocket, text: String) {
                            val j = JSONObject(text)
                            val id = j.optInt("id")
                            when (j.getString("type")) {
                                "auth" -> ws.send("""{"type":"auth_ok"}""")
                                "subscribe_events" ->
                                    ws.send(
                                        """{"id":$id,"type":"result","success":true,"result":null}"""
                                    )
                                "get_states" -> {
                                    ws.send(
                                        """{"id":$id,"type":"result","success":true,"result":[{"entity_id":"light.desk","state":"$state","attributes":{}}]}"""
                                    )
                                    if (state == "off") ws.close(1001, "restarting")
                                }
                            }
                        }
                    }
                )
        server.enqueue(socketResponse("off"))
        server.enqueue(socketResponse("on"))
        server.start()
        val client = HomeAssistantClient(scope)
        try {
            client.connect(server.url("/").toString(), "local-test-token")
            withTimeout(7000) { client.states.first { it["light.desk"]?.state == "on" } }
            assertEquals(2, server.requestCount)
            client.disconnect()
            delay(1400)
            assertEquals(2, server.requestCount)
        } finally {
            client.disconnect()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun restConnectionTestSendsBearerAndSurfacesHttpErrors() = runBlocking {
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = HomeAssistantClient(scope)
        try {
            server.enqueue(MockResponse().setBody("{}"))
            client.test(server.url("/").toString(), "local-test-token")
            assertEquals(
                "Bearer local-test-token",
                server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"),
            )
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { client.test(server.url("/").toString(), "invalid") }.isFailure)
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }
}
