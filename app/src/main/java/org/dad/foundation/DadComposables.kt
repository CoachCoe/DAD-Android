package org.dad.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Shared DAD composables (design-system primitives). Additive: these are wired into screens in later
// PRs, so no screen behaviour or testTags change here. Token colours only; 48dp-safe; a title that is a
// section heading carries dadHeading().

/**
 * Frames a recurring gift as its monthly and daily amounts at EQUAL visual weight — both lines use the
 * same figure style and colour, so neither reads as subordinate (replacing the old displaySmall vs
 * bodyLarge imbalance). The pair is one semantics group for TalkBack.
 */
@Composable
fun DadGiftFraming(monthly: String, daily: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics { contentDescription = "$monthly, $daily" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(monthly, style = MaterialTheme.typography.headlineMedium, color = DadTheme.color("ink"))
        Text(daily, style = MaterialTheme.typography.headlineMedium, color = DadTheme.color("ink"))
    }
}

/**
 * A labelled statistic. A null [value] renders as "—" with a "Not available" contentDescription,
 * matching EngagementScreens' ImpactCard so unknowns read the same everywhere. The value uses the
 * figure style; the caption is low-emphasis.
 */
@Composable
fun DadStatTile(title: String, value: String?, caption: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (value != null) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
        } else {
            Text("—", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { contentDescription = "Not available" })
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = DadTheme.color("muted"))
    }
}

/**
 * A named icon-glyph carrying a real [contentDescription], so a "star"/"warning" cue is never colour
 * alone — the glyph is a shape and TalkBack reads the description. No `material-icons` Gradle artifact
 * and no Material Symbols font are on the classpath (adding either breaks the NO-new-dependency
 * constraint), so — matching the shell's established glyph-as-icon precedent (the back arrow \u2190 in
 * DadBackBar) — each named symbol maps to a stable Unicode glyph. The token [tint] keeps it in the
 * design palette; callers place it inline with the text it annotates.
 */
@Composable
fun DadSymbol(name: String, contentDescription: String, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val glyph = when (name) {
        "warning" -> "\u26A0" // ⚠
        "star" -> "\u2605" // ★
        else -> "\u2022" // • — an obvious, non-crashing fallback for an unknown name
    }
    Text(
        glyph,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        color = tint,
        style = MaterialTheme.typography.titleMedium,
    )
}

/**
 * An empty-state hint: an explanatory line plus an action slot. Callers pass a [DadSecondaryButton] or
 * [DadTextButton] as the action, so the button weight stays the caller's choice.
 */
@Composable
fun DadEmptyHint(text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        action()
    }
}
