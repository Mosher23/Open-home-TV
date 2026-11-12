package dev.hatv.launcher

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hatv.launcher.camera.CameraPlayer
import dev.hatv.launcher.camera.rtcPage
import dev.hatv.launcher.data.CameraConfig
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Decode real video; an SDP reply or a refreshed JPEG does not pass these tests. */
class CameraPlaybackTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun twoHlsFeedsDecodeAndReopenAfterLeavingComposition() {
        val live = java.util.concurrent.atomic.AtomicIntegerArray(2)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val socket = ServerSocket(0)
        val workers = Executors.newCachedThreadPool()
        workers.submit {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers.submit {
                    client.use {
                        it.soTimeout = 5000
                        val input = it.getInputStream().bufferedReader()
                        val path = input.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?')
                        while (!input.readLine().isNullOrEmpty()) {}
                        val name = path?.substringAfterLast('/')
                        val body =
                            if (
                                name in
                                    listOf(
                                        "playlist.m3u8",
                                        "init.mp4",
                                        "playlist0.m4s",
                                        "playlist1.m4s",
                                    )
                            )
                                assets.open("camera/$name").use { stream -> stream.readBytes() }
                            else byteArrayOf()
                        val type =
                            if (name?.endsWith("m3u8") == true) "application/vnd.apple.mpegurl"
                            else "video/mp4"
                        it.getOutputStream().apply {
                            write(
                                "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                            write(body)
                            flush()
                        }
                    }
                }
            }
        }
        var visible by mutableStateOf(true)
        lateinit var model: LauncherViewModel
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[LauncherViewModel::class.java]
            it.setContent {
                if (visible)
                    Row(Modifier.fillMaxSize()) {
                        repeat(2) { index ->
                            CameraPlayer(
                                CameraConfig(
                                    "camera.fixture$index",
                                    hls = "http://127.0.0.1:${socket.localPort}/playlist.m3u8",
                                ),
                                model,
                                Modifier.weight(1f).fillMaxHeight(),
                                onPlaybackChanged = { active ->
                                    live.set(index, if (active) 1 else 0)
                                },
                            )
                        }
                    }
            }
        }
        fun awaitFrames() {
            ui.waitUntil(25000) {
                (0..1).all {
                    live.get(it) == 1 &&
                        ui.onAllNodesWithTag("camera-live:camera.fixture$it")
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                }
            }
        }
        try {
            awaitFrames()
            ui.runOnIdle { visible = false }
            ui.onNodeWithTag("camera-live:camera.fixture0").assertDoesNotExist()
            assertEquals(0, live.get(0))
            assertEquals(0, live.get(1))
            ui.runOnIdle { visible = true }
            awaitFrames()
        } finally {
            ui.runOnIdle { visible = false }
            socket.close()
            workers.shutdownNow()
        }
    }

    class LoopbackBridge(
        private val post: (String) -> Unit,
        val frames: CountDownLatch,
        val failure: AtomicReference<String>,
    ) {
        @JavascriptInterface
        fun offer(run: Int, sdp: String) = post("window.fixtureAnswer(${JSONObject.quote(sdp)})")

        @JavascriptInterface
        fun candidate(run: Int, candidate: String) = post("window.fixtureCandidate($candidate)")

        @JavascriptInterface
        fun playing(run: Int) {
            frames.countDown()
        }

        @JavascriptInterface
        fun failed(run: Int, code: String) {
            failure.set(code)
            frames.countDown()
        }
    }

    @Test
    fun productionWebRtcPageRejectsOnlyDroppedFrames() {
        val finished = CountDownLatch(1)
        val failure = AtomicReference("")
        lateinit var web: WebView
        ui.activityRule.scenario.onActivity { activity ->
            val template =
                activity.assets.open("camera/rtc.html").bufferedReader().use { it.readText() }
            web =
                WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.blockNetworkLoads = true
                    addJavascriptInterface(LoopbackBridge({}, finished, failure), "NativeRtc")
                }
            activity.setContent {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { web },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Reproduce a WebView NullVideoDecoder: metadata exists and total frames rise,
            // but every frame is dropped before decoding. This must not trigger playing().
            val droppedFrames =
                """
                <script>
                Object.defineProperties(video, {
                  readyState:{get:()=>4},videoWidth:{get:()=>640},videoHeight:{get:()=>360}
                });
                let drops=0;
                video.getVideoPlaybackQuality=()=>({totalVideoFrames:++drops,droppedVideoFrames:drops});
                setTimeout(()=>{const realNow=Date.now;Date.now=()=>realNow()+30000},1500);
                </script>
            """
                    .trimIndent()
            web.loadDataWithBaseURL(
                "https://localhost/",
                rtcPage(template, JSONObject(), true, 1) + droppedFrames,
                "text/html",
                "UTF-8",
                null,
            )
        }
        try {
            assertTrue("Watchdog did not request fallback", finished.await(10, TimeUnit.SECONDS))
            assertEquals(
                "Dropped frames were incorrectly reported as playing",
                "NO_VIDEO",
                failure.get(),
            )
        } finally {
            ui.activityRule.scenario.onActivity {
                web.evaluateJavascript("window.stop()", null)
                web.removeJavascriptInterface("NativeRtc")
                web.destroy()
            }
        }
    }

    @Test
    fun productionWebRtcPageDecodesVideoWithTrickleIce() {
        val frames = CountDownLatch(1)
        val failure = AtomicReference("")
        lateinit var web: WebView
        ui.activityRule.scenario.onActivity { activity ->
            val template =
                activity.assets.open("camera/rtc.html").bufferedReader().use { it.readText() }
            web =
                WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.blockNetworkLoads = true
                    addJavascriptInterface(
                        LoopbackBridge(
                            { js -> activity.runOnUiThread { evaluateJavascript(js, null) } },
                            frames,
                            failure,
                        ),
                        "NativeRtc",
                    )
                }
            activity.setContent {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { web },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Local synthetic animated camera. The receive path is the unchanged production page.
            val fixture =
                """
                <script>
                let remote, queued=[];
                window.fixtureCandidate=async c=>{if(remote?.remoteDescription)await remote.addIceCandidate(c);else queued.push(c)};
                window.fixtureAnswer=async sdp=>{try{
                  remote=new RTCPeerConnection({iceServers:[]});
                  // Exercise providers that omit sdpMid and sdpMLineIndex.
                  remote.onicecandidate=e=>{if(e.candidate)window.candidate({candidate:e.candidate.candidate})};
                  const canvas=document.createElement('canvas');canvas.width=320;canvas.height=180;
                  const ctx=canvas.getContext('2d');let n=0;
                  window.fixtureTimer=setInterval(()=>{ctx.fillStyle=(++n%2)?'#18bcee':'#ff8833';ctx.fillRect(0,0,320,180)},100);
                  const stream=canvas.captureStream(10);remote.addTrack(stream.getVideoTracks()[0],stream);
                  await remote.setRemoteDescription({type:'offer',sdp});
                  for(const c of queued)await remote.addIceCandidate(c);queued=[];
                  await remote.setLocalDescription(await remote.createAnswer());
                  window.answer(remote.localDescription.sdp);
                }catch(e){NativeRtc.failed(1,'FIXTURE')}};
                </script>
            """
                    .trimIndent()
            web.loadDataWithBaseURL(
                "https://localhost/",
                fixture + rtcPage(template, JSONObject(), true, 1),
                "text/html",
                "UTF-8",
                null,
            )
        }
        try {
            assertTrue(
                "Timed out waiting for decoded WebRTC frames",
                frames.await(30, TimeUnit.SECONDS),
            )
            assertEquals("WebRTC playback failed", "", failure.get())
            // Freeze a previously working stream. Advance only the watchdog's clock after
            // decoded frames stop, rather than spending 25 seconds in the test.
            ui.activityRule.scenario.onActivity {
                web.evaluateJavascript(
                    """
                    remote.close(); clearInterval(window.fixtureTimer);
                    let previous=-1, stable=0;
                    window.freezeTimer=setInterval(()=>{
                      const count=video.getVideoPlaybackQuality().totalVideoFrames;
                      stable=count===previous?stable+1:0; previous=count;
                      if(stable>=3){clearInterval(window.freezeTimer);const realNow=Date.now;Date.now=()=>realNow()+30000;}
                    },500);
                    """
                        .trimIndent(),
                    null,
                )
            }
            ui.waitUntil(10000) { failure.get().isNotEmpty() }
            assertEquals("VIDEO_STALLED", failure.get())
        } finally {
            ui.activityRule.scenario.onActivity {
                web.evaluateJavascript(
                    "window.stop(); if(remote)remote.close(); clearInterval(window.fixtureTimer); clearInterval(window.freezeTimer)",
                    null,
                )
                web.removeJavascriptInterface("NativeRtc")
                web.destroy()
            }
        }
    }
}
