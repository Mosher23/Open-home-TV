package dev.hatv.launcher

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class RemoteNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        ui.waitForIdle()
    }

    private fun home() {
        ui.activityRule.scenario.onActivity {
            it.startActivity(
                Intent(it, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        ui.waitForIdle()
    }

    @Test
    fun dpadReachesSettingsAndAllConnectionActions() {
        home()
        repeat(7) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.onNodeWithText("Home Assistant server URL").assertIsDisplayed()
        ui.onAllNodesWithText("Save").onFirst().assertIsDisplayed()
        ui.onNodeWithText("Forget saved token").assertIsDisplayed()
        key(KeyEvent.KEYCODE_BACK)
        ui.onNodeWithText("Home power").assertIsDisplayed()
    }

    @Test
    fun remoteOpensAndClosesCameraFullscreen() {
        home()
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.onNodeWithText("Demo camera · no live stream").assertIsDisplayed()
        key(KeyEvent.KEYCODE_BACK)
        home()
        ui.onNodeWithText("Home power").assertIsDisplayed()
    }

    @Test
    fun remoteCanToggleDemoLightAndReturnHome() {
        home()
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        // State of the first light changes from on to off; no HA credentials or services involved.
        ui.onAllNodesWithText("off").assertCountEquals(2)
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        ui.onNodeWithText("YouTube").assertIsDisplayed()
    }

    @Test
    fun remoteReachesRoomClimateAndCalendar() {
        home()
        repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.onNodeWithText("CO₂").assertIsDisplayed()
        ui.onNodeWithText("PM2.5").assertIsDisplayed()
        ui.onNodeWithText("Living Room · Shutters").assertIsDisplayed()
        key(KeyEvent.KEYCODE_BACK)
        repeat(6) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.onNodeWithText("Family dinner").assertIsDisplayed()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_BACK)
        ui.onNodeWithText("Home power").assertIsDisplayed()
    }

    @Test
    fun phoneSetupShowsQrAndBackClosesTheListener() {
        home()
        repeat(7) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.onNodeWithText("Set up with your phone").performSemanticsAction(
            SemanticsActions.RequestFocus
        ) {
            it()
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        try {
            ui.waitUntil(8000) {
                ui.onAllNodesWithText("Scan with your phone").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            var state = ""
            ui.activityRule.scenario.onActivity { activity ->
                state =
                    androidx.lifecycle
                        .ViewModelProvider(activity)[LauncherViewModel::class.java]
                        .phoneSetup
                        .state
                        .value
                        .let { it.phase + ": " + it.error }
            }
            throw AssertionError("Phone setup state: $state. " + ui.onRoot().printToString(), e)
        }
        val image =
            ui.onNodeWithContentDescription("QR code for temporary phone setup")
                .assertIsDisplayed()
                .captureToImage()
                .asAndroidBitmap()
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val decoded =
            com.google.zxing
                .MultiFormatReader()
                .decode(
                    com.google.zxing.BinaryBitmap(
                        com.google.zxing.common.HybridBinarizer(
                            com.google.zxing.RGBLuminanceSource(image.width, image.height, pixels)
                        )
                    )
                )
        var link = ""
        ui.activityRule.scenario.onActivity { activity ->
            link =
                androidx.lifecycle
                    .ViewModelProvider(activity)[LauncherViewModel::class.java]
                    .phoneSetup
                    .state
                    .value
                    .url
        }
        org.junit.Assert.assertEquals(link, decoded.text)
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/open-home-tv-phone-setup.png")
            .use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
        key(KeyEvent.KEYCODE_BACK)
        ui.onNodeWithText("Set up with your phone").assertIsDisplayed()
    }
}
