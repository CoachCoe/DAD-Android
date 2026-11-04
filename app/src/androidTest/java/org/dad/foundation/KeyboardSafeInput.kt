package org.dad.foundation

import android.app.Activity
import android.view.Choreographer
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keyboard-safe interaction for the instrumentation tests.
 *
 * Root cause (GiftFlowTest, fbbffc8): the soft keyboard's inset animation is driven by the system, not the Compose
 * clock, so waitForIdle does not cover it. A button tapped while the viewport is still shrinking ends up clipped
 * behind the keyboard, and a click on a clipped node is dropped WITHOUT an error. Two safeguards, used by every
 * test that types and then taps:
 *  1. [awaitKeyboardSettled] after text input: waits (on real frames, no sleeps) until the keyboard's inset has
 *     stopped changing, so layout has taken its final height;
 *  2. [tapSafely]: scroll into view, assert it is displayed AND not under the keyboard, then click, so a covered
 *     node fails here at the cause instead of silently doing nothing.
 */
private const val SETTLE_TIMEOUT_MS = 20_000L
private const val STABLE_FRAMES = 10

// If the IME inset never becomes visible within this many frames, treat the platform as one that
// cannot report it (see awaitKeyboardSettled) and stop waiting. ~180 frames is about 3s at 60fps.
private const val NO_IME_GRACE_FRAMES = 180

private fun resumedActivity(): Activity? {
    var found: Activity? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    return found
}

private fun imeBottomInset(activity: Activity): Pair<Boolean, Int> {
    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView) ?: return false to 0
    return insets.isVisible(WindowInsetsCompat.Type.ime()) to insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
}

/** Waits until the soft keyboard is up and its height has been unchanged for [STABLE_FRAMES] consecutive frames. */
fun ComposeTestRule.awaitKeyboardSettled() {
    val activity = checkNotNull(resumedActivity()) { "no resumed activity to read the keyboard from" }
    val settled = AtomicBoolean(false)
    var last = -1
    var stable = 0
    var sawIme = false
    var frames = 0
    val onFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frames++
            val (visible, bottom) = imeBottomInset(activity)
            if (visible && bottom > 0) sawIme = true
            if (visible && bottom > 0 && bottom == last) stable++ else stable = 0
            last = bottom
            // On a headless emulator (-no-window -gpu swiftshader_indirect) there is no real window
            // surface, so the soft keyboard often never reports a positive IME inset to the decorView:
            // `stable` would never reach STABLE_FRAMES and every typing test would block the full 20s.
            // If the inset has not become visible even once within the grace window, give up waiting and
            // fall back to waitForIdle(). This does NOT weaken the clip guard: when the inset never
            // reports, bottom stays 0, so assertNotUnderKeyboard (which no-ops when the inset is not
            // visible) is still correct and no tap is wrongly allowed under a keyboard. When the inset
            // DOES report (a windowed emulator), we still wait for it to settle as before.
            val settledNow = stable >= STABLE_FRAMES || (!sawIme && frames >= NO_IME_GRACE_FRAMES)
            if (settledNow) settled.set(true) else Choreographer.getInstance().postFrameCallback(this)
        }
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync { Choreographer.getInstance().postFrameCallback(onFrame) }
    try {
        waitUntil(SETTLE_TIMEOUT_MS) { settled.get() }
        waitForIdle()
    } finally {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().removeFrameCallback(onFrame)
        }
    }
}

/** Fails if the node's bottom edge sits under the soft keyboard (a click there is silently dropped). */
private fun SemanticsNodeInteraction.assertNotUnderKeyboard(description: String) {
    val activity = resumedActivity() ?: return
    val (visible, bottom) = imeBottomInset(activity)
    if (!visible) return
    val limit = activity.window.decorView.height - bottom
    val nodeBottom = fetchSemanticsNode().boundsInWindow.bottom
    check(nodeBottom <= limit + 1f) { "$description is covered by the keyboard (bottom=$nodeBottom, keyboard top=$limit)" }
}

private fun ComposeTestRule.exists(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

/** Waits for [tag] to exist and be enabled, scrolls it into view, checks it is visible and clear of the keyboard, clicks. */
fun ComposeTestRule.tapSafely(tag: String, timeoutMs: Long = 15_000) {
    waitUntil(timeoutMs) { exists(tag) }
    waitUntil(timeoutMs) { runCatching { onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
    tapNode(onNodeWithTag(tag), tag)
}

/** Same safeguards for a node found some other way (e.g. by text). */
fun ComposeTestRule.tapNode(node: SemanticsNodeInteraction, description: String) {
    // App-shell chrome (the Scaffold TopAppBar back arrow / account avatar and the NavigationBar tabs)
    // is deliberately NOT inside a scrollable container, so performScrollTo there throws "no parent
    // layout with a Scroll SemanticsAction". Such chrome is always on screen, so a failed scroll there
    // is harmless; attempt the scroll and swallow only that failure (same runCatching idiom as
    // DashboardFlowTest). Reading ScrollBy off the semantics tree to pre-decide missed scrollable text
    // fields, which skipped the scroll, left the field off-screen and unfocused, and timed out the IME
    // wait in awaitKeyboardSettled. Still assert displayed + clear of the keyboard either way.
    runCatching { node.performScrollTo() }
    node.assertIsDisplayed()
    node.assertNotUnderKeyboard(description)
    node.performClick()
}

/** Types into [tag] (scrolled into view first), then waits for the keyboard to settle so the next tap is safe. */
fun ComposeTestRule.enterText(
    tag: String, text: String, replace: Boolean = false, timeoutMs: Long = 15_000, settleAfter: Boolean = true,
) {
    waitUntil(timeoutMs) { exists(tag) }
    val field = onNodeWithTag(tag).performScrollTo()
    // A semantics-only insertion can request the IME before Android has served the editor.
    // Focus it through the same touch path as a user, then wait for the input connection and layout.
    tapNode(field, tag)
    awaitKeyboardSettled()
    if (replace) field.performTextReplacement(text) else field.performTextInput(text)
    // A field whose last character submits (the 6-digit sign-in code) dismisses the keyboard itself,
    // so there is no settled keyboard to wait for afterwards.
    if (settleAfter) awaitKeyboardSettled()
}
