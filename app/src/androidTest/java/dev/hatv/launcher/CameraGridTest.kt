package dev.hatv.launcher

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hatv.launcher.data.CameraConfig
import dev.hatv.launcher.data.Config
import java.net.ServerSocket
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CameraGridTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        ui.waitForIdle()
    }

    @Test
    fun gridAutoplaysVisibleFeedsAndRemoteRestoresFocusAfterFullscreen() {
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
                        val name =
                            input.readLine()?.split(' ')?.getOrNull(1)?.substringAfterLast('/')
                        while (!input.readLine().isNullOrEmpty()) {}
                        val body =
                            if (name == "playlist.m3u8")
                            // The tiny live fixture expires before four cold software decoders
                            // start. A repeated VOD playlist keeps real frames available while
                            // the test navigates, without a synthetic "playing" callback.
                            ("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:1\n" +
                                        "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
                                        (1..60).joinToString("") {
                                            "#EXT-X-DISCONTINUITY\n#EXTINF:0.500,\nplaylist0.m4s\n" +
                                                "#EXTINF:0.550,\nplaylist1.m4s\n"
                                        } +
                                        "#EXT-X-ENDLIST\n")
                                    .toByteArray()
                            else if (
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
        val cameras =
            (1..6).map {
                CameraConfig(
                    "camera.grid$it",
                    hls = "http://127.0.0.1:${socket.localPort}/playlist.m3u8",
                )
            }
        lateinit var model: LauncherViewModel
        lateinit var original: Config
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[LauncherViewModel::class.java]
            original = model.config.value
            model.update(
                original.copy(
                    demo = false,
                    cameras = cameras,
                    homeCameraCount = 0,
                    livePreviews = false,
                )
            )
            it.startActivity(
                Intent(it, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        fun tile(index: Int) = ui.onNodeWithTag("grid-camera:camera.grid$index")
        fun awaitLive(vararg indices: Int) {
            ui.waitUntil(30000) {
                indices.all {
                    ui.onAllNodesWithTag("camera-live:camera.grid$it", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .size == 1
                }
            }
        }
        try {
            ui.waitForIdle()
            repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitLive(1, 2, 3, 4)
            val bounds = (1..4).map { tile(it).assertIsDisplayed().getUnclippedBoundsInRoot() }
            assertEquals(bounds[0].top.value, bounds[1].top.value, 1f)
            assertEquals(bounds[2].top.value, bounds[3].top.value, 1f)
            assertEquals(bounds[0].left.value, bounds[2].left.value, 1f)
            assertTrue(bounds[1].left > bounds[0].right)
            assertTrue(bounds[2].top > bounds[0].bottom)
            ui.onNodeWithTag("camera-player:camera.grid5", useUnmergedTree = true)
                .assertDoesNotExist()
            ui.onNodeWithTag("camera-player:camera.grid6", useUnmergedTree = true)
                .assertDoesNotExist()

            // The TV remote moves across columns, down rows, then into fullscreen.
            tile(1).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            ui.waitForIdle()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            tile(2).assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            tile(4).assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.onNodeWithTag("camera-grid").assertDoesNotExist()
            awaitLive(4)
            listOf(1, 2, 3, 5, 6).forEach {
                ui.onNodeWithTag("camera-player:camera.grid$it", useUnmergedTree = true)
                    .assertDoesNotExist()
            }
            ui.onNodeWithText("Back").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            tile(4).assertIsFocused()
            awaitLive(1, 2, 3, 4)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            tile(6).assertIsFocused()
            awaitLive(5, 6)
            ui.onNodeWithTag("camera-player:camera.grid1", useUnmergedTree = true)
                .assertDoesNotExist()
            ui.onNodeWithTag("camera-player:camera.grid2", useUnmergedTree = true)
                .assertDoesNotExist()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitLive(6)
            key(KeyEvent.KEYCODE_BACK)
            tile(6).assertIsFocused()
            awaitLive(5, 6)
            key(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithTag("camera-grid").assertDoesNotExist()
            cameras.forEach {
                ui.onNodeWithTag("camera-player:${it.entityId}", useUnmergedTree = true)
                    .assertDoesNotExist()
            }
        } finally {
            ui.activityRule.scenario.onActivity { model.update(original) }
            socket.close()
            workers.shutdownNow()
        }
    }
}
