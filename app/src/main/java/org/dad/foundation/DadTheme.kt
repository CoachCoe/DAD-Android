package org.dad.foundation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import org.dad.foundation.generated.DADTokens

/**
 * Design system — promotes the generated [DADTokens] palette/scale into reusable,
 * accessibility-first Compose primitives. Every surface pairs a background with its
 * foreground so light/dark both read; color is never the only signal.
 */
object DadTheme {
    /**
     * Resolve a DAD color token by name. Falls back to a visible magenta for an unknown token
     * so a typo is obvious rather than silently wrong. Some tokens (outline cues like `ghost-line`)
     * are authored as `rgba(r,g,b,a)` with fractional alpha; `toColorInt` only parses `#rgb`/`#rrggbb`,
     * so those are decoded here to keep their real token value (and alpha) rather than a magenta fallback.
     */
    fun color(name: String): Color {
        val raw = DADTokens.color[name] ?: return Color.Magenta
        parseRgba(raw)?.let { return it }
        return runCatching { Color(raw.toColorInt()) }.getOrElse { Color.Magenta }
    }

    /** Decode a `rgba(r,g,b,a)` / `rgb(r,g,b)` token string, or null if it is not that form. */
    private fun parseRgba(value: String): Color? {
        val inner = value.trim().removePrefix("rgba").removePrefix("rgb").trim()
        if (!inner.startsWith("(") || !inner.endsWith(")")) return null
        val parts = inner.substring(1, inner.length - 1).split(",").map { it.trim() }
        if (parts.size !in 3..4) return null
        val r = parts[0].toIntOrNull() ?: return null
        val g = parts[1].toIntOrNull() ?: return null
        val b = parts[2].toIntOrNull() ?: return null
        val a = if (parts.size == 4) parts[3].toFloatOrNull() ?: return null else 1f
        if (r !in 0..255 || g !in 0..255 || b !in 0..255 || a !in 0f..1f) return null
        return Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a)
    }

    /**
     * The brand colour for TEXT and icons on the page background. The raw `brand` token is only
     * ~3.2:1 on paper (fails WCAG AA for normal text), so light uses `brand-ink` (5.0:1 on paper)
     * and dark uses `brand` itself (5.5:1 on ink-deep; `brand-ink` there is only 3.5:1).
     */
    fun brandAccentToken(dark: Boolean): String = if (dark) "brand" else "brand-ink"

    fun brandAccent(dark: Boolean): Color = color(brandAccentToken(dark))

    /** Page background token for the given theme. */
    fun backgroundToken(dark: Boolean): String = if (dark) "ink-deep" else "paper"

    /** WCAG 2.x contrast ratio (1.0..21.0) between two opaque `#rgb`/`#rrggbb` tokens, by token name. */
    fun contrast(foreground: String, background: String): Double {
        val la = luminance(DADTokens.color.getValue(foreground))
        val lb = luminance(DADTokens.color.getValue(background))
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(hex: String): Double {
        val digits = hex.removePrefix("#").let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        fun channel(i: Int): Double {
            val v = digits.substring(i, i + 2).toInt(16) / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
    }

    fun space(step: String): Dp = (DADTokens.space[step] ?: 0.0).dp
    fun radius(name: String): Dp = (DADTokens.radius[name] ?: 0.0).dp
}

/** Marks a title for TalkBack heading navigation. */
fun Modifier.dadHeading(): Modifier = semantics { heading() }

/**
 * A top app bar whose only control is a back arrow. The arrow is an [IconButton] (never a filled
 * button, so a screen keeps its single filled primary) drawn with the "\u2190" glyph — no
 * `material-icons` dependency is on the classpath, so the glyph is the icon. It carries [backTag]
 * (the testTag moved off the old inline Close/Back button) and [backDescription] for TalkBack; the
 * 48dp IconButton is the touch target. Colours come from the theme (no literal Color). [title] is
 * optional because most screens keep their heading in the body; pass it only where the bar owns it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DadBackBar(backTag: String, backDescription: String, title: String = "", onBack: () -> Unit) {
    TopAppBar(
        title = { if (title.isNotEmpty()) Text(title, modifier = Modifier.dadHeading()) },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = backDescription }.testTag(backTag),
            ) { Text("\u2190", style = MaterialTheme.typography.headlineSmall) }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/**
 * Primary call-to-action — the web primary: `brand` fill with an `ink` label (ink-on-brand = 5.11:1,
 * WCAG AA). Replaces the former `brand-ink` fill + `paper` label; a `paper` label on raw `brand` is
 * only 3.2:1, so the label is `ink`, not paper. 48dp minimum target. One filled primary per screen.
 */
@Composable
fun DadPrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    DadPrimaryButton(text = text, modifier = modifier, enabled = true, onClick = onClick)

/** Primary call-to-action with an explicit enabled state (e.g. a busy/invalid form guard). */
@Composable
fun DadPrimaryButton(text: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(DadTheme.radius("pill")),
        colors = ButtonDefaults.buttonColors(
            containerColor = DadTheme.color("brand"),
            contentColor = DadTheme.color("ink"),
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * Secondary action — an outlined, ghost-line "ghost" button. The brand accent label (brand-ink on
 * paper / brand on ink-deep via [DadTheme.brandAccent]) stays on the page background, never a fill, so
 * it keeps its AA contrast. 48dp minimum target.
 */
@Composable
fun DadSecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(DadTheme.radius("pill")),
        border = BorderStroke(1.dp, DadTheme.color(if (dark) "ghost-line-dark" else "ghost-line")),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DadTheme.brandAccent(dark)),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Low-emphasis text action (links-as-buttons). Brand accent label on the page background. 48dp target. */
@Composable
fun DadTextButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = DadTheme.brandAccent(dark)),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * Destructive text action, drawn in the `fail` token. Colour is never the only signal: the caller's
 * label must state the destructive intent in words (e.g. "Delete my account"), which this renders as-is.
 * 48dp target.
 */
@Composable
fun DadDestructiveTextButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = DadTheme.color("fail")),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * 30-day streak bar. Filled days use the teal `ok` family (fill + `ok-ink` outline), not brand orange:
 * orange is DAD's identity, not a progress signal. Each filled day carries a shape fill AND the whole
 * bar is labeled for TalkBack with its "N of M days" value — color is never the only signal (the text
 * label is the accessible signal; the outline is a redundant shape cue). (PRD §10 / a11y bar.)
 */
@Composable
fun DadStreakBar(filled: Int, modifier: Modifier = Modifier, total: Int = 30) {
    val safeTotal = total.coerceAtLeast(1)
    val safeFilled = filled.coerceIn(0, safeTotal)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clearAndSetSemantics { contentDescription = "Streak: $safeFilled of $safeTotal days" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(safeTotal) { index ->
            val isFilled = index < safeFilled
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(10.dp)
                    .background(
                        if (isFilled) DadTheme.color("ok") else DadTheme.color("line"),
                        RoundedCornerShape(2.dp),
                    )
                    .then(
                        // Shape cue (outline) in addition to color for filled segments.
                        if (isFilled) {
                            Modifier.border(0.5.dp, DadTheme.color("ok-ink"), RoundedCornerShape(2.dp))
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}
