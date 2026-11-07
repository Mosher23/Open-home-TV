package dev.hatv.launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class KeyboardNavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        ui.waitForIdle()
    }

    private fun keyboardVisible() =
        instrumentation.uiAutomation.windows.any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }

    @Test
    fun navigationDoesNotOpenImeAndSelectExplicitlyEdits() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val oldFlags = info.flags
        info.flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        try {
            ui.activityRule.scenario.onActivity {
                it.startActivity(
                    Intent(it, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            ui.waitForIdle()
            repeat(7) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            val url = "setting-field:" + ui.activity.getString(R.string.server_url)
            val token = "setting-field:" + ui.activity.getString(R.string.access_token)
            ui.onNodeWithTag(url).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            ui.onNodeWithTag(url).assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            ui.onNodeWithTag(token).assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_UP)
            ui.onNodeWithTag(url).assertIsFocused()
            // IME requests arrive asynchronously through Binder; allow their normal arrival window.
            SystemClock.sleep(500)
            assertFalse("Browsing text settings opened the keyboard", keyboardVisible())
            ui.onAllNodes(hasSetTextAction()).assertCountEquals(0)

            key(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.onNodeWithTag("setting-editor").assertIsFocused()
            ui.waitUntil(5000) { keyboardVisible() }
            ui.onNodeWithTag("setting-editor").performTextReplacement("http://example.local:8123")
            ui.onNodeWithTag("setting-editor").performImeAction()
            ui.waitUntil(5000) { !keyboardVisible() }
            ui.onNodeWithTag("setting-editor").assertDoesNotExist()
            ui.onNodeWithTag(url).assertIsFocused()
            ui.onNodeWithText("http://example.local:8123").assertIsDisplayed()

            key(KeyEvent.KEYCODE_DPAD_DOWN)
            ui.onNodeWithTag(token).assertIsFocused()
            SystemClock.sleep(500)
            assertFalse(keyboardVisible())
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.waitUntil(5000) { keyboardVisible() }
            ui.onNodeWithTag("setting-editor").performTextReplacement("fixture-token-not-saved")
            key(KeyEvent.KEYCODE_BACK)
            if (ui.onAllNodesWithTag("setting-editor").fetchSemanticsNodes().isNotEmpty())
                key(KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5000) { !keyboardVisible() }
            ui.onNodeWithTag("setting-editor").assertDoesNotExist()
            ui.onNodeWithTag(token).assertIsFocused()
            ui.onNodeWithText("fixture-token-not-saved").assertDoesNotExist()
            key(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithText("Home power").assertIsDisplayed()
        } finally {
            info.flags = oldFlags
            automation.serviceInfo = info
        }
    }
}
