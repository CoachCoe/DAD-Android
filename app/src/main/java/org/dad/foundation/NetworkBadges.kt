package org.dad.foundation

import java.time.OffsetDateTime

// Donor referral network and badges (PRD N-1..N-3, B-1..B-4). The display and eligibility rules
// live here as pure value types so network anonymization, badge earned/greyed rendering (incl. the
// rule text for unearned) and sharing visibility are unit-testable without a network or UI — the
// Kotlin counterpart of the iOS Phase D engagement/badge domain.

// MARK: - Network (N-1..N-3)

/**
 * The next referral tier the donor has not yet reached (N-2). Null at the network level once every
 * tier is reached.
 */
data class NetworkMilestone(
    val label: String,
    val target: Long,
    val remaining: Long,
) {
    /** "2 more to Circle of 10", or "1 more to …" for the singular. */
    val progressLabel: String
        get() = if (remaining == 1L) "1 more to $label" else "$remaining more to $label"
}

/** One opted-in person the donor brought in (N-3): first name + last initial, as they chose it. */
data class NetworkInvitee(
    val name: String,
    val active: Boolean,
) {
    /** "giving" / "paused": state in text (N-3). */
    val statusLabel: String get() = if (active) "giving" else "paused"
}

/**
 * The donor's referral-network view (N-1..N-3). [invitees] are only the people who opted in to
 * being named; [anonymousActive] are active referrals who did NOT opt in — counted in the totals
 * but never listed. The UI must never derive a name for an anonymous referral.
 */
data class NetworkView(
    val activeCount: Long,
    val annualizedMinor: Long,
    val nextMilestone: NetworkMilestone?,
    val invitees: List<NetworkInvitee>,
    val anonymousActive: Long,
) {
    /** "12 people giving", singular-aware (N-1). */
    val activeLabel: String
        get() = if (activeCount == 1L) "1 person giving" else "$activeCount people giving"

    /** Combined annualized giving across the network, e.g. "$3,480/yr" (N-1). */
    val annualizedLabel: String get() = "${formatMinorUsd(annualizedMinor)}/yr"

    /** Only the opted-in invitees are listable (N-3); anonymous referrals are never named. */
    val namedInvitees: List<NetworkInvitee> get() = invitees

    /**
     * How many active referrals are represented only as a count, never a name. This is the gap
     * between the active total and the opted-in active names — so the UI can show
     * "and N more giving anonymously" without ever inventing a name.
     */
    val anonymousActiveCount: Long get() = anonymousActive

    /** The "and N more giving anonymously" line, or null when everyone active opted in. */
    val anonymousLabel: String?
        get() = when {
            anonymousActive <= 0L -> null
            anonymousActive == 1L -> "and 1 more giving anonymously"
            else -> "and $anonymousActive more giving anonymously"
        }

    /** "2 people choose to stay anonymous", or null when everyone active opted in. */
    val anonymousStayingLabel: String?
        get() = when {
            anonymousActive <= 0L -> null
            anonymousActive == 1L -> "1 person chooses to stay anonymous"
            else -> "$anonymousActive people choose to stay anonymous"
        }

    /** True once every referral tier is reached (no next milestone). */
    val allTiersReached: Boolean get() = nextMilestone == null
}

// MARK: - Badges (B-1..B-4)

/**
 * One badge in the 24-badge grid. [earned] drives earned-in-color vs greyed; the [rule] text is
 * shown on an UNEARNED badge (B-1) so the state is conveyed in text, never by colour alone (a11y).
 * [perk] is the unlock the badge confers (B-4: Founding Donor / 1,000 Days / Team Captain).
 */
data class Badge(
    val code: String,
    val title: String,
    val rule: String,
    val perk: String,
    val earned: Boolean,
    val earnedAt: OffsetDateTime?,
    /** The city a city-ranked badge is for (`Top 50 in Chicago`), null for every other badge. */
    val city: String? = null,
    /** The server's earned-state title (e.g. `Top 50 in Chicago`); blank or missing falls back to [title]. */
    val displayTitle: String = "",
) {
    /** The title shown: [displayTitle] once earned (falling back to [title] when blank), the generic [title] while locked. */
    val shownTitle: String get() = if (earned && displayTitle.isNotBlank()) displayTitle else title

    /**
     * The accessibility label — state is in TEXT so a screen reader (and a colour-blind user)
     * knows earned vs locked without relying on colour (B-1, WCAG). Earned: the title + "earned".
     * Locked: the title + "locked" + how to earn it.
     */
    val accessibilityLabel: String
        get() = if (earned) "$shownTitle, earned" else "$title, locked. $rule"

    /** The secondary line under the title: the rule when locked, the perk when earned (the rule again for a badge with no perk). */
    val detailLine: String get() = if (earned) perk.ifBlank { rule } else rule

    /** Whether this badge's perk is active (B-4 perk gating): only once earned. */
    val perkActive: Boolean get() = earned && perk.isNotBlank()

    /** Whether this earned badge unlocks invite-only events (E-5), keyed on the server's badge code. */
    val unlocksInviteOnlyEvents: Boolean get() = earned && code in PERK_GATING_CODES

    companion object {
        /** Server badge codes that confer a perk: Founding Donor, 1,000 Days, Team Captain (B-4). */
        val PERK_GATING_CODES: Set<String> = setOf("F", "1K", "TC")

        /**
         * Perk copy for the server's perk key (PRD B-4). The key set is open: an unknown, non-empty key
         * still says the badge carries a perk rather than hiding it, and an empty key means no perk.
         */
        fun perkCopy(key: String): String = when (key) {
            "" -> ""
            "FOUNDING_EVENTS" -> "Invite-only founding events"
            "MILESTONE_EVENTS" -> "Invite-only milestone events"
            "TEAM_CAPTAIN" -> "Invite-only events, a DAD merch item, 48-hour early event booking and opt-in name listing on initiative pages"
            else -> "Unlocks a member perk"
        }
    }
}

/**
 * The full badge catalogue with per-donor earned state (B-1..B-4). Permanence (B-3) is a server
 * fact — an earned badge stays earned — so the grid simply renders whatever the catalogue returns;
 * there is no client-side expiry.
 */
data class BadgeGrid(
    val badges: List<Badge>,
) {
    val earned: List<Badge> get() = badges.filter { it.earned }
    val locked: List<Badge> get() = badges.filter { !it.earned }

    /** "7 of 24 earned" — the headline count (B-2). */
    val summaryLabel: String get() = "${earned.size} of ${badges.size} earned"

    /** True when the donor holds any perk-gating badge (F, 1K, TC), i.e. invite-only events are open to them. */
    val hasInviteOnlyAccess: Boolean get() = badges.any { it.unlocksInviteOnlyEvents }

    /** The active perks the donor has unlocked (B-4), for the perk display. */
    val activePerks: List<String> get() = earned.filter { it.perkActive }.map { it.perk }
}

// MARK: - Sharing visibility (N opt-in / A-5)

/** Longest public name the server accepts (`saveSharing`: 1..80). */
const val MAX_PUBLIC_NAME = 80

/**
 * The donor's public sharing state. A null [publicName] means the donor has NOT opted into being
 * named — they appear anonymously in others' networks, and have no referral link yet. Choosing a
 * public name is what mints the [link]/[irlLink] (the server returns them together).
 */
data class SharingState(
    val publicName: String?,
    val link: String?,
    val irlLink: String?,
) {
    /** True once the donor opted in to a public name (and therefore has share links). */
    val isPublic: Boolean get() = !publicName.isNullOrBlank()

    /** The share link, only when the donor opted in. */
    val shareLink: String? get() = if (isPublic) link else null

    /** The in-person (QR) referral link, only when the donor opted in. */
    val inPersonLink: String? get() = if (isPublic) irlLink else null

    /** The visibility line, e.g. "Shown as Alex C." or "Giving anonymously". */
    val visibilityLabel: String
        get() = publicName?.takeIf { it.isNotBlank() }?.let { "Shown as $it" } ?: "Giving anonymously"
}

// MARK: - Invite (R-1..R-4)

/** One referral's first gift. It carries nothing about the referred donor; [kind] is an open string. */
data class Conversion(val convertedAt: OffsetDateTime, val kind: String) {
    /** "Link" / "In person"; a kind a newer server adds is shown neutrally rather than dropped. */
    val kindLabel: String get() = when (kind) {
        "LINK" -> "Link"
        "IRL" -> "In person"
        else -> "Invite"
    }
}

/**
 * The donor's invite: the referral [code] (parsed from the sharing link's `/r/<code>` path) plus the
 * counts and recent first gifts. A null count or [conversions] means the read failed: unknown, not zero.
 * Links are always built from the app's configured public origin, never taken from the server.
 */
data class ReferralInfo(
    val code: String,
    val linkCount: Long?,
    val irlCount: Long?,
    val conversions: List<Conversion>?,
    val hasMoreConversions: Boolean = false,
) {
    /** People who have given through the donor's invite, or null when either count is unknown. */
    val totalConverted: Long? get() = if (linkCount != null && irlCount != null) linkCount + irlCount else null

    /** The text that goes out with the link. */
    fun shareMessage(publicOrigin: String): String {
        val base = "Join me on Dollar A Day — give \$1 a day to local causes."
        return link(publicOrigin)?.let { "$base $it" } ?: base
    }

    fun link(publicOrigin: String): String? = Referral.inviteLink(publicOrigin, code)
    fun irlLink(publicOrigin: String): String? = Referral.inviteLink(publicOrigin, code, inPerson = true)
}
