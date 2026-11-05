package org.dad.foundation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DadMotionTest {
    @Test fun animationsAreOffOnlyWhenTheScaleIsZero() {
        assertFalse(animationsEnabled(0f))
        assertTrue(animationsEnabled(0.5f))
        assertTrue(animationsEnabled(1f))
    }
}
