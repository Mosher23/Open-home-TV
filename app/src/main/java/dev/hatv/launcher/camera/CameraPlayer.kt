package dev.hatv.launcher.camera

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.data.CameraConfig
import dev.hatv.launcher.ui.Label
import dev.hatv.launcher.ui.Muted
import dev.hatv.launcher.ui.StartedEffect
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Composable
fun CameraPlayer(
    camera: CameraConfig,
    model: LauncherViewModel,
    modifier: Modifier = Modifier,
    onPlaybackChanged: (Boolean) -> Unit = {},
) {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return
    val playbackChanged by rememberUpdatedState(onPlaybackChanged)
    DisposableEffect(camera) { onDispose { playbackChanged(false) } }
    val status by model.ha.status.collectAsState()
    var rtcConfig by remember(camera) { mutableStateOf(JSONObject()) }
    var mode by
        remember(camera, status) {
            mutableStateOf(
                if (camera.webRtc.isNotBlank()) "endpoint"
                else if (camera.hls.isNotBlank()) "hls" else "checking"
            )
        }
    StartedEffect(camera, status) {
        if (mode == "checking") {
            mode =
                try {
                    val capabilities =
                        model.ha.command(
                            "camera/capabilities",
                            JSONObject().put("entity_id", camera.entityId),
                        )
                    val types =
                        capabilities.optJSONObject("result")?.optJSONArray("frontend_stream_types")
                    if (types?.toString()?.contains("web_rtc") == true) {
                        rtcConfig =
                            model.ha
                                .command(
                                    "camera/webrtc/get_client_config",
                                    JSONObject().put("entity_id", camera.entityId),
                                )
                                .optJSONObject("result") ?: JSONObject()
                        "ha-rtc"
                    } else "hls"
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    "hls"
                }
        }
    }
    LaunchedEffect(mode) { playbackChanged(false) }
    Box(modifier.background(Color.Black).testTag("camera-player:${camera.entityId}")) {
        when (mode) {
            "endpoint" ->
                WebRtcPlayer(
                    camera.webRtc,
                    model,
                    Modifier.fillMaxSize(),
                    null,
                    JSONObject(),
                    playbackChanged,
                ) {
                    mode = "hls"
                }
            "ha-rtc" ->
                WebRtcPlayer(
                    "",
                    model,
                    Modifier.fillMaxSize(),
                    camera.entityId,
                    rtcConfig,
                    playbackChanged,
                ) {
                    mode = "hls"
                }
            "hls" -> HlsPlayer(camera, model, Modifier.fillMaxSize(), playbackChanged)
            else ->
                Label(
                    stringResource(R.string.camera_connecting),
                    Modifier.align(Alignment.Center),
                    size = 11,
                    color = Muted,
                )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun HlsPlayer(
    camera: CameraConfig,
    model: LauncherViewModel,
    modifier: Modifier,
    onPlaybackChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var url by remember(camera) { mutableStateOf<String?>(null) }
    var failed by remember(camera) { mutableStateOf(false) }
    var buffering by remember(camera) { mutableStateOf(false) }
    var rendered by remember(camera) { mutableStateOf(false) }
    var error by remember(camera) { mutableStateOf("") }
    var retryIndex by remember(camera) { mutableIntStateOf(0) }
    val notifyPlayback by rememberUpdatedState(onPlaybackChanged)
    LaunchedEffect(rendered, buffering, failed) {
        notifyPlayback(rendered && !buffering && !failed)
    }
    StartedEffect(camera, retryIndex) {
        failed = false
        rendered = false
        try {
            url = camera.hls.ifBlank { model.ha.stream(camera.entityId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            error = "HLS_START"
            android.util.Log.w("CameraPlayback", "HLS stream request failed")
        }
    }
    val stream = url
    val player =
        remember(stream, retryIndex) {
            if (stream == null) null
            else {
                val server = runCatching { model.config.value.server.toHttpUrl() }.getOrNull()
                val token = runCatching { model.store.token() }.getOrDefault("")
                val client =
                    model.ha.http
                        .newBuilder()
                        .addInterceptor { chain ->
                            val target = chain.request().url
                            val authenticated =
                                server != null &&
                                    target.scheme == server.scheme &&
                                    target.host == server.host &&
                                    target.port == server.port
                            chain.proceed(
                                if (authenticated && token.isNotBlank())
                                    chain
                                        .request()
                                        .newBuilder()
                                        .header("Authorization", "Bearer $token")
                                        .build()
                                else chain.request()
                            )
                        }
                        .build()
                ExoPlayer.Builder(
                        context,
                        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
                    )
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(2000, 6000, 500, 1000)
                            .setTargetBufferBytes(4 * 1024 * 1024)
                            .setPrioritizeTimeOverSizeThresholds(false)
                            .build()
                    )
                    .build()
                    .apply {
                        volume = 0f
                        trackSelectionParameters =
                            trackSelectionParameters
                                .buildUpon()
                                .setMaxVideoSize(1920, 1080)
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .build()
                        setMediaSource(
                            HlsMediaSource.Factory(OkHttpDataSource.Factory(client))
                                .createMediaSource(MediaItem.fromUri(stream))
                        )
                        prepare()
                        playWhenReady = true
                    }
            }
        }
    DisposableEffect(player, lifecycle) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(exception: PlaybackException) {
                    failed = true
                    rendered = false
                    error = playbackErrorCode(exception)
                    android.util.Log.w("CameraPlayback", "HLS: $error")
                }

                override fun onRenderedFirstFrame() {
                    rendered = true
                    failed = false
                }

                override fun onPlaybackStateChanged(state: Int) {
                    buffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_ENDED) {
                        failed = true
                        rendered = false
                    }
                }
            }
        player?.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    player?.prepare()
                    player?.playWhenReady = true
                }
                Lifecycle.Event.ON_STOP -> {
                    player?.stop()
                    rendered = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player?.removeListener(listener)
            player?.release()
        }
    }
    StartedEffect(player, rendered, failed, buffering) {
        if (!rendered || failed || buffering) {
            // A silent decoder/network stall must not leave a permanent black card.
            delay(if (failed) 20_000 else 45_000)
            failed = true
            url = null
            retryIndex++
        }
    }
    Box(if (rendered) modifier.testTag("camera-live:${camera.entityId}") else modifier) {
        if (player != null)
            AndroidView(
                factory = {
                    (android.view.LayoutInflater.from(it).inflate(R.layout.camera_player, null)
                            as PlayerView)
                        .apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            isFocusable = false
                            isFocusableInTouchMode = false
                            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            // TextureView respects Compose clipping and overlays on TV firmware.
                        }
                },
                modifier = Modifier.fillMaxSize(),
                update = { it.player = player },
            )
        if (!rendered) SnapshotFallback(camera, model, Modifier.fillMaxSize(), failed, error)
    }
}

@Composable
private fun SnapshotFallback(
    camera: CameraConfig,
    model: LauncherViewModel,
    modifier: Modifier,
    failed: Boolean,
    error: String,
) {
    var image by
        remember(camera) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var lastSuccess by remember(camera) { mutableLongStateOf(0L) }
    StartedEffect(camera) {
        while (isActive) {
            try {
                val bytes = model.ha.cameraSnapshot(camera.entityId)
                val bitmap =
                    withContext(Dispatchers.Default) {
                        val bounds =
                            android.graphics.BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (
                            bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 720
                        ) sample *= 2
                        android.graphics.BitmapFactory.decodeByteArray(
                            bytes,
                            0,
                            bytes.size,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                        )
                    }
                image = bitmap?.asImageBitmap()
                lastSuccess = android.os.SystemClock.elapsedRealtime()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (android.os.SystemClock.elapsedRealtime() - lastSuccess > 15_000) image = null
            }
            delay(5000)
        }
    }
    Box(modifier.background(Color.Black)) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Label(
            stringResource(
                if (image != null) R.string.camera_snapshot
                else if (failed) R.string.camera_retry else R.string.camera_connecting
            ) + if (failed && error.isNotBlank()) " · " + error.removePrefix("ERROR_CODE_") else "",
            Modifier.align(Alignment.Center)
                .background(Color.Black.copy(alpha = .7f))
                .padding(6.dp),
            size = 10,
            color = Muted,
        )
    }
}

/** Safe codes only: never include signed stream URLs, tokens or SDP in UI/logs. */
private fun playbackErrorCode(error: PlaybackException): String {
    var cause: Throwable? = error
    repeat(8) {
        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            return "HTTP_${(cause as androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException).responseCode}"
        }
        cause = cause?.cause
    }
    return error.errorCodeName.removePrefix("ERROR_CODE_")
}

private class RtcBridge(
    val offerHandler: (Int, String) -> Unit,
    val candidateHandler: (Int, String) -> Unit,
    val playingHandler: (Int) -> Unit,
    val failure: (Int, String) -> Unit,
) {
    @JavascriptInterface fun offer(run: Int, sdp: String) = offerHandler(run, sdp)

    @JavascriptInterface fun candidate(run: Int, value: String) = candidateHandler(run, value)

    @JavascriptInterface fun playing(run: Int) = playingHandler(run)

    @JavascriptInterface fun failed(run: Int, code: String) = failure(run, code)
}

/** The page has no network assets; native code owns authenticated signaling. */
internal fun rtcPage(template: String, config: JSONObject, trickle: Boolean, run: Int): String =
    template
        .replace("__CONFIG__", JSONObject.quote(config.toString()))
        .replace("__TRICKLE__", trickle.toString())
        .replace("__RUN__", run.toString())

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebRtcPlayer(
    endpoint: String,
    model: LauncherViewModel,
    modifier: Modifier,
    entityId: String?,
    config: JSONObject,
    onPlaybackChanged: (Boolean) -> Unit,
    onFallback: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val currentFallback by rememberUpdatedState(onFallback)
    val generation = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val localCandidates = remember {
        java.util.concurrent.atomic.AtomicReference<
            kotlinx.coroutines.channels.Channel<JSONObject>?
        >(
            null
        )
    }
    val rtcSession = remember {
        java.util.concurrent.atomic.AtomicReference<
            dev.hatv.launcher.ha.HomeAssistantClient.RtcSession?
        >(
            null
        )
    }
    var rendered by remember { mutableStateOf(false) }
    val notifyPlayback by rememberUpdatedState(onPlaybackChanged)
    LaunchedEffect(rendered) { notifyPlayback(rendered) }
    fun fallback(run: Int, code: String) {
        if (generation.get() == run && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // Page reports fixed diagnostic categories, never external text.
            val safe = code.takeIf { it.matches(Regex("[A-Z_]{1,32}")) } ?: "FAILED"
            android.util.Log.w("CameraPlayback", "WebRTC fallback: $safe")
            currentFallback()
        }
    }
    val web =
        remember(endpoint, entityId) {
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                isFocusable = false
                isFocusableInTouchMode = false
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ) = true

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?,
                        ): Boolean {
                            val run = generation.get()
                            main.post { fallback(run, "RENDERER") }
                            return true // Disposal removes and destroys the affected WebView.
                        }
                    }
                addJavascriptInterface(
                    RtcBridge(
                        { run, sdp ->
                            scope.launch {
                                if (
                                    generation.get() != run ||
                                        !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                                )
                                    return@launch
                                try {
                                    if (entityId != null) {
                                        val candidates = localCandidates.get() ?: return@launch
                                        val session = model.ha.cameraWebRtc(entityId, sdp)
                                        rtcSession.set(session)
                                        val sessionId = CompletableDeferred<String>()
                                        val sender = launch {
                                            try {
                                                val id = sessionId.await()
                                                for (candidate in candidates) session.addCandidate(
                                                    id,
                                                    candidate,
                                                )
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                fallback(run, "LOCAL_ICE")
                                            }
                                        }
                                        try {
                                            for (event in session.events) {
                                                if (generation.get() != run) break
                                                when (event.optString("type")) {
                                                    "session" ->
                                                        sessionId.complete(
                                                            event.getString("session_id")
                                                        )
                                                    "answer" ->
                                                        evaluateJavascript(
                                                            "window.answer(${JSONObject.quote(event.getString("answer"))})",
                                                            null,
                                                        )
                                                    "candidate" ->
                                                        evaluateJavascript(
                                                            "window.candidate(${event.getJSONObject("candidate")})",
                                                            null,
                                                        )
                                                    "error" -> {
                                                        fallback(run, "SIGNALING")
                                                        break
                                                    }
                                                }
                                            }
                                        } finally {
                                            sender.cancel()
                                            session.close()
                                            rtcSession.compareAndSet(session, null)
                                        }
                                        return@launch
                                    }
                                    // A separate go2rtc endpoint never receives the HA token.
                                    val answer =
                                        withContext(Dispatchers.IO) {
                                            val url =
                                                endpoint.trim().toHttpUrl().also {
                                                    require(
                                                        it.username.isBlank() &&
                                                            it.password.isBlank() &&
                                                            it.fragment == null
                                                    )
                                                }
                                            val request =
                                                Request.Builder()
                                                    .url(url)
                                                    .post(
                                                        sdp.toRequestBody(
                                                            "application/sdp".toMediaType()
                                                        )
                                                    )
                                                    .build()
                                            model.ha.http.newCall(request).execute().use { response
                                                ->
                                                check(response.isSuccessful)
                                                val text =
                                                    response.body?.string() ?: error("Empty SDP")
                                                check(text.startsWith("v="))
                                                text
                                            }
                                        }
                                    if (generation.get() == run)
                                        evaluateJavascript(
                                            "window.answer(${JSONObject.quote(answer)})",
                                            null,
                                        )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    fallback(run, "SIGNALING")
                                }
                            }
                        },
                        { run, value ->
                            if (generation.get() == run)
                                runCatching { JSONObject(value) }
                                    .getOrNull()
                                    ?.let {
                                        if (localCandidates.get()?.trySend(it)?.isFailure == true)
                                            main.post { fallback(run, "ICE_QUEUE") }
                                    }
                        },
                        { run -> main.post { if (generation.get() == run) rendered = true } },
                        { run, code -> main.post { fallback(run, code) } },
                    ),
                    "NativeRtc",
                )
            }
        }
    val currentRendered by rememberUpdatedState(rendered)
    StartedEffect(web) {
        // Native guard also covers a page/renderer that never executes its JS watchdog.
        delay(35_000)
        if (!currentRendered) fallback(generation.get(), "NO_VIDEO")
    }
    DisposableEffect(web, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val run = generation.incrementAndGet()
                    rendered = false
                    localCandidates.getAndSet(kotlinx.coroutines.channels.Channel(64))?.cancel()
                    web.onResume()
                    val template =
                        context.assets.open("camera/rtc.html").bufferedReader().use {
                            it.readText()
                        }
                    web.loadDataWithBaseURL(
                        "https://localhost/",
                        rtcPage(template, config, entityId != null, run),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
                Lifecycle.Event.ON_STOP -> {
                    generation.incrementAndGet()
                    scope.coroutineContext.cancelChildren()
                    localCandidates.getAndSet(null)?.cancel()
                    rtcSession.getAndSet(null)?.close()
                    web.evaluateJavascript("window.stop()", null)
                    web.onPause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            generation.incrementAndGet()
            lifecycle.removeObserver(observer)
            localCandidates.getAndSet(null)?.cancel()
            rtcSession.getAndSet(null)?.close()
            web.evaluateJavascript("window.stop()", null)
            web.removeJavascriptInterface("NativeRtc")
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
    }
    Box(if (rendered) modifier.testTag("camera-live:$entityId") else modifier) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        if (!rendered)
            Label(
                stringResource(R.string.camera_connecting),
                Modifier.align(Alignment.Center),
                size = 11,
                color = Muted,
            )
    }
}
