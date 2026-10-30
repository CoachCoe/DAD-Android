package org.dad.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
     * so a typo is obvious rather than silently wrong.
     */
    fun color(name: String): Color {
        val hex = DADTokens.color[name] ?: return Color.Magenta
        return runCatching { Color(hex.toColorInt()) }.getOrElse { Color.Magenta }
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
 * Primary call-to-action. 48dp minimum target. Paper text on `brand-ink` (5.0:1, WCAG AA); the raw
 * `brand` fill gave only 3.2:1 behind normal-size label text.
 */
@Composable
fun DadPrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(DadTheme.radius("pill")),
        colors = ButtonDefaults.buttonColors(
            containerColor = DadTheme.color("brand-ink"),
            contentColor = DadTheme.color("paper"),
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * 30-day streak bar. Each filled day carries a shape fill AND the whole bar is labeled for
 * TalkBack with its "N of M days" value — color is never the only signal (PRD §10 / a11y bar).
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
                        if (isFilled) DadTheme.color("brand") else DadTheme.color("line"),
                        RoundedCornerShape(2.dp),
                    )
                    .then(
                        // Shape cue (outline) in addition to color for filled segments.
                        if (isFilled) {
                            Modifier.border(0.5.dp, DadTheme.color("brand-ink"), RoundedCornerShape(2.dp))
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}
