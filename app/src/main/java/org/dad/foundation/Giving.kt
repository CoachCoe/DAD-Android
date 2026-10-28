package org.dad.foundation

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Donor giving core (PRD G-1…G-11, D-5). Pure value types and the amount rule live here so
// the arithmetic and state transitions are unit-testable without a network, an Android
// framework, or UI — the same split the iOS DADFoundation uses.

/**
 * A validated recurring gift amount. The canonical unit is integer USD cents charged
 * monthly; the product rule (G-1/G-2) is a whole number of dollars a day, $1…$30, times 30.
 * The factories are the only place the rule is enforced, so an out-of-range amount cannot
 * be constructed.
 */
@JvmInline
value class GiftAmount private constructor(val dailyDollars: Int) : Comparable<GiftAmount> {
    /** Monthly charge in integer USD cents (dailyDollars × 30 × 100 == dailyDollars × 3000). */
    val monthlyMinor: Int get() = dailyDollars * CENTS_PER_DAILY_DOLLAR

    /** "$5/day" — the headline the set-gift screen shows. */
    val dailyLabel: String get() = "$$dailyDollars/day"

    /** "$150/mo" — the monthly equivalent, derived, never entered directly. */
    val monthlyLabel: String get() = "$${monthlyMinor / 100}/mo"

    override fun compareTo(other: GiftAmount): Int = dailyDollars.compareTo(other.dailyDollars)

    companion object {
        const val MIN_DAILY_DOLLARS = 1
        const val MAX_DAILY_DOLLARS = 30

        /** A daily dollar maps to this many monthly cents: $1/day → $30/month → 3000¢. */
        const val CENTS_PER_DAILY_DOLLAR = 3000

        /**
         * Build from a per-day dollar figure, clamped to the $1…$30 rule. Returns null only
         * for a non-positive input; a value above the ceiling clamps to $30 rather than fail,
         * so a stepper cannot wedge.
         */
        fun ofDailyDollars(dailyDollars: Int): GiftAmount? {
            if (dailyDollars < MIN_DAILY_DOLLARS) return null
            return GiftAmount(minOf(dailyDollars, MAX_DAILY_DOLLARS))
        }

        /**
         * Reconstruct from a monthly-cents figure returned by the API. Accepts only exact
         * multiples of the $1/day step within range; anything else (a legacy amount predating
         * the daily rule) returns null so the caller shows it as a raw figure, not a daily tier.
         */
        fun ofMonthlyMinor(monthlyMinor: Int): GiftAmount? {
            if (monthlyMinor < MIN_DAILY_DOLLARS * CENTS_PER_DAILY_DOLLAR) return null
            if (monthlyMinor > MAX_DAILY_DOLLARS * CENTS_PER_DAILY_DOLLAR) return null
            if (monthlyMinor % CENTS_PER_DAILY_DOLLAR != 0) return null
            return GiftAmount(monthlyMinor / CENTS_PER_DAILY_DOLLAR)
        }
    }
}

/**
 * Format an integer-cents USD figure as "$12.34" / "$150". Whole-dollar amounts drop the
 * decimals; display-only, never used for arithmetic.
 */
fun formatUsdMinor(minor: Int): String {
    if (minor % 100 == 0) return "$${minor / 100}"
    return String.format(Locale.US, "$%.2f", minor / 100.0)
}

/**
 * The recurring gift as the giving screens see it: either none set yet, an active
 * subscription, or one scheduled to stop at period end (the cancel path, G-8/G-9).
 */
sealed interface RecurringState {
    data object None : RecurringState
    data class Active(val subscriptionId: String, val amountMinor: Int, val periodEnd: Date) : RecurringState

    /** Cancelled but still running until [stopsAt] — the streak continues until then. */
    data class Ending(val subscriptionId: String, val amountMinor: Int, val periodEnd: Date, val stopsAt: Date) : RecurringState

    /** The subscription id recurring commands (change amount, cancel) need, when one exists. */
    val subscriptionIdOrNull: String?
        get() = when (this) {
            is None -> null
            is Active -> subscriptionId
            is Ending -> subscriptionId
        }

    val amountMinorOrNull: Int?
        get() = when (this) {
            is None -> null
            is Active -> amountMinor
            is Ending -> amountMinor
        }

    val isEnding: Boolean get() = this is Ending
}

/**
 * Copy for the cancel confirmation (G-8/G-9): cancelling ends the recurring gift at the
 * current period end and the streak lapses after that — stated plainly. Kept inline and
 * testable (no OS dialog), the lesson the iOS side learned the hard way.
 */
object CancelCopy {
    fun confirmation(periodEnd: Date, timeZone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.US): String {
        val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
        formatter.timeZone = timeZone
        val date = formatter.format(periodEnd)
        return "Your gift stays active until $date. After that it won't renew and your streak ends."
    }
}
