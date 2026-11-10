package dev.hatv.launcher

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hatv.launcher.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardFeaturesTest {
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

    private fun select(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        ui.waitForIdle()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test
    fun homeCamerasCanBeCountedReorderedAndHiddenWithoutHeatingControls() {
        lateinit var model: LauncherViewModel
        lateinit var original: Config
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[LauncherViewModel::class.java]
            original = model.config.value
            model.update(original.copy(demo = true, cameras = Demo.cameras, homeCameraCount = 2))
        }
        try {
            home()
            ui.onNodeWithText("Living Room · Thermostat").assertDoesNotExist()
            ui.onAllNodesWithText("−").assertCountEquals(0)
            ui.onNodeWithTag("home-camera:camera.harbor").assertIsDisplayed()
            ui.onNodeWithTag("home-camera:camera.garden").assertDoesNotExist()
            repeat(7) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.onNodeWithTag("settings-list")
                .performScrollToNode(hasText("Home cameras and streams"))
            select(ui.onNodeWithText("Home cameras and streams"))
            ui.onNodeWithTag("settings-list")
                .performScrollToNode(hasTestTag("home-camera-count-up"))
            select(ui.onNodeWithTag("home-camera-count-up"))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.onNodeWithText("Home camera feeds: 4").assertIsDisplayed()
            ui.onNodeWithTag("settings-list")
                .performScrollToNode(hasTestTag("camera-up:camera.rhine"))
            select(ui.onNodeWithTag("camera-up:camera.rhine"))
            key(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithTag("settings-list").performScrollToNode(hasText("Save"))
            select(ui.onAllNodesWithText("Save").onFirst())
            ui.waitUntil(5000) {
                model.config.value.homeCameraCount == 4 &&
                    model.config.value.cameras.first().entityId == "camera.rhine"
            }
            assertEquals(model.config.value, model.store.load())
            home()
            val first =
                ui.onNodeWithTag("home-camera:camera.rhine")
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            val second =
                ui.onNodeWithTag("home-camera:camera.harbor")
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            val third =
                ui.onNodeWithTag("home-camera:camera.garden")
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            ui.onNodeWithTag("home-camera:camera.entry").assertIsDisplayed()
            assertTrue(first.left < second.left)
            assertTrue(third.top > first.top)
            ui.activityRule.scenario.onActivity {
                model.update(model.config.value.copy(homeCameraCount = 0))
            }
            ui.waitForIdle()
            ui.onNodeWithTag("home-camera:camera.rhine").assertDoesNotExist()
            ui.onNodeWithText("Cameras").assertDoesNotExist()
            ui.activityRule.scenario.onActivity {
                model.update(model.config.value.copy(homeCameraCount = 1))
            }
            ui.waitForIdle()
            ui.onNodeWithTag("home-camera:camera.rhine").assertIsDisplayed()
            ui.onNodeWithTag("home-camera:camera.harbor").assertDoesNotExist()
        } finally {
            ui.activityRule.scenario.onActivity { model.update(original) }
        }
    }

    @Test
    fun oneHomeCameraSavesDirectlyFromCameraSettingsAndSurvivesFreshViewModel() {
        lateinit var original: Config
        lateinit var firstModel: LauncherViewModel
        ui.activityRule.scenario.onActivity {
            firstModel = ViewModelProvider(it)[LauncherViewModel::class.java]
            original = firstModel.config.value
            firstModel.update(
                original.copy(demo = true, cameras = Demo.cameras, homeCameraCount = 2)
            )
        }
        fun cameraSettings() {
            home()
            repeat(7) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.onNodeWithTag("settings-list")
                .performScrollToNode(hasText("Home cameras and streams"))
            select(ui.onNodeWithText("Home cameras and streams"))
        }
        try {
            cameraSettings()
            ui.onNodeWithTag("home-camera-count-down").assertIsFocused()
            ui.onNodeWithTag("settings-list")
                .performScrollToNode(hasTestTag("home-camera-count-down"))
            select(ui.onNodeWithTag("home-camera-count-down"))
            ui.onNodeWithText("Home camera feeds: 1").assertIsDisplayed()
            ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("camera-save-top"))
            ui.onNodeWithText("Unsaved changes").assertIsDisplayed()
            select(ui.onNodeWithTag("camera-save-top"))
            ui.waitUntil(5000) { firstModel.config.value.homeCameraCount == 1 }
            ui.onNodeWithText("Camera view saved").assertIsDisplayed()
            ui.activityRule.scenario.onActivity {
                val persisted = SettingsStore(it).load()
                assertEquals(1, persisted.homeCameraCount)
                assertEquals(Demo.cameras, persisted.cameras)
                // A new ViewModel must read disk, rather than retaining the UI draft.
                it.viewModelStore.clear()
            }
            ui.activityRule.scenario.recreate()
            ui.activityRule.scenario.onActivity {
                val fresh = ViewModelProvider(it)[LauncherViewModel::class.java]
                assertNotSame(firstModel, fresh)
                assertEquals(1, fresh.config.value.homeCameraCount)
            }
            home()
            ui.onNodeWithTag("home-camera:camera.harbor").assertIsDisplayed()
            ui.onNodeWithTag("home-camera:camera.rhine").assertDoesNotExist()
            cameraSettings()
            ui.onNodeWithTag("settings-list").performScrollToNode(hasText("Home camera feeds: 1"))
            ui.onNodeWithText("Home camera feeds: 1").assertIsDisplayed()
            // The footer saves the same draft, without returning to main Settings.
            select(ui.onNodeWithTag("home-camera-count-down"))
            ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("camera-save-bottom"))
            select(ui.onNodeWithTag("camera-save-bottom"))
            ui.waitUntil(5000) { SettingsStore(ui.activity).load().homeCameraCount == 0 }
        } finally {
            ui.activityRule.scenario.onActivity {
                ViewModelProvider(it)[LauncherViewModel::class.java].update(original)
            }
        }
    }

    @Test
    fun galleryUsesHeaderSpaceAndKeepsForecastAccessible() {
        lateinit var model: LauncherViewModel
        lateinit var original: Config
        ui.activityRule.scenario.onActivity {
            model = ViewModelProvider(it)[LauncherViewModel::class.java]
            original = model.config.value
            model.update(
                original.copy(
                    demo = true,
                    cameras = Demo.cameras,
                    homeCameraCount = 1,
                    widgets = Config().widgets,
                )
            )
        }
        try {
            home()
            ui.onNodeWithText("Open home TV").assertDoesNotExist()
            val screen = ui.onRoot().getUnclippedBoundsInRoot()
            val weather =
                ui.onNodeWithTag("home-weather").assertIsDisplayed().getUnclippedBoundsInRoot()
            val power = ui.onNodeWithTag("energy-card").getUnclippedBoundsInRoot()
            val camera = ui.onNodeWithTag("home-camera:camera.harbor").getUnclippedBoundsInRoot()
            assertTrue(
                "An empty header row remains",
                weather.top.value < (screen.bottom.value - screen.top.value) * .08f,
            )
            assertEquals(weather.top.value, power.top.value, 2f)
            assertEquals(power.top.value, camera.top.value, 2f)
            assertTrue(
                weather.right.value - weather.left.value < power.right.value - power.left.value &&
                    power.right.value - power.left.value < camera.right.value - camera.left.value
            )
            ui.onNodeWithText("All apps").assertIsDisplayed()
            select(ui.onNodeWithTag("home-weather"))
            ui.onNodeWithText("Hourly").assertIsDisplayed()
            select(ui.onNodeWithText("Hourly"))
            ui.onNodeWithText("Daily").assertIsDisplayed()
            key(KeyEvent.KEYCODE_BACK)
            ui.onNodeWithTag("home-weather").assertIsFocused()
        } finally {
            ui.activityRule.scenario.onActivity { model.update(original) }
        }
    }

    @Test
    fun selectTogglesPowerAndTodayAndColorfulScenesStillActivate() {
        home()
        ui.onNodeWithText("Home power").assertIsDisplayed()
        select(ui.onNodeWithTag("energy-card"))
        ui.onNodeWithText("Energy consumption").assertIsDisplayed()
        ui.onAllNodesWithText("kWh", substring = true).onFirst().assertIsDisplayed()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ui.onNodeWithText("Home power").assertIsDisplayed()
        select(ui.onNodeWithTag("energy-details"))
        ui.onNodeWithText("Energy consumption · Today").assertIsDisplayed()
        key(KeyEvent.KEYCODE_BACK)
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        select(ui.onNodeWithTag("scene:scene.movie"))
        ui.onNodeWithText("✓ Film").assertIsDisplayed()
    }
}
