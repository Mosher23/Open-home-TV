package dev.hatv.launcher

import android.content.Intent
import android.view.KeyEvent
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
        repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.onNodeWithText("Home Assistant server URL").assertIsDisplayed()
        ui.onAllNodesWithText("Save").onFirst().assertIsDisplayed()
        ui.onNodeWithText("Forget saved token").assertIsDisplayed()
        key(KeyEvent.KEYCODE_BACK)
        ui.onNodeWithText("Home energy · now").assertIsDisplayed()
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
        ui.onNodeWithText("Home energy · now").assertIsDisplayed()
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
}
