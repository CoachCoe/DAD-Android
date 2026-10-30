package org.dad.foundation

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

// Donor impact, streak, initiatives and rankings (PRD A-*, S-*, I-*, K-*). Pure value types and
// the display / eligibility rules live here so streak-bar fill, milestone math, local-midnight
// rollover and rankings opt-out are unit-testable without a network or UI — the Kotlin
// counterpart of the iOS Phase D engagement domain.

/** Format USD minor units (cents) for display: "$1,240" or "$12.34". Long-safe for large totals. */
internal fun formatMinorUsd(minor: Long): String {
    if (minor % 100L == 0L) return String.format(Locale.US, "$%,d", minor / 100L)
    return String.format(Locale.US, "$%,.2f", minor / 100.0)
}

// MARK: - Milestones (S-3)

/** The streak milestone ladder from PRD §2 (7/30/100/365/500/1000/1095/1460, then +365). */
object Milestones {
    val ladder: List<Int> = listOf(7, 30, 100, 365, 500, 1000, 1095, 1460)

    /** The next milestone strictly greater than [days]; after the fixed ladder, every +365. */
    fun next(days: Int): Int {
        ladder.firstOrNull { it > days }?.let { return it }
        // Beyond the ladder: the next multiple of 365 strictly greater than days.
        val base = ladder.last()
        var target = base
        while (target <= days) target += 365
        return target
    }

    /** The highest milestone reached at [days] (0 if none yet). */
    fun lastReached(days: Int): Int {
        if (days >= ladder.last()) {
            // Beyond the fixed ladder: the highest 1460 + k*365 that does not exceed days.
            var reached = ladder.last()
            while (reached + 365 <= days) reached += 365
            return reached
        }
        return ladder.lastOrNull { it <= days } ?: 0
    }
}

// MARK: - Streak (S-1..S-4)

/**
 * The donor's giving streak as the home renders it. [current] is the day count; [atRisk] drives the
 * "give today to keep your streak" nudge (grace day). The 30-day bar shows the trailing window.
 */
data class Streak(
    val current: Int,
    val atRisk: Boolean,
) {
    /** Segments filled in the 30-day bar (S-2): the trailing window, capped at 30. */
    val barFilled: Int get() = current.coerceIn(0, 30)

    /** The next milestone to aim for (S-3). */
    val nextMilestone: Int get() = Milestones.next(current)

    /** Days remaining to the next milestone. */
    val daysToNext: Int get() = (nextMilestone - current).coerceAtLeast(0)

    /** The highest milestone already reached. */
    val lastMilestone: Int get() = Milestones.lastReached(current)

    /** The headline count line, e.g. "12-day streak". */
    val countLabel: String get() = if (current == 1) "1-day streak" else "$current-day streak"

    /** The milestone progress line, e.g. "18 days to your 30-day milestone". */
    val progressLabel: String
        get() = if (daysToNext == 1) "1 day to your $nextMilestone-day milestone"
        else "$daysToNext days to your $nextMilestone-day milestone"
}

// MARK: - Local-midnight rollover by ZIP time zone (NFR §10)

/**
 * The streak rolls over at the donor's LOCAL midnight (their ZIP's time zone), not UTC and not the
 * device zone. This is a pure function of (server's reference instant, the donor's zone) so the
 * "has a new local day begun since the server computed the streak?" decision is testable.
 */
object StreakRollover {
    /** The local calendar day (as an epoch-day number) for [instant] in [zone]. */
    fun localDay(instant: Instant, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(instant, zone).toLocalDate().toEpochDay()

    /**
     * True when [now] falls on a later local day than [computedAt] in the donor's [zone] — i.e. the
     * streak figure is stale and "give today" applies to a fresh day. Equal days = same day, not
     * stale.
     */
    fun hasRolledOver(computedAt: Instant, now: Instant, zone: ZoneId): Boolean =
        localDay(now, zone) > localDay(computedAt, zone)
}

// MARK: - Impact totals (I-1..I-3)

/**
 * Lifetime impact. A null [givenMinor] means "not currently known" — the UI must render it as "--",
 * never as $0 (the contract's explicit rule).
 */
data class ImpactTotals(
    val givenMinor: Long?,
    val initiativesFunded: Int,
    val eventsVolunteered: Int,
) {
    /** "$1,240" or "--" when unknown — never "$0" for an unknown figure. */
    val givenLabel: String get() = givenMinor?.let { formatMinorUsd(it) } ?: "--"
}

// MARK: - Rankings (K-1..K-3), opt-out hides self

/**
 * National/city standing. Opting out yields an all-null standing so no position is ever shown, and
 * the UI must never render a name list.
 */
data class RankingStanding(
    val nationalPercentile: Int?,
    val city: String?,
    val cityRank: Int?,
) {
    val isOptedOut: Boolean get() = nationalPercentile == null && city == null && cityRank == null

    /** "Top 8% nationally" (from the 92nd percentile), or null when opted out / unknown. */
    val nationalLabel: String? get() = nationalPercentile?.let { "Top ${100 - it}% nationally" }

    /** "#4 in Austin", or null when opted out / unknown. */
    val cityLabel: String? get() {
        val c = city ?: return null
        val rank = cityRank ?: return null
        return "#$rank in $c"
    }
}

// MARK: - Today's initiative rotation (A-1..A-4)

/** One headline metric on the rotation card, e.g. ("Meals served", "1,240"). */
data class ImpactMetric(val label: String, val value: String)

/**
 * The rotation-selected "Today your $1 funded" card (A-1). Up to four headline metrics (A-2); the
 * domain caps the count so the UI never over-renders.
 */
data class TodayInitiative(
    val slug: String,
    val title: String,
    val summary: String,
    val metrics: List<ImpactMetric>,
) {
    /** At most four headline metrics (A-2). */
    val headlineMetrics: List<ImpactMetric> get() = metrics.take(4)
}

// MARK: - Spend-by-category (A-3), totals only

/** One spend category line (totals only — never per-transaction line items, PRD §5.2). */
data class SpendCategory(val category: String, val amountMinor: Long) {
    val amountLabel: String get() = formatMinorUsd(amountMinor)
}

/** The spend-by-category drill-in: totals by category (A-3), never a line-item ledger. */
data class InitiativeSpend(
    val totalMinor: Long,
    val categories: List<SpendCategory>,
) {
    val totalLabel: String get() = formatMinorUsd(totalMinor)
}
