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
    if (minor % 100 == 0) return String.format(Locale.US, "$%,d", minor / 100)
    return String.format(Locale.US, "$%,.2f", minor / 100.0)
}

/**
 * Stripe's subscription status as the app uses it. The contract documents six values, but Stripe
 * can send others (`paused`, `trialing`), so anything unrecognised is [OTHER] and the gift is still
 * shown; a status never makes a response fail to decode. `canceled` and `incomplete_expired` have
 * ended, so they are not represented here: the reader maps them to [RecurringState.None].
 */
enum class SubscriptionStatus {
    ACTIVE, PAST_DUE, UNPAID, INCOMPLETE, OTHER;

    /** A renewal has failed (or is failing): the donor should update the payment method. */
    val needsPaymentAttention: Boolean get() = this == PAST_DUE || this == UNPAID

    companion object {
        fun parse(raw: String?): SubscriptionStatus = when (raw) {
            "active" -> ACTIVE
            "past_due" -> PAST_DUE
            "unpaid" -> UNPAID
            "incomplete" -> INCOMPLETE
            else -> OTHER
        }

        /** Whether [raw] means the subscription has ended (`canceled`, `incomplete_expired`). */
        fun isEnded(raw: String?): Boolean = raw == "canceled" || raw == "incomplete_expired"
    }
}

/**
 * The recurring gift as the giving screens see it: none yet, one the server has not counted yet
 * (checkout finished, webhook pending), an active subscription, or one scheduled to stop at period
 * end (the cancel path, G-8/G-9).
 */
sealed interface RecurringState {
    data object None : RecurringState

    /** The server has a gift in progress but has not counted it yet (`state: preparing`). */
    data object Preparing : RecurringState

    data class Active(
        val subscriptionId: String,
        val amountMinor: Int,
        val periodEnd: Date,
        val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    ) : RecurringState

    /** Cancelled but still running until [stopsAt] — the streak continues until then. */
    data class Ending(
        val subscriptionId: String,
        val amountMinor: Int,
        val periodEnd: Date,
        val stopsAt: Date,
        val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    ) : RecurringState

    /** The subscription id recurring commands (change amount, cancel) need, when one exists. */
    val subscriptionIdOrNull: String?
        get() = when (this) {
            is None, is Preparing -> null
            is Active -> subscriptionId
            is Ending -> subscriptionId
        }

    val amountMinorOrNull: Int?
        get() = when (this) {
            is None, is Preparing -> null
            is Active -> amountMinor
            is Ending -> amountMinor
        }

    val isEnding: Boolean get() = this is Ending
}

/**
 * What the amount endpoint offers for the subscription: the amount the next renewal will charge,
 * when, and a change already on its way (queued but unconfirmed, or confirmed and waiting for its
 * effective date). Minor units are integer USD cents.
 */
data class AmountOffer(
    val subscriptionId: String,
    val nextAmountMinor: Int,
    val effectiveAt: Date,
    val pending: Pending? = null,
) {
    data class Pending(val amountMinor: Int, val previousMinor: Int, val effectiveAt: Date, val confirmed: Boolean)
}

/** Where the hosted card update stands, from `GET /donor/me/payment-method`. */
data class PaymentMethodState(val kind: Kind, val completedAt: Date? = null) {
    enum class Kind {
        NONE, PENDING, OPEN, APPLIED, EXPIRED, CANCELLED;

        companion object {
            /** An unknown value reads as [NONE]: the update is neither claimed nor assumed. */
            fun parse(raw: String?): Kind = entries.firstOrNull { it.name == raw?.uppercase() } ?: NONE
        }
    }

    /** An update is in flight: the donor started it and the server has not finished it. */
    val isInFlight: Boolean get() = kind == Kind.PENDING || kind == Kind.OPEN
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
