package org.dad.foundation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The full-screen moment after a donor's first gift settles. Only the check circle moves (a short
 * scale-in, skipped when the system has animations off); no figure animates. The Founding chip appears
 * only when [founding] is true, so a flag that has not arrived yet never reads as "not a founding donor".
 */
@Composable
fun FirstGiftCelebration(
    amountMonthly: String,
    amountDaily: String,
    founding: Boolean,
    onInvite: () -> Unit,
    onDone: () -> Unit,
) {
    val haptics = rememberDadHaptics()
    val animate = rememberAnimationsEnabled()
    val scale = remember { Animatable(if (animate) 0.6f else 1f) }
    LaunchedEffect(Unit) {
        haptics.confirm()
        if (animate) scale.animateTo(1f, tween(durationMillis = 250))
    }
    val paper = DadTheme.color("paper")
    Surface(
        modifier = Modifier.fillMaxSize().testTag("celebration-screen"),
        color = DadTheme.color("ink"),
        contentColor = paper,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(96.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                    .clip(CircleShape).background(DadTheme.color("brand"), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DadSymbol("check", "Gift confirmed", tint = DadTheme.color("ink"))
            }
            Text(
                "You're in. Welcome to the beginning.",
                style = MaterialTheme.typography.headlineMedium, color = paper,
                modifier = Modifier.dadHeading(),
            )
            DadGiftFraming(amountMonthly, amountDaily, color = paper)
            // Polite live region on the wrapper so a flag that lands after the screen opens is announced.
            Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                if (founding) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.testTag("celebration-founding").semantics(mergeDescendants = true) {},
                    ) {
                        DadSymbol("star", "Founding donor badge", tint = DadTheme.color("brand"))
                        Text("Founding Donor", style = MaterialTheme.typography.labelLarge, color = DadTheme.color("brand"))
                    }
                }
            }
            DadPrimaryButton("Invite", Modifier.testTag("celebration-invite"), onClick = onInvite)
            DadTextButton("Done", Modifier.testTag("celebration-done"), contentColor = DadTheme.color("brand"), onClick = onDone)
        }
    }
}
