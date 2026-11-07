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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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
fun CameraPlayer(camera: CameraConfig, model: LauncherViewModel, modifier: Modifier = Modifier) {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return
    val status by model.ha.status.collectAsState()
    var dataChannel by remember(camera) { mutableStateOf("") }
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
                        dataChannel =
                            model.ha
                                .command(
                                    "camera/webrtc/get_client_config",
                                    JSONObject().put("entity_id", camera.entityId),
                                )
                                .optJSONObject("result")
                                ?.optString("dataChannel")
                                .orEmpty()
                        "ha-rtc"
                    } else "hls"
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    "hls"
                }
        }
    }
    Box(modifier.background(Color.Black)) {
        when (mode) {
            "endpoint" ->
                WebRtcPlayer(camera.webRtc, model, Modifier.fillMaxSize(), null, "") {
                    mode = "hls"
                }
            "ha-rtc" ->
                WebRtcPlayer("", model, Modifier.fillMaxSize(), camera.entityId, dataChannel) {
                    mode = "hls"
                }
            "hls" -> HlsPlayer(camera, model, Modifier.fillMaxSize())
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
private fun HlsPlayer(camera: CameraConfig, model: LauncherViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var url by remember(camera) { mutableStateOf<String?>(null) }
    var failed by remember(camera) { mutableStateOf(false) }
    var buffering by remember(camera) { mutableStateOf(false) }
    var rendered by remember(camera) { mutableStateOf(false) }
    var error by remember(camera) { mutableStateOf("") }
    var retryIndex by remember(camera) { mutableIntStateOf(0) }
    StartedEffect(camera, retryIndex) {
        failed = false
        rendered = false
        try {
            url = camera.hls.ifBlank { model.ha.stream(camera.entityId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            error = "HLS"
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
                            trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
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
                    error = exception.errorCodeName
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
            delay(if (failed) 20_000 else 30_000)
            failed = true
            url = null
            retryIndex++
        }
    }
    Box(modifier) {
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
            ) +
                if (failed && error.isNotBlank() && image == null)
                    " · " + error.removePrefix("ERROR_CODE_")
                else "",
            Modifier.align(Alignment.Center)
                .background(Color.Black.copy(alpha = .7f))
                .padding(6.dp),
            size = 10,
            color = Muted,
        )
    }
}

private class RtcBridge(val offerHandler: (String) -> Unit, val failure: () -> Unit) {
    @JavascriptInterface fun offer(sdp: String) = offerHandler(sdp)

    @JavascriptInterface fun failed() = failure()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebRtcPlayer(
    endpoint: String,
    model: LauncherViewModel,
    modifier: Modifier,
    entityId: String?,
    dataChannel: String,
    onFallback: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val currentFallback by rememberUpdatedState(onFallback)
    val rtcSession = remember {
        java.util.concurrent.atomic.AtomicReference<
            dev.hatv.launcher.ha.HomeAssistantClient.RtcSession?
        >(
            null
        )
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
                settings.blockNetworkLoads =
                    true // All signaling goes through native OkHttp; page has no remote assets.
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ) = true
                    }
                addJavascriptInterface(
                    RtcBridge(
                        { sdp ->
                            scope.launch {
                                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                                    return@launch
                                try {
                                    if (entityId != null) {
                                        val session = model.ha.cameraWebRtc(entityId, sdp)
                                        rtcSession.set(session)
                                        try {
                                            for (event in session.events) {
                                                when (event.optString("type")) {
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
                                                        currentFallback()
                                                        break
                                                    }
                                                }
                                            }
                                        } finally {
                                            session.close()
                                            rtcSession.compareAndSet(session, null)
                                        }
                                        return@launch
                                    }
                                    val answer =
                                        withContext(Dispatchers.IO) {
                                            val url =
                                                endpoint
                                                    .trim()
                                                    .toHttpUrl()
                                                    .also {
                                                        require(
                                                            it.username.isBlank() &&
                                                                it.password.isBlank() &&
                                                                it.fragment == null
                                                        )
                                                    }
                                                    .toString()
                                            val request =
                                                Request.Builder()
                                                    .url(url)
                                                    .post(
                                                        sdp.toRequestBody(
                                                            "application/sdp".toMediaType()
                                                        )
                                                    )
                                                    .build()
                                            // go2rtc is a distinct endpoint: never send the HA
                                            // access token.
                                            model.ha.http.newCall(request).execute().use { response
                                                ->
                                                check(response.isSuccessful)
                                                val text =
                                                    response.body?.string() ?: error("Empty SDP")
                                                check(text.startsWith("v="))
                                                text
                                            }
                                        }
                                    evaluateJavascript(
                                        "window.answer(${JSONObject.quote(answer)})",
                                        null,
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    currentFallback()
                                }
                            }
                        },
                        { main.post { currentFallback() } },
                    ),
                    "NativeRtc",
                )
            }
        }
    // Covers unsupported WebView WebRTC, ICE failure, incompatible codecs and silent signaling
    // failures.
    StartedEffect(endpoint, entityId) {
        delay(12_000)
        web.evaluateJavascript("window.checkConnection()", null)
    }
    DisposableEffect(web, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    web.onResume()
                    web.loadDataWithBaseURL(
                        "https://localhost/",
                        RTC_HTML.replace("__CHANNEL__", JSONObject.quote(dataChannel)),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
                Lifecycle.Event.ON_STOP -> {
                    scope.coroutineContext.cancelChildren()
                    rtcSession.getAndSet(null)?.close()
                    web.evaluateJavascript("window.stop()", null)
                    web.onPause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            rtcSession.getAndSet(null)?.close()
            web.evaluateJavascript("window.stop()", null)
            web.removeJavascriptInterface("NativeRtc")
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
    }
    AndroidView(factory = { web }, modifier = modifier)
}

private const val RTC_HTML =
    """
<!doctype html><html><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body,video{margin:0;width:100%;height:100%;background:#090f19;overflow:hidden}video{object-fit:cover}</style><video id="v" autoplay muted playsinline></video><script>
let pc;let candidates=[];window.candidate=async(c)=>{try{if(pc&&pc.remoteDescription)await pc.addIceCandidate(c);else candidates.push(c)}catch(e){NativeRtc.failed()}};window.stop=()=>{if(pc)pc.close();v.srcObject=null};window.checkConnection=()=>{if(!pc||pc.connectionState!=='connected')NativeRtc.failed()};window.answer=async(sdp)=>{try{await pc.setRemoteDescription({type:'answer',sdp});for(const c of candidates)await pc.addIceCandidate(c);candidates=[]}catch(e){NativeRtc.failed()}};
(async()=>{try{pc=new RTCPeerConnection({iceServers:[]});const channel=__CHANNEL__;if(channel)pc.createDataChannel(channel);pc.addTransceiver('video',{direction:'recvonly'});pc.ontrack=e=>{v.srcObject=e.streams[0]||new MediaStream([e.track])};pc.onconnectionstatechange=()=>{if(['failed','disconnected'].includes(pc.connectionState))NativeRtc.failed()};await pc.setLocalDescription(await pc.createOffer());await new Promise(resolve=>{if(pc.iceGatheringState==='complete')return resolve();pc.onicegatheringstatechange=()=>{if(pc.iceGatheringState==='complete')resolve()};setTimeout(resolve,2500)});NativeRtc.offer(pc.localDescription.sdp)}catch(e){NativeRtc.failed()}})();
</script></html>
"""
