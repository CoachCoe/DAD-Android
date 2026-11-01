package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanGateTest {
    private val token = "a".repeat(22) + "." + "b".repeat(43)
    private val other = "c".repeat(22) + "." + "d".repeat(43)

    private class Probe {
        val valid = mutableListOf<String>()
        var invalid = 0
        val gate = ScanGate(onValid = { valid += it }, onInvalid = { invalid++ })
    }

    @Test fun aWellFormedTokenIsSubmittedOnce() {
        val p = Probe()
        p.gate.offer(token)
        assertEquals(listOf(token), p.valid)
        assertTrue(p.gate.isDone)
        assertEquals(0, p.invalid)
    }

    @Test fun duplicateFramesSubmitOnlyOnce() {
        val p = Probe()
        repeat(30) { p.gate.offer(token) }
        assertEquals(listOf(token), p.valid)
    }

    @Test fun aDifferentCodeAfterTheFirstIsIgnored() {
        val p = Probe()
        p.gate.offer(token)
        p.gate.offer(other)
        p.gate.offer("https://example.org/x")
        assertEquals(listOf(token), p.valid)
        assertEquals(0, p.invalid)
    }

    @Test fun anInvalidCodeHintsAndScanningContinues() {
        val p = Probe()
        p.gate.offer("https://example.org/not-a-checkin")
        p.gate.offer("short")
        assertEquals(2, p.invalid)
        assertTrue(p.valid.isEmpty())
        assertFalse(p.gate.isDone)
        p.gate.offer(token)
        assertEquals(listOf(token), p.valid)
        assertTrue(p.gate.isDone)
    }

    @Test fun surroundingWhitespaceIsTrimmedButTheShapeIsNotRepaired() {
        val p = Probe()
        p.gate.offer(" $token\n")
        assertEquals(listOf(token), p.valid)
        val q = Probe()
        q.gate.offer(token.replace('.', '-'))
        assertEquals(1, q.invalid)
        assertTrue(q.valid.isEmpty())
    }

    @Test fun anEmptyReadIsAnInvalidCodeNotACrash() {
        val p = Probe()
        p.gate.offer("")
        assertEquals(1, p.invalid)
    }
}
