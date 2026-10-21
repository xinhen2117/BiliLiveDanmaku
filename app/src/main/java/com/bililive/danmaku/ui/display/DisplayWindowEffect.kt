package com.bililive.danmaku.ui.display

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** No wake lock, global settings write, background service or permission request. */
@Composable
fun DisplayWindowEffect(keepScreenOn: Boolean, brightness: Float?, immersive: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lease = remember(activity.window) { DisplayWindowLease(AndroidDisplayWindowPort(activity.window)) }
    val currentKeepOn = rememberUpdatedState(keepScreenOn)
    val currentBrightness = rememberUpdatedState(brightness)
    val currentImmersive = rememberUpdatedState(immersive)

    DisposableEffect(lease, lifecycle) {
        fun update() = lease.update(
            active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            keepScreenOn = currentKeepOn.value,
            brightness = currentBrightness.value,
            immersive = currentImmersive.value
        )
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            lease.release()
        }
    }
    LaunchedEffect(lease, lifecycle, keepScreenOn, brightness, immersive) {
        lease.update(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), keepScreenOn, brightness, immersive)
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class AndroidDisplayWindowPort(private val window: Window) : DisplayWindowPort {
    private val controller get() = WindowCompat.getInsetsController(window, window.decorView)
    private var original: DisplayWindowSnapshot? = null
    private var hidingBars = false

    override fun snapshot(): DisplayWindowSnapshot {
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        return DisplayWindowSnapshot(
            keepScreenOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
            brightness = window.attributes.screenBrightness,
            statusBarVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true,
            navigationBarVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true,
            barsBehavior = controller.systemBarsBehavior
        ).also { original = it }
    }

    override fun apply(keepScreenOn: Boolean, brightness: Float, immersive: Boolean) {
        applyAttributes(keepScreenOn, brightness)
        if (immersive && !hidingBars) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            hidingBars = true
        } else if (!immersive && hidingBars) {
            original?.let(::restoreBars)
            hidingBars = false
        }
    }

    override fun restore(snapshot: DisplayWindowSnapshot) {
        applyAttributes(snapshot.keepScreenOn, snapshot.brightness)
        if (hidingBars) restoreBars(snapshot)
        hidingBars = false
        original = null
    }

    private fun applyAttributes(keepScreenOn: Boolean, brightness: Float) {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (window.attributes.screenBrightness != brightness) {
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
    }

    private fun restoreBars(snapshot: DisplayWindowSnapshot) {
        controller.systemBarsBehavior = snapshot.barsBehavior
        if (snapshot.statusBarVisible) controller.show(WindowInsetsCompat.Type.statusBars())
        else controller.hide(WindowInsetsCompat.Type.statusBars())
        if (snapshot.navigationBarVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
        else controller.hide(WindowInsetsCompat.Type.navigationBars())
    }
}
