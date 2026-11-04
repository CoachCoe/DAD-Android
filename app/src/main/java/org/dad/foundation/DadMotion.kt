package org.dad.foundation

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

// Moments-of-joy helpers: a haptic on a confirmed or refused action, and the system "remove animations"
// switch. Both are side effects of a state change the donor caused, never decoration.

/** Confirm / reject haptics. Callers fire these from effects keyed on a state transition, not from composition. */
class DadHaptics internal constructor(private val feedback: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
}

@Composable
fun rememberDadHaptics(): DadHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { DadHaptics(feedback) }
}

/** The system animator scale is 0 when the donor turned animations off. */
internal fun animationsEnabled(scale: Float): Boolean = scale > 0f

@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        animationsEnabled(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
}
