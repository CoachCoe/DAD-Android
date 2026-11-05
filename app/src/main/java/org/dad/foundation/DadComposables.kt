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
fun DadGiftFraming(
    monthly: String,
    daily: String,
    modifier: Modifier = Modifier,
    monthlyModifier: Modifier = Modifier,
    dailyModifier: Modifier = Modifier,
    color: Color = DadTheme.color("ink"),
) {
    Column(
        modifier = modifier.semantics { contentDescription = "$monthly, $daily" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(monthly, modifier = monthlyModifier, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(daily, modifier = dailyModifier, style = MaterialTheme.typography.headlineMedium, color = color)
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
        "check" -> "\u2713" // ✓
        "lock" -> "\u2298" // ⊘ — a circled slash reads as "not yet", unlike an emoji padlock
        "card" -> "\u25AD" // ▭
        "history" -> "\u21BA" // ↺
        "chevron" -> "\u203A" // ›
        "person" -> "\u263A" // ☺
        "bell" -> "\u266A" // ♪
        "shield" -> "\u25C8" // ◈
        "key" -> "\u2699" // ⚙
        "calendar" -> "\u25A6" // ▦
        "plus" -> "+"
        "minus" -> "\u2212" // −
        else -> "\u2022" // • — an obvious, non-crashing fallback for an unknown name
    }
    Text(
        glyph,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        color = tint,
        style = MaterialTheme.typography.titleMedium,
    )
}
