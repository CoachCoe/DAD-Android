package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MilestoneTest {
    private fun streak(current: Int, last: Long) =
        HomeStreak(current, false, null, HomeLadder(current.toLong(), last, null, null, emptyList()))

    @Test fun theFirstCallSeedsTheBaselineSilently() {
        val store = MemoryMilestoneStore()
        assertNull(pendingMilestone(streak(18, 7), store))
        assertEquals(7L, store.seen())
        assertNull(pendingMilestone(streak(18, 7), store))
    }

    @Test fun aNewlyCrossedMilestoneShowsUntilItIsMarkedSeen() {
        val store = MemoryMilestoneStore(7)
        assertEquals(30L, pendingMilestone(streak(30, 30), store))
        assertEquals(30L, pendingMilestone(streak(31, 30), store))
        store.markSeen(30)
        assertNull(pendingMilestone(streak(31, 30), store))
    }

    @Test fun aMilestoneTheStreakNoLongerCoversDoesNotShow() {
        assertNull(pendingMilestone(streak(3, 30), MemoryMilestoneStore(7)))
    }

    @Test fun anUnknownStreakOrLadderShowsNothingAndSeedsNothing() {
        val store = MemoryMilestoneStore()
        assertNull(pendingMilestone(null, store))
        assertNull(pendingMilestone(HomeStreak(5, false, null, null), store))
        assertNull(store.seen())
    }

    @Test fun clearingForgetsTheBaseline() {
        val store = MemoryMilestoneStore(7)
        store.clear()
        assertNull(store.seen())
    }

    @Test fun eachMilestoneHasItsLine() {
        assertEquals("30 days. That's a month of aid, on the record.", milestoneLine(30))
        assertEquals("7 days. A full week of aid, on the record.", milestoneLine(7))
        assertEquals("100 days. Triple digits, on the record.", milestoneLine(100))
        assertEquals("365 days. A full year of aid, on the record.", milestoneLine(365))
        assertEquals("60 days. Every one on the record.", milestoneLine(60))
    }
}
