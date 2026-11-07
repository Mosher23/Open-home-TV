package dev.hatv.launcher.ha

import dev.hatv.launcher.data.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class HomeAssistantClient(
    private val scope: CoroutineScope,
    val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    private val commandTimeoutMs: Long = 15_000,
) {
    enum class Status {
        OFFLINE,
        CONNECTING,
        ONLINE,
        AUTH_ERROR,
    }

    private val mutableStates = MutableStateFlow<Map<String, Entity>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private val mutableStatus = MutableStateFlow(Status.OFFLINE)
    val status = mutableStatus.asStateFlow()
    private val rtcEvents = ConcurrentHashMap<Int, Channel<JSONObject>>()
    private val ids = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    @Volatile private var socket: WebSocket? = null
    private var session: Job? = null
    private var base = ""
    private var accessToken = ""

    fun connect(server: String, token: String) {
        disconnect()
        base = normalizeServer(server)
        accessToken = token
        session =
            scope.launch(Dispatchers.IO) {
                var failures = 0
                while (isActive) {
                    mutableStatus.value = Status.CONNECTING
                    val messages = Channel<JSONObject>(256)
                    val opened = CompletableDeferred<WebSocket>()
                    val ws =
                        http.newWebSocket(
                            Request.Builder()
                                .url(base.replaceFirst("http", "ws") + "/api/websocket")
                                .build(),
                            object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    opened.complete(webSocket)
                                }

                                override fun onMessage(webSocket: WebSocket, text: String) {
                                    val json =
                                        runCatching { JSONObject(text) }.getOrNull() ?: return
                                    if (!messages.trySend(json).isSuccess) {
                                        messages.close(IOException("Event buffer full"))
                                        webSocket.cancel()
                                    }
                                }

                                override fun onFailure(
                                    webSocket: WebSocket,
                                    t: Throwable,
                                    response: Response?,
                                ) {
                                    opened.completeExceptionally(t)
                                    messages.close(t)
                                }

                                override fun onClosed(
                                    webSocket: WebSocket,
                                    code: Int,
                                    reason: String,
                                ) {
                                    messages.close()
                                }

                                override fun onClosing(
                                    webSocket: WebSocket,
                                    code: Int,
                                    reason: String,
                                ) {
                                    webSocket.close(code, null)
                                    messages.close()
                                }
                            },
                        )
                    var authFailed = false
                    try {
                        socket = withTimeout(10_000) { opened.await() }
                        withTimeout(10_000) {
                            check(messages.receive().optString("type") == "auth_required")
                            ws.send(
                                JSONObject()
                                    .put("type", "auth")
                                    .put("access_token", token)
                                    .toString()
                            )
                            val reply = messages.receive()
                            authFailed = reply.optString("type") == "auth_invalid"
                            check(reply.optString("type") == "auth_ok")
                        }
                        val subscriptionId = ids.incrementAndGet()
                        ws.send(
                            JSONObject()
                                .put("id", subscriptionId)
                                .put("type", "subscribe_events")
                                .put("event_type", "state_changed")
                                .toString()
                        )
                        val snapshotId = ids.incrementAndGet()
                        ws.send(
                            JSONObject().put("id", snapshotId).put("type", "get_states").toString()
                        )
                        val bufferedEvents = mutableListOf<JSONObject>()
                        var snapshotLoaded = false
                        val readyTimeout =
                            scope.launch {
                                delay(15_000)
                                if (!snapshotLoaded) ws.cancel()
                            }
                        try {
                            for (j in messages) {
                                when (j.optString("type")) {
                                    "result" -> {
                                        val id = j.optInt("id")
                                        if (id == subscriptionId && !j.optBoolean("success"))
                                            throw IOException("Subscription rejected")
                                        if (id == snapshotId) {
                                            check(j.optBoolean("success")) {
                                                "State snapshot rejected"
                                            }
                                            val array = j.getJSONArray("result")
                                            mutableStates.value =
                                                (0 until array.length())
                                                    .map { Entity.parse(array.getJSONObject(it)) }
                                                    .associateBy { it.id }
                                            bufferedEvents.forEach(::applyEvent)
                                            bufferedEvents.clear()
                                            snapshotLoaded = true
                                            mutableStatus.value = Status.ONLINE
                                            failures = 0
                                        } else pending.remove(id)?.complete(j)
                                    }
                                    "event" ->
                                        if (rtcEvents.containsKey(j.optInt("id"))) {
                                            val channel = rtcEvents[j.optInt("id")]
                                            if (
                                                channel
                                                    ?.trySend(
                                                        j.optJSONObject("event") ?: JSONObject()
                                                    )
                                                    ?.isSuccess == false
                                            )
                                                channel.close(
                                                    IOException("Camera signaling buffer full")
                                                )
                                        } else if (snapshotLoaded) applyEvent(j)
                                        else {
                                            if (bufferedEvents.size >= 256)
                                                throw IOException("Snapshot event buffer full")
                                            bufferedEvents.add(j)
                                        }
                                }
                            }
                        } finally {
                            readyTimeout.cancel()
                        }
                    } catch (_: TimeoutCancellationException) {
                        // Handshake timeout is a recoverable connection failure.
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        /* Never log URLs, tokens or response bodies. */
                    } finally {
                        ws.cancel()
                        messages.cancel()
                        if (socket === ws) {
                            socket = null
                            pending.values.forEach {
                                it.completeExceptionally(IOException("Connection closed"))
                            }
                            pending.clear()
                            rtcEvents.values.forEach { it.close(IOException("Connection closed")) }
                            rtcEvents.clear()
                        }
                    }
                    mutableStatus.value = if (authFailed) Status.AUTH_ERROR else Status.OFFLINE
                    if (authFailed) break
                    val backoff = (1000L shl failures.coerceAtMost(5)).coerceAtMost(30_000)
                    failures++
                    delay(backoff + Random.nextLong(250))
                }
            }
    }

    private fun applyEvent(j: JSONObject) {
        val data = j.optJSONObject("event")?.optJSONObject("data") ?: return
        val id = data.optString("entity_id")
        val entity = data.optJSONObject("new_state")
        mutableStates.value =
            if (entity == null) mutableStates.value - id
            else mutableStates.value + (id to Entity.parse(entity))
    }

    fun disconnect() {
        session?.cancel()
        session = null
        socket?.cancel()
        socket = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        rtcEvents.values.forEach { it.close(IOException("Connection closed")) }
        rtcEvents.clear()
        mutableStatus.value = Status.OFFLINE
    }

    suspend fun command(type: String, args: JSONObject = JSONObject()): JSONObject {
        check(status.value == Status.ONLINE) { "Home Assistant is offline" }
        val id = ids.incrementAndGet()
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        try {
            check(
                socket?.send(
                    JSONObject(args.toString()).put("id", id).put("type", type).toString()
                ) == true
            )
            val result =
                try {
                    withTimeout(commandTimeoutMs) { response.await() }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw IOException("Home Assistant command timed out")
                }
            check(result.optBoolean("success")) {
                result.optJSONObject("error")?.optString("code") ?: "Command failed"
            }
            return result
        } finally {
            pending.remove(id)
        }
    }

    suspend fun test(server: String, token: String) =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(normalizeServer(server) + "/api/")
                    .header("Authorization", "Bearer $token")
                    .build()
            http.newCall(request).execute().use {
                check(it.isSuccessful) { "Home Assistant returned HTTP ${it.code}" }
            }
        }

    suspend fun fetchStates(server: String, token: String): Map<String, Entity> =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(normalizeServer(server) + "/api/states")
                    .header("Authorization", "Bearer $token")
                    .build()
            http.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Home Assistant returned HTTP ${response.code}" }
                val body = response.body ?: error("Missing response")
                require(body.contentLength() <= 8 * 1024 * 1024) { "Entity response is too large" }
                // Bound chunked responses too; never trust Content-Length alone.
                val buffer = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        require(buffer.size() + n <= 8 * 1024 * 1024) {
                            "Entity response is too large"
                        }
                        buffer.write(chunk, 0, n)
                    }
                }
                val array = JSONArray(buffer.toString("UTF-8"))
                (0 until array.length())
                    .map { Entity.parse(array.getJSONObject(it)) }
                    .associateBy { it.id }
            }
        }

    suspend fun service(
        domain: String,
        service: String,
        entityId: String,
        data: JSONObject = JSONObject(),
    ) =
        withContext(Dispatchers.IO) {
            require(domain.matches(Regex("[a-z_]+")) && service.matches(Regex("[a-z_]+")))
            val body =
                JSONObject(data.toString())
                    .put("entity_id", entityId)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
            val request =
                Request.Builder()
                    .url("$base/api/services/$domain/$service")
                    .header("Authorization", "Bearer $accessToken")
                    .post(body)
                    .build()
            http.newCall(request).execute().use {
                check(it.isSuccessful) { "Action returned HTTP ${it.code}" }
            }
        }

    suspend fun forecasts(entityId: String, type: String): List<Forecast> {
        val reply =
            command(
                "call_service",
                JSONObject()
                    .put("domain", "weather")
                    .put("service", "get_forecasts")
                    .put("target", JSONObject().put("entity_id", entityId))
                    .put("service_data", JSONObject().put("type", type))
                    .put("return_response", true),
            )
        val array =
            reply
                .optJSONObject("result")
                ?.optJSONObject("response")
                ?.optJSONObject(entityId)
                ?.optJSONArray("forecast") ?: JSONArray()
        return (0 until array.length()).map {
            array.getJSONObject(it).let { f ->
                Forecast(
                    f.optString("datetime"),
                    f.opt("temperature")?.toString()?.toDoubleOrNull(),
                    f.optString("condition"),
                )
            }
        }
    }

    class RtcSession(val events: Channel<JSONObject>, private val stop: () -> Unit) :
        java.io.Closeable {
        override fun close() = stop()
    }

    suspend fun cameraWebRtc(entityId: String, offer: String): RtcSession {
        check(status.value == Status.ONLINE)
        val ws = socket ?: error("Home Assistant is offline")
        val id = ids.incrementAndGet()
        val response = CompletableDeferred<JSONObject>()
        val channel = Channel<JSONObject>(64)
        pending[id] = response
        rtcEvents[id] = channel
        fun stop() {
            if (rtcEvents.remove(id, channel) && socket === ws) {
                ws.send(
                    JSONObject()
                        .put("id", ids.incrementAndGet())
                        .put("type", "unsubscribe_events")
                        .put("subscription", id)
                        .toString()
                )
            }
            channel.cancel()
        }
        try {
            check(
                ws.send(
                    JSONObject()
                        .put("id", id)
                        .put("type", "camera/webrtc/offer")
                        .put("entity_id", entityId)
                        .put("offer", offer)
                        .toString()
                )
            )
            val result =
                try {
                    withTimeout(commandTimeoutMs) { response.await() }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw IOException("Home Assistant command timed out")
                }
            check(result.optBoolean("success")) { "Camera WebRTC signaling unavailable" }
            return RtcSession(channel, ::stop)
        } catch (e: Exception) {
            stop()
            throw e
        } finally {
            pending.remove(id)
        }
    }

    suspend fun catalog(): HomeCatalog = coroutineScope {
        suspend fun list(type: String): JSONArray =
            try {
                command(type).optJSONArray("result") ?: JSONArray()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                JSONArray()
            }
        val areas = async { list("config/area_registry/list") }
        val devices = async { list("config/device_registry/list") }
        val entities = async { list("config/entity_registry/list") }
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        fun JSONObject.text(key: String) = if (isNull(key)) "" else optString(key)
        HomeCatalog(
            areas.await().objects().associate { a ->
                a.getString("area_id") to
                    AreaInfo(
                        a.getString("area_id"),
                        a.getString("name"),
                        a.text("temperature_entity_id"),
                        a.text("humidity_entity_id"),
                    )
            },
            entities.await().objects().associate { e ->
                e.getString("entity_id") to
                    EntityInfo(
                        e.text("area_id"),
                        e.text("device_id"),
                        e.text("entity_category"),
                        e.text("hidden_by").isNotBlank(),
                        e.text("disabled_by").isNotBlank(),
                    )
            },
            devices.await().objects().associate { d -> d.getString("id") to d.text("area_id") },
        )
    }

    suspend fun calendarEvents(
        entityId: String,
        start: java.time.Instant,
        end: java.time.Instant,
        zone: java.time.ZoneId,
    ): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            require(entityId.matches(Regex("calendar\\.[a-z0-9_]+")))
            val url =
                base
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegments("api/calendars")
                    .addPathSegment(entityId)
                    .addQueryParameter("start", start.toString())
                    .addQueryParameter("end", end.toString())
                    .build()
            http
                .newCall(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $accessToken")
                        .build()
                )
                .execute()
                .use { response ->
                    check(response.isSuccessful) { "Calendar returned HTTP ${response.code}" }
                    val body = response.body ?: error("Empty calendar response")
                    val source = body.source()
                    require(!source.request(2L * 1024 * 1024 + 1)) {
                        "Calendar response is too large"
                    }
                    parseCalendarEvents(entityId, JSONArray(source.readUtf8()), zone).take(500)
                }
        }

    suspend fun cameraSnapshot(entityId: String): ByteArray =
        withContext(Dispatchers.IO) {
            require(entityId.matches(Regex("camera\\.[a-z0-9_]+")))
            val url =
                base
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegments("api/camera_proxy")
                    .addPathSegment(entityId)
                    .addQueryParameter("width", "960")
                    .build()
            http
                .newCall(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $accessToken")
                        .build()
                )
                .execute()
                .use { response ->
                    check(response.isSuccessful) { "Camera image unavailable" }
                    val source = response.body?.source() ?: error("Empty camera image")
                    require(!source.request(4L * 1024 * 1024 + 1)) { "Camera image is too large" }
                    source.readByteArray()
                }
        }

    suspend fun stream(entityId: String): String {
        val reply =
            command("camera/stream", JSONObject().put("entity_id", entityId).put("format", "hls"))
        val path = reply.getJSONObject("result").getString("url")
        return base.toHttpUrl().resolve(path)?.toString() ?: error("Invalid stream URL")
    }
}
