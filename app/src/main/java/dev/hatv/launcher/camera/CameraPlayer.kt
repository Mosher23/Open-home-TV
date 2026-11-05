package dev.hatv.launcher.camera

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.data.CameraConfig
import dev.hatv.launcher.ui.Label
import dev.hatv.launcher.ui.Muted
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
    var fallback by remember(camera) { mutableStateOf(camera.webRtc.isBlank()) }
    if (!fallback) WebRtcPlayer(camera.webRtc, model, modifier) { fallback = true }
    else HlsPlayer(camera, model, modifier)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun HlsPlayer(camera: CameraConfig, model: LauncherViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var url by remember(camera) { mutableStateOf<String?>(null) }
    var failed by remember(camera) { mutableStateOf(false) }
    var retryIndex by remember(camera) { mutableIntStateOf(0) }
    LaunchedEffect(camera, retryIndex) {
        while (isActive) {
            failed = false
            try {
                url = camera.hls.ifBlank { model.ha.stream(camera.entityId) }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                delay(15_000)
            }
        }
    }
    val stream = url
    if (stream == null) {
        Label(
            if (failed) "Stream unavailable · retrying" else "Connecting…",
            modifier,
            size = 11,
            color = Muted,
        )
        return
    }
    val player =
        remember(stream, retryIndex) {
            // A separate client limits buffers; bearer authentication is restricted to the HA
            // origin.
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
            ExoPlayer.Builder(context)
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
                    setMediaSource(
                        HlsMediaSource.Factory(OkHttpDataSource.Factory(client))
                            .createMediaSource(MediaItem.fromUri(stream))
                    )
                    prepare()
                    playWhenReady = true
                }
        }
    LaunchedEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    failed = true
                }
            }
        player.addListener(listener)
        try {
            while (isActive) {
                delay(15_000)
                if (failed) {
                    url = null
                    retryIndex++
                    failed = false
                }
            }
        } finally {
            player.removeListener(listener)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        modifier = modifier,
        update = { it.player = player },
    )
    if (failed) Label("Stream unavailable · retrying", size = 11, color = Muted)
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
    onFallback: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val currentFallback by rememberUpdatedState(onFallback)
    val web =
        remember(endpoint) {
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
                                try {
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
                loadDataWithBaseURL("https://localhost/", RTC_HTML, "text/html", "UTF-8", null)
            }
        }
    // Covers unsupported WebView WebRTC, ICE failure, incompatible codecs and silent signaling
    // failures.
    LaunchedEffect(endpoint) {
        delay(12_000)
        web.evaluateJavascript("window.checkConnection()", null)
    }
    DisposableEffect(web) {
        onDispose {
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
let pc;window.stop=()=>{if(pc)pc.close();v.srcObject=null};window.checkConnection=()=>{if(!pc||pc.connectionState!=='connected')NativeRtc.failed()};window.answer=async(sdp)=>{try{await pc.setRemoteDescription({type:'answer',sdp})}catch(e){NativeRtc.failed()}};
(async()=>{try{pc=new RTCPeerConnection({iceServers:[]});pc.addTransceiver('video',{direction:'recvonly'});pc.ontrack=e=>{v.srcObject=e.streams[0]||new MediaStream([e.track])};pc.onconnectionstatechange=()=>{if(['failed','disconnected'].includes(pc.connectionState))NativeRtc.failed()};await pc.setLocalDescription(await pc.createOffer());await new Promise(resolve=>{if(pc.iceGatheringState==='complete')return resolve();pc.onicegatheringstatechange=()=>{if(pc.iceGatheringState==='complete')resolve()};setTimeout(resolve,2500)});NativeRtc.offer(pc.localDescription.sdp)}catch(e){NativeRtc.failed()}})();
</script></html>
"""
