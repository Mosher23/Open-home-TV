package dev.hatv.launcher

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import dev.hatv.launcher.ui.StartedEffect
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LifecycleWorkTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun workStopsWhileFramesArePausedAndRestartsOnReturn() {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        ui.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                StartedEffect(Unit) {
                    starts.incrementAndGet()
                    try {
                        awaitCancellation()
                    } finally {
                        stops.incrementAndGet()
                    }
                }
            }
        }
        ui.waitUntil(3000) { starts.get() == 1 }
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.waitUntil(3000) { stops.get() == 1 }
        assertEquals(1, starts.get())
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.waitUntil(3000) { starts.get() == 2 }
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.waitUntil(3000) { stops.get() == 2 }
    }
}
