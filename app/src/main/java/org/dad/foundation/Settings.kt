package org.dad.foundation

// Account & settings (PRD §6.11, §6.12). Pure value types and the toggle/grouping rules live
// here so the preferences block, its optimistic version, and the delete-account confirmation gate
// are unit-testable without a network or UI — the same "pure core, seam for I/O" shape as the
// other phases.

// MARK: - Profile summary (read-only header)

/**
 * The account header the settings screen shows. [accountRole] is fixed at creation and immutable
 * (D-1), so it is display-only. [zip] drives the streak's local-day rollover (§10), so it is the
 * one profile field settings lets the donor edit here.
 */
data class ProfileSummary(
    val email: String,
    val accountRole: String,
    val zip: String?,
    val city: String?,
    val phone: String?,
) {
    val zipLabel: String get() = zip?.takeIf { it.isNotBlank() } ?: "Not set"
}

// MARK: - Notification + visibility preferences (§6.11)

/**
 * The donor's preference block. It carries the optimistic-concurrency [version] it was read at;
 * saving sends that version so a stale overwrite is rejected server-side. Email toggles, the push
 * category toggles, and the visibility/volunteer switches are all here. A null gift-processed /
 * lapsed toggle is treated as its default so an older server payload still round-trips.
 */
data class Preferences(
    val version: Int,
    val referralEmails: Boolean,
    val marketingEmails: Boolean,
    val pushReferralConverted: Boolean,
    val pushStreakAtRisk: Boolean,
    val pushStreakMilestone: Boolean,
    val pushStreakLapsed: Boolean,
    val pushBadgeEarned: Boolean,
    val pushEventNearby: Boolean,
    val pushEventReminder: Boolean,
    val pushEventPromoted: Boolean,
    val pushGiftProcessed: Boolean,
    val rankingsOptedOut: Boolean,
    val networkVisible: Boolean,
    val volunteer: Boolean,
) {
    /** A UI-facing toggle: its label, current value, and a copy producing the updated block. */
    data class Toggle(val key: String, val label: String, val value: Boolean)

    /** The push-notification category toggles (§6.11), in display order. Payment-failure emails
     *  are deliberately absent — they are non-toggleable server-side. */
    val pushToggles: List<Toggle>
        get() = listOf(
            Toggle("push_referral_converted", "Someone joined through you", pushReferralConverted),
            Toggle("push_streak_at_risk", "Streak at risk", pushStreakAtRisk),
            Toggle("push_streak_milestone", "Streak milestone", pushStreakMilestone),
            Toggle("push_streak_lapsed", "Streak lapsed", pushStreakLapsed),
            Toggle("push_badge_earned", "Badge earned", pushBadgeEarned),
            Toggle("push_event_nearby", "Events nearby", pushEventNearby),
            Toggle("push_event_reminder", "Event reminders", pushEventReminder),
            Toggle("push_event_promoted", "Event promoted", pushEventPromoted),
            Toggle("push_gift_processed", "Gift processed", pushGiftProcessed),
        )

    /** The email toggles (§6.11). */
    val emailToggles: List<Toggle>
        get() = listOf(
            Toggle("referral_emails", "Referral emails", referralEmails),
            Toggle("marketing_emails", "Marketing emails", marketingEmails),
        )

    /** The privacy/participation switches. rankingsOptedOut is inverted for display ("Show my
     *  ranking" = NOT opted out) so the UI reads naturally. */
    val showRanking: Boolean get() = !rankingsOptedOut

    /** Produce an updated copy with one toggle flipped by key. Unknown keys return this unchanged. */
    fun with(key: String, value: Boolean): Preferences = when (key) {
        "referral_emails" -> copy(referralEmails = value)
        "marketing_emails" -> copy(marketingEmails = value)
        "push_referral_converted" -> copy(pushReferralConverted = value)
        "push_streak_at_risk" -> copy(pushStreakAtRisk = value)
        "push_streak_milestone" -> copy(pushStreakMilestone = value)
        "push_streak_lapsed" -> copy(pushStreakLapsed = value)
        "push_badge_earned" -> copy(pushBadgeEarned = value)
        "push_event_nearby" -> copy(pushEventNearby = value)
        "push_event_reminder" -> copy(pushEventReminder = value)
        "push_event_promoted" -> copy(pushEventPromoted = value)
        "push_gift_processed" -> copy(pushGiftProcessed = value)
        "rankings_opted_out" -> copy(rankingsOptedOut = value)
        "network_visible" -> copy(networkVisible = value)
        "volunteer" -> copy(volunteer = value)
        else -> this
    }
}

// MARK: - Delete account (store-mandated, §6.12)

/**
 * The delete-account flow gate. Deletion is irreversible (backend removes personal data, keeps
 * legally-required gift records), so the UI requires an explicit confirm step; the donor must type
 * the exact confirmation word before the action is enabled. Pure so the gate is testable.
 */
data class DeleteAccountGate(val typed: String) {
    companion object { const val CONFIRM_WORD = "DELETE" }

    /** True only when the donor typed the exact confirmation word (case-sensitive). */
    val isConfirmed: Boolean get() = typed == CONFIRM_WORD

    val helpText: String
        get() = "This permanently deletes your personal data. Your required gift records are kept. " +
            "Type ${CONFIRM_WORD} to confirm."
}
