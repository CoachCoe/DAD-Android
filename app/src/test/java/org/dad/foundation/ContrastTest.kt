package org.dad.foundation

import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    @Test fun contrastMatchesKnownReferenceValues() {
        assertTrue(DadTheme.contrast("ink", "card") > 15.0)
        assertTrue(DadTheme.contrast("brand", "paper") < 3.3)
    }

    @Test fun primaryButtonLabelMeetsAA() {
        // DadPrimaryButton and the Material primary buttons: paper text on brand-ink (light).
        assertTrue(DadTheme.contrast("paper", "brand-ink") >= 4.5)
        // Dark theme: Material onPrimary ink-deep text on brand fill.
        assertTrue(DadTheme.contrast("ink-deep", "brand") >= 4.5)
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
}
