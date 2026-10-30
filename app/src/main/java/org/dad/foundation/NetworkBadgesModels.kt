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
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

// Network + badges view model and the service seam. The model owns an explicit load/ready/failed
// state machine driven through [NetworkBadgesService], so the network page and the 24-badge grid
// are unit-tested without a network or UI — the Kotlin counterpart of the iOS Phase D model.

/**
 * Everything the network/badges screen needs in one load. Grouping the reads lets the model expose
 * a single ready/failed phase instead of several independent spinners.
 */
data class NetworkBadgesSnapshot(
    val network: NetworkView,
    val grid: BadgeGrid,
    val sharing: SharingState,
)

/**
 * The seam the model calls. A conformer maps to the generated client (WireAPI) or to a
 * preview/test double; all value types so the model stays testable.
 */
interface NetworkBadgesService {
    suspend fun snapshot(): NetworkBadgesSnapshot

    /**
     * Opt into being named publicly (N opt-in): choosing a public name mints the share links. The
     * server returns the updated sharing state (with [SharingState.link]/[irlLink] populated).
     */
    suspend fun choosePublicName(name: String): SharingState
}

/** Explicit phase so the UI never has to infer "is the load in flight". */
sealed interface NetworkBadgesPhase {
    data object Loading : NetworkBadgesPhase
    data object Ready : NetworkBadgesPhase
    data class Failed(val message: String) : NetworkBadgesPhase
}

/**
 * Drives the network/badges screen. One load fetches the snapshot; choosing a public name replaces
 * the sharing state in place so the card re-renders to show the minted share links.
 */
class NetworkBadgesModel(
    private val service: NetworkBadgesService,
) : ViewModel() {
    var phase: NetworkBadgesPhase by mutableStateOf(NetworkBadgesPhase.Loading)
        private set
    var snapshot: NetworkBadgesSnapshot? by mutableStateOf(null)
        private set
    /** A failed opt-in shows inline and leaves the loaded page on screen. */
    var actionError: String? by mutableStateOf(null)
        private set
    private var choosingName = false

    fun load() {
        phase = NetworkBadgesPhase.Loading
        viewModelScope.launch {
            try {
                snapshot = service.snapshot()
                phase = NetworkBadgesPhase.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                phase = NetworkBadgesPhase.Failed(message(error))
            }
        }
    }

    /** Opt into a public name (N opt-in); on success the snapshot's sharing is replaced in place. */
    fun choosePublicName(name: String) {
        val trimmed = name.trim()
        if (snapshot == null || trimmed.isEmpty() || choosingName) return
        choosingName = true
        actionError = null
        viewModelScope.launch {
            try {
                val sharing = service.choosePublicName(trimmed)
                snapshot = snapshot?.copy(sharing = sharing)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = message(error)
            } finally {
                choosingName = false
            }
        }
    }

    private fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> "Please sign in again."
        else -> "Something went wrong. Please try again."
    }

    companion object {
        fun factory(service: NetworkBadgesService): ViewModelProvider.Factory = viewModelFactory {
            initializer { NetworkBadgesModel(service) }
        }

        /** Preview/UITest factory wiring the in-memory [PreviewNetworkBadgesService]. */
        fun previewFactory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { NetworkBadgesModel(PreviewNetworkBadgesService()) }
        }
    }
}

/**
 * In-memory network/badges service for previews and Compose UI tests. One known snapshot: a
 * 5-person network with 2 anonymous, a next milestone, the 24-badge Appendix A catalogue with 7 earned, and a
 * donor who starts anonymous (the "choose a public name" opt-in flips them public and mints links).
 */
class PreviewNetworkBadgesService : NetworkBadgesService {
    private var sharing = SharingState(publicName = null, link = null, irlLink = null)

    override suspend fun snapshot(): NetworkBadgesSnapshot = NetworkBadgesSnapshot(
        network = NetworkView(
            activeCount = 5,
            annualizedMinor = 348_000,
            nextMilestone = NetworkMilestone(label = "Brought 12 In", target = 12, remaining = 7),
            invitees = listOf(
                NetworkInvitee("Alex C.", active = true),
                NetworkInvitee("Jordan M.", active = true),
                NetworkInvitee("Sam R.", active = false),
            ),
            anonymousActive = 2,
        ),
        grid = BadgeGrid(badges = previewBadges()),
        sharing = sharing,
    )

    override suspend fun choosePublicName(name: String): SharingState {
        sharing = SharingState(
            publicName = name,
            link = "https://dad.org/r/$name",
            irlLink = "https://dad.org/r/$name/irl",
        )
        return sharing
    }

    private fun previewBadges(): List<Badge> {
        val earnedAt = OffsetDateTime.parse("2026-06-01T00:00:00Z")
        // Titles, rules and perks are verbatim from PRD Appendix A (approved 2026-10-23); "—" perks are blank.
        val specs = listOf(
            BadgeSpec("FOUNDING_DONOR", "Founding Donor", "Started a monthly gift before January 2027, or joined the website waitlist", "Founding Donor Dinner and founding events"),
            BadgeSpec("FIRST_DAY", "First Day", "Completed a first gift", ""),
            BadgeSpec("ONE_WEEK", "One Week", "7-day streak", ""),
            BadgeSpec("ONE_MONTH", "One Month", "30-day streak", ""),
            BadgeSpec("HUNDRED", "Hundred", "100-day streak", ""),
            BadgeSpec("ONE_YEAR", "One Year", "365-day streak", ""),
            BadgeSpec("THOUSAND_DAYS", "1,000 Days", "1,000-day streak", "Invite-only milestone events"),
            BadgeSpec("THREE_YEARS", "Three Years", "1,095-day streak", ""),
            BadgeSpec("COMEBACK", "Comeback", "Restarted giving after a lapse and reached 30 days", ""),
            BadgeSpec("LEVEL_UP", "Level Up", "Raised their daily amount", ""),
            BadgeSpec("FIRST_REFERRAL", "First Referral", "First person they invited completed a gift", ""),
            BadgeSpec("BROUGHT_3", "Brought 3 In", "3 converted referrals", ""),
            BadgeSpec("BROUGHT_12", "Brought 12 In", "12 converted referrals", ""),
            BadgeSpec("TEAM_CAPTAIN", "Team Captain", "20 converted referrals", "Invite-only events; DAD merch; reserve event spots 48 hours early; name listed on initiative pages (opt-in)"),
            BadgeSpec("IRL", "IRL", "Someone joined by scanning their IRL code", ""),
            BadgeSpec("VOLUNTEER", "Volunteer", "Opted in as a volunteer", ""),
            BadgeSpec("SHOWED_UP", "Showed Up", "First event check-in", ""),
            BadgeSpec("VOLUNTEER_X3", "Volunteer ×3", "3 event check-ins", ""),
            BadgeSpec("VOLUNTEER_X10", "Volunteer ×10", "10 event check-ins", ""),
            BadgeSpec("SEVEN_INITIATIVES", "7 Initiatives", "Funded 7 distinct initiatives", ""),
            BadgeSpec("TOP_50_CITY", "Top 50 City", "Ranked top 50 in their city", ""),
            BadgeSpec("TOP_2_PERCENT", "Top 2%", "National top 2% by streak", ""),
            BadgeSpec("TEAM_PLAYER", "Team Player", "Joined a company team", ""),
            BadgeSpec("WINNING_TEAM", "Winning Team", "Their team finished a month at #1 on the company leaderboard", ""),
        )
        return specs.mapIndexed { index, (code, title, rule, perk) ->
            val earned = index < 7
            Badge(
                code = code,
                title = title,
                rule = rule,
                perk = perk,
                earned = earned,
                earnedAt = if (earned) earnedAt else null,
            )
        }
    }

    private data class BadgeSpec(val code: String, val title: String, val rule: String, val perk: String)
}
