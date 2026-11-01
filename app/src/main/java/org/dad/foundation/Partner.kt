package org.dad.foundation

import java.util.Date

// Partner portal (PRD P-*, EM-*, §7). Pure value types and display rules live here so role
// gating, the "verified or hidden, never estimated" figure rule (§7.3), <5 suppression (EM-3) and
// last-updated rendering are unit-testable without a network or UI — the Kotlin counterpart of
// the iOS Phase F partner domain.

// MARK: - Role (§7.2, P-4/P-5)

/** A partner user's role. ADMIN manages users/invites/profile/exports; VIEWER is read-only.
 *  Partner accounts never donate (P-5) — enforced by the portal having no giving surface. */
enum class PartnerRole {
    ADMIN, VIEWER;

    companion object {
        /** Decode defensively — an unknown future role is the least-privileged VIEWER. */
        fun from(raw: String): PartnerRole = if (raw.uppercase() == "ADMIN") ADMIN else VIEWER
    }

    val canManageUsers: Boolean get() = this == ADMIN
    val canEditProfile: Boolean get() = this == ADMIN
    val canExport: Boolean get() = this == ADMIN
}

/** Thousands-grouped count ("12,400") to match the grouped money figures. */
internal fun formatCount(value: Long): String = String.format(java.util.Locale.US, "%,d", value)

/** Invite-email shape check (shared with iOS): trimmed, at most 254 characters, `local@domain`
 *  with a non-empty local part and a domain of at least two non-empty dot-separated labels, no
 *  whitespace. The server remains the authority. */
fun isPlausibleEmail(value: String): Boolean {
    val email = value.trim()
    if (email.isEmpty() || email.length > 254 || email.any { it.isWhitespace() }) return false
    val parts = email.split('@')
    if (parts.size != 2 || parts[0].isEmpty()) return false
    val labels = parts[1].split('.')
    return labels.size >= 2 && labels.all { it.isNotEmpty() }
}

// MARK: - Verified figures (§7.3 — hidden, never estimated)

/** A money figure shown ONLY when the server marks it verified; unverified is hidden (§7.3). */
data class VerifiedMoney(val amountMinor: Long, val verified: Boolean) {
    val label: String? get() = if (verified) formatMinorUsd(amountMinor) else null
}

/** A count figure shown only when verified. */
data class VerifiedCount(val value: Long, val verified: Boolean) {
    val label: String? get() = if (verified) formatCount(value) else null
}

// MARK: - Overview (P-10, §7.3)

/**
 * The partner Overview tiles. Each money/count pairs its value with a verified flag; the UI
 * renders it only when verified. employeesEnrolled/enrolledPercent are nullable because they are
 * <5-suppressed server-side (EM-3) — null means "too few to show", not zero.
 */
data class PartnerOverview(
    val organization: String,
    val description: String,
    val role: PartnerRole,
    val companyCode: String,
    val committed: VerifiedMoney,
    val deployed: VerifiedMoney,
    val deployedPercent: Int,
    val peopleReached: VerifiedCount,
    val employeesEnrolled: Long?,
    val workforceSize: Int,
    val enrolledPercent: Int?,
    val updatedAt: Date?,
    val hasLogo: Boolean,
    /** The calling partner user's own id (contract 2.3.0 overview `user_id`); null when absent. */
    val userId: String? = null,
) {
    /** "42 of 120" or null when suppressed (<5). Never shows a suppressed count. */
    val enrolledLabel: String? get() = employeesEnrolled?.let { "$it of $workforceSize" }

    /** The "Verified by DAD" assurance line with the as-of date (§11); null when nothing verified yet. */
    val verifiedLine: String?
        get() = updatedAt?.let {
            val df = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
            "Verified by DAD · as of ${df.format(it)}"
        }
}

// MARK: - Our People rollup (P-13, EM-3 suppression)

/** The workforce rollup. When [suppressed] the group is under the <5 threshold and the UI must
 *  show the suppression notice instead of any figure (EM-3). */
data class PartnerPeople(
    val enrolled: Long,
    val workforceSize: Int,
    val givenMinor: Long,
    val matchedMinor: Long,
    val avgStreakDays: Long,
    val volunteerMinutes: Long,
    val teams: Long,
    val suppressed: Boolean,
) {
    val givenLabel: String? get() = if (suppressed) null else formatMinorUsd(givenMinor)
    val matchedLabel: String? get() = if (suppressed) null else formatMinorUsd(matchedMinor)
    val suppressionNotice: String?
        get() = if (suppressed) "Fewer than 5 people have enrolled, so individual figures are hidden." else null
}

// MARK: - Initiatives (P-11)

enum class InitiativeStatus {
    ACTIVE, COMPLETE;
    companion object { fun from(raw: String): InitiativeStatus = if (raw.uppercase() == "COMPLETE") COMPLETE else ACTIVE }
    val label: String get() = if (this == ACTIVE) "Active" else "Complete"
}

/** One partner initiative tile (P-11), at most three headline metrics (§7.2). */
data class PartnerInitiative(
    val id: String,
    val title: String,
    val summary: String,
    val status: InitiativeStatus,
    val region: String,
    val committedMinor: Long,
    val deployedMinor: Long,
    val metrics: List<PartnerMetric>,
) {
    val headlineMetrics: List<PartnerMetric> get() = metrics.take(3)
    val committedLabel: String get() = formatMinorUsd(committedMinor)
    val deployedLabel: String get() = formatMinorUsd(deployedMinor)
}

data class PartnerMetric(val label: String, val value: Long, val unit: Unit) {
    enum class Unit {
        USD_CENTS, PEOPLE, INITIATIVES, PARTNERS;
        companion object {
            fun from(raw: String): Unit = when (raw.uppercase()) {
                "USD_CENTS" -> USD_CENTS; "INITIATIVES" -> INITIATIVES; "PARTNERS" -> PARTNERS; else -> PEOPLE
            }
        }
    }
    val valueLabel: String get() = if (unit == Unit.USD_CENTS) formatMinorUsd(value) else formatCount(value)
}

// MARK: - Users (P-13 management)

data class PartnerUser(val id: String, val email: String, val name: String, val role: PartnerRole)

// MARK: - Spend (P-12, totals only)

data class PartnerSpendCategory(val category: String, val amountMinor: Long) {
    val amountLabel: String get() = formatMinorUsd(amountMinor)
}
