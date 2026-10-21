package com.bililive.danmaku.ui.display

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bililive.danmaku.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Needs an emulator/device. Pure JVM lease tests do not substitute for these lifecycle checks. */
@RunWith(AndroidJUnit4::class)
class DisplayWindowEffectTest {
    @Test fun pauseAndDisposeRestoreOriginalWindowAttributes() {
        val show = mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f }
                activity.setContent { if (show.value) DisplayWindowEffect(true, 0.2f, true) }
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(0.2f, activity.window.attributes.screenBrightness)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                assertEquals(-1f, activity.window.attributes.screenBrightness)
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(0.2f, activity.window.attributes.screenBrightness)
                show.value = false
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(-1f, activity.window.attributes.screenBrightness)
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
