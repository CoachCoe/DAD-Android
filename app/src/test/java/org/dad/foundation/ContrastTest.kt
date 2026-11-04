package org.dad.foundation

import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    @Test fun contrastMatchesKnownReferenceValues() {
        assertTrue(DadTheme.contrast("ink", "card") > 15.0)
        assertTrue(DadTheme.contrast("brand", "paper") < 3.3)
    }

    @Test fun primaryButtonLabelMeetsAA() {
        // The web-style primary (DadPrimaryButton + Material primary) is a brand fill with an ink
        // label in BOTH light and dark: ink-on-brand = 5.11:1, clearing WCAG AA (>= 4.5).
        val ratio = DadTheme.contrast("ink", "brand")
        assertTrue("ink-on-brand ratio=$ratio", ratio >= 4.5)
    }

    @Test fun streakBarFillReadsOnPageBackground() {
        // The streak bar fills with teal `ok` (orange is identity, not progress). Pin the fill against
        // the light page background it reads on: paper-on-ok = 4.90:1 (AA). The `ok-ink` outline is a
        // redundant shape cue (ok-ink-on-ok is only 1.44:1), NOT the accessible signal — the bar's text
        // contentDescription ("Streak: N of M days") carries state — so it is intentionally not asserted.
        assertTrue(DadTheme.contrast("paper", "ok") >= 4.5)
    }

    @Test fun brandTextOnPageBackgroundMeetsAAInBothThemes() {
        for (dark in listOf(false, true)) {
            val ratio = DadTheme.contrast(DadTheme.brandAccentToken(dark), DadTheme.backgroundToken(dark))
            assertTrue("dark=$dark ratio=$ratio", ratio >= 4.5)
        }
    }

    @Test fun lockedBadgeTextMeetsAAInBothThemes() {
        assertTrue(DadTheme.contrast("muted", "paper") >= 4.5)
        assertTrue(DadTheme.contrast("dkmut", "ink-deep") >= 4.5)
    }

    @Test fun atRiskBannerTextMeetsAA() {
        // Home's at-risk banner: `warn` text + warning glyph on a `warn-wash` block. warn-on-warn-wash =
        // 4.91:1 (AA). Colour is not the only signal (glyph + text), but the text itself must still read.
        assertTrue(DadTheme.contrast("warn", "warn-wash") >= 4.5)
    }

    @Test fun streakHeroTextOnInkMeetsAA() {
        // The streak hero card sits on the `ink` surface. Every foreground it uses clears AA on ink:
        // the day count / rank lines in `paper` (16.29:1), the low-emphasis labels in `dkmut` (9.77:1),
        // and the Founding-Donor chip in `brand` (5.11:1).
        assertTrue(DadTheme.contrast("paper", "ink") >= 4.5)
        assertTrue(DadTheme.contrast("dkmut", "ink") >= 4.5)
        assertTrue(DadTheme.contrast("brand", "ink") >= 4.5)
    }
}
