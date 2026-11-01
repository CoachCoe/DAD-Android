package org.dad.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

// Engagement view models + the service seam (PRD A-3, I-*, K-*, N-*, R-*, B-*). The models own an
// explicit load/ready/failed state, so impact, badges, rankings, network and the invite are
// unit-tested without a network or UI. The Kotlin counterpart of the iOS wiring-P5 model.

/**
 * Everything the engagement screen shows. Each section is null when the server did not give it (the
 * read failed, or the figure is not computed yet): the screen says "can't be shown right now" for that
 * section instead of drawing a zero, an empty grid or a "not ranked" claim it cannot back.
 */
data class EngagementSnapshot(
    val impact: ImpactTotals?,
    val badges: BadgeGrid?,
    val ranking: RankingStanding?,
    val network: NetworkView?,
    /** Null when there is no usable invite code; see [needsPublicName] for the one case that is the donor's to fix. */
    val referral: ReferralInfo?,
    /** The donor has not chosen a public name yet, so the server issues no invite link. */
    val needsPublicName: Boolean = false,
) {
    fun replacing(ranking: RankingStanding?) = copy(ranking = ranking)
}

/**
 * The seam the engagement model calls. A conformer maps to the wire (WireEngagement) or to a
 * preview/test double; all value types so the models stay testable.
 */
interface EngagementService {
    suspend fun snapshot(): EngagementSnapshot

    /** Opt into or out of the public rankings (K-2). Opting out yields [RankingStanding.optedOut]. */
    suspend fun setRankingOptIn(optIn: Boolean): RankingStanding

    /** Choose the public name the invite link and IRL code are issued for (R-1). Throws [AccountFailure.InvalidInput] for a name the server refuses. */
    suspend fun choosePublicName(name: String)

    /** Spend by category for one initiative (A-3); totals only. */
    suspend fun initiativeSpend(id: String): InitiativeSpend
}

/** Explicit phase so the UI never has to infer "is the load in flight". */
sealed interface EngagementPhase {
    data object Loading : EngagementPhase
    data object Ready : EngagementPhase
    data class Failed(val message: String) : EngagementPhase
}

/**
 * Drives the engagement screen. One load fetches the snapshot; a refresh that fails or is cancelled keeps
 * the content already on screen. The rankings toggle flips at once ([displayedRanking]) and reverts with an
 * inline notice if the save fails.
 */
class EngagementModel(private val service: EngagementService) : ViewModel() {
    var phase: EngagementPhase by mutableStateOf(EngagementPhase.Loading)
        private set
    var snapshot: EngagementSnapshot? by mutableStateOf(null)
        private set
    /** A failed ranking toggle shows inline; the loaded screen and the prior standing stay put. */
    var actionError: String? by mutableStateOf(null)
        private set
    /** Shown above stale content when a refresh failed. */
    var refreshNotice: String? by mutableStateOf(null)
        private set
    /** The session ended (a refresh was refused twice): the shell must route to sign-in. */
    var signedOut: Boolean by mutableStateOf(false)
        private set
    /** The rankings choice being saved; clearing it reverts the card to the standing the server last confirmed. */
    var pendingOptIn: Boolean? by mutableStateOf(null)
        private set
    var nameError: String? by mutableStateOf(null)
        private set
    var isSavingName: Boolean by mutableStateOf(false)
        private set

    private var generation = 0
    private var job: Job? = null

    /** What the rankings card shows right now: the confirmed standing, or the donor's pending choice. */
    val displayedRanking: RankingStanding?
        get() {
            val ranking = snapshot?.ranking ?: return null
            return when (pendingOptIn) {
                null -> ranking
                false -> RankingStanding.optedOut()
                true -> RankingStanding(ranking.nationalPercentile, ranking.city, ranking.cityRank, optedOut = false)
            }
        }

    fun load() {
        job?.cancel()
        generation++
        val current = generation
        val prior = phase
        refreshNotice = null
        if (snapshot == null) phase = EngagementPhase.Loading
        job = viewModelScope.launch {
            try {
                val value = service.snapshot()
                if (current != generation) return@launch
                snapshot = value
                phase = EngagementPhase.Ready
            } catch (cancelled: CancellationException) {
                if (current == generation) phase = prior
                throw cancelled
            } catch (error: Exception) {
                if (current != generation) return@launch
                if (error is AccountFailure.SignedOut) signedOut = true
                if (snapshot != null) { phase = EngagementPhase.Ready; refreshNotice = homeMessage(error) }
                else phase = EngagementPhase.Failed(homeMessage(error))
            }
        }
    }

    /** Toggle rankings visibility (K-2). A second tap while one is saving is ignored. */
    fun setRankingOptIn(optIn: Boolean) {
        if (snapshot == null || pendingOptIn != null) return
        actionError = null
        pendingOptIn = optIn
        viewModelScope.launch {
            try {
                val ranking = service.setRankingOptIn(optIn)
                snapshot = snapshot?.replacing(ranking)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is AccountFailure.SignedOut) signedOut = true
                actionError = homeMessage(error)
            } finally {
                pendingOptIn = null
            }
        }
    }

    /** Choose the public name behind the invite link (R-1), then reload so the code and link appear. */
    fun choosePublicName(raw: String) {
        val name = raw.trim()
        if (isSavingName) return
        if (name.length !in 1..MAX_PUBLIC_NAME) { nameError = "Enter a name between 1 and $MAX_PUBLIC_NAME characters."; return }
        isSavingName = true
        nameError = null
        viewModelScope.launch {
            try {
                service.choosePublicName(name)
            } catch (cancelled: CancellationException) {
                isSavingName = false
                throw cancelled
            } catch (error: Exception) {
                if (error is AccountFailure.SignedOut) signedOut = true
                nameError = when (error) {
                    is AccountFailure.InvalidInput -> "That name can’t be used. Try another."
                    is AccountFailure.Forbidden -> "Invite links aren’t available for this account."
                    else -> homeMessage(error)
                }
                isSavingName = false
                return@launch
            }
            isSavingName = false
            load()
        }
    }

    companion object {
        fun factory(service: EngagementService): ViewModelProvider.Factory = viewModelFactory {
            initializer { EngagementModel(service) }
        }

        /** Preview/UITest factory wiring the in-memory [PreviewEngagementService]. */
        fun previewFactory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { EngagementModel(PreviewEngagementService()) }
        }
    }
}

/** Spend by category for one initiative (A-3), opened from today's initiative card. Totals only. */
class InitiativeSpendModel(private val service: EngagementService, private val initiativeId: String) : ViewModel() {
    sealed interface Phase {
        data object Loading : Phase
        data class Ready(val spend: InitiativeSpend) : Phase
        /** The initiative has no published spend (404). */
        data object Unpublished : Phase
        data class Failed(val message: String) : Phase
    }

    var phase: Phase by mutableStateOf(Phase.Loading)
        private set
    var signedOut: Boolean by mutableStateOf(false)
        private set

    fun load() {
        val prior = phase
        if (prior !is Phase.Ready) phase = Phase.Loading
        viewModelScope.launch {
            try {
                phase = Phase.Ready(service.initiativeSpend(initiativeId))
            } catch (cancelled: CancellationException) {
                phase = prior
                throw cancelled
            } catch (error: Exception) {
                if (error is AccountFailure.NotFound) { phase = Phase.Unpublished; return@launch }
                if (error is AccountFailure.SignedOut) signedOut = true
                if (prior !is Phase.Ready) phase = Phase.Failed(homeMessage(error)) else phase = prior
            }
        }
    }

    companion object {
        fun factory(service: EngagementService, initiativeId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { InitiativeSpendModel(service, initiativeId) }
        }
    }
}

/**
 * In-memory engagement service for DEBUG previews. One known snapshot: a 5-person network with 2 anonymous, the
 * 24-badge Appendix A catalogue with 7 earned, an opted-in ranking that the toggle flips, and a donor who starts
 * with no public name (choosing one mints the invite).
 */
class PreviewEngagementService : EngagementService {
    private var optedIn = true
    private var publicName: String? = null

    private fun ranking() = if (optedIn) RankingStanding(2, "Austin", 4) else RankingStanding.optedOut()

    override suspend fun snapshot(): EngagementSnapshot = EngagementSnapshot(
        impact = ImpactTotals(givenMinor = 124_000, initiativesFunded = 3, eventsVolunteered = 2),
        badges = BadgeGrid(previewBadges()),
        ranking = ranking(),
        network = NetworkView(
            activeCount = 5,
            annualizedMinor = 348_000,
            nextMilestone = NetworkMilestone(label = "Brought 12 In", target = 12, remaining = 7),
            invitees = listOf(NetworkInvitee("Alex C.", active = true), NetworkInvitee("Jordan M.", active = true), NetworkInvitee("Sam R.", active = false)),
            anonymousActive = 2,
        ),
        referral = publicName?.let {
            ReferralInfo("preview", 2, 1, listOf(Conversion(OffsetDateTime.parse("2026-10-20T12:00:00Z"), "LINK")))
        },
        needsPublicName = publicName == null,
    )

    override suspend fun setRankingOptIn(optIn: Boolean): RankingStanding {
        optedIn = optIn
        return ranking()
    }

    override suspend fun choosePublicName(name: String) { publicName = name }

    override suspend fun initiativeSpend(id: String) = InitiativeSpend(
        totalMinor = 500_000,
        categories = listOf(SpendCategory("Food", 300_000), SpendCategory("Housing", 200_000)),
    )

    private fun previewBadges(): List<Badge> {
        val earnedAt = OffsetDateTime.parse("2026-06-01T00:00:00Z")
        // Codes are the server catalogue's (DAD-Main badges.go); titles, rules and perks are verbatim from PRD Appendix A (approved 2026-10-23); "—" perks are blank.
        val specs = listOf(
            BadgeSpec("F", "Founding Donor", "Started a monthly gift before January 2027, or joined the website waitlist", "FOUNDING_EVENTS"),
            BadgeSpec("1D", "First Day", "Completed a first gift", ""),
            BadgeSpec("7", "One Week", "7-day streak", ""),
            BadgeSpec("30", "One Month", "30-day streak", ""),
            BadgeSpec("100", "Hundred", "100-day streak", ""),
            BadgeSpec("Y1", "One Year", "365-day streak", ""),
            BadgeSpec("1K", "1,000 Days", "1,000-day streak", "MILESTONE_EVENTS"),
            BadgeSpec("Y3", "Three Years", "1,095-day streak", ""),
            BadgeSpec("CB", "Comeback", "Restarted giving after a lapse and reached 30 days", ""),
            BadgeSpec("UP", "Level Up", "Raised their daily amount", ""),
            BadgeSpec("REF1", "First Referral", "First person they invited completed a gift", ""),
            BadgeSpec("REF3", "Brought 3 In", "3 converted referrals", ""),
            BadgeSpec("REF12", "Brought 12 In", "12 converted referrals", ""),
            BadgeSpec("TC", "Team Captain", "20 converted referrals", "TEAM_CAPTAIN"),
            BadgeSpec("IRL", "IRL", "Someone joined by scanning their IRL code", ""),
            BadgeSpec("V", "Volunteer", "Opted in as a volunteer", ""),
            BadgeSpec("SU", "Showed Up", "First event check-in", ""),
            BadgeSpec("V3", "Volunteer ×3", "3 event check-ins", ""),
            BadgeSpec("V10", "Volunteer ×10", "10 event check-ins", ""),
            BadgeSpec("INIT7", "7 Initiatives", "Funded 7 distinct initiatives", ""),
            BadgeSpec("CITY50", "Top 50 City", "Ranked top 50 in their city", ""),
            BadgeSpec("NAT2", "Top 2%", "National top 2% by streak", ""),
            BadgeSpec("TP", "Team Player", "Joined a company team", ""),
            BadgeSpec("WT", "Winning Team", "Their team finished a month at #1 on the company leaderboard", ""),
        )
        return specs.mapIndexed { index, (code, title, rule, perkKey) ->
            val earned = index < 7
            Badge(code, title, rule, Badge.perkCopy(perkKey), earned, if (earned) earnedAt else null)
        }
    }

    private data class BadgeSpec(val code: String, val title: String, val rule: String, val perkKey: String)
}
