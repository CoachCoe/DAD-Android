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
 * 5-person network with 2 anonymous, a next milestone, a 24-badge catalogue with 7 earned, and a
 * donor who starts anonymous (the "choose a public name" opt-in flips them public and mints links).
 */
class PreviewNetworkBadgesService : NetworkBadgesService {
    private var sharing = SharingState(publicName = null, link = null, irlLink = null)

    override suspend fun snapshot(): NetworkBadgesSnapshot = NetworkBadgesSnapshot(
        network = NetworkView(
            activeCount = 5,
            annualizedMinor = 348_000,
            nextMilestone = NetworkMilestone(label = "Circle of 10", target = 10, remaining = 5),
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
        // The 24-badge catalogue (Appendix A): 7 earned, 17 locked — every one carries a rule
        // (for the locked state) and a perk (for the earned/gating state).
        val specs = listOf(
            Triple("FOUNDING_DONOR", "Founding Donor", "Give before January 2027"),
            Triple("FIRST_GIFT", "First Gift", "Make your first gift"),
            Triple("WEEK_ONE", "One Week", "Give 7 days running"),
            Triple("MONTH_ONE", "One Month", "Give 30 days running"),
            Triple("HUNDRED_DAYS", "100 Days", "Give 100 days running"),
            Triple("YEAR_ONE", "One Year", "Give 365 days running"),
            Triple("THOUSAND_DAYS", "1,000 Days", "Give 1,000 days running"),
            Triple("REFERRER", "Referrer", "Bring in your first giver"),
            Triple("CIRCLE_5", "Circle of 5", "Bring in 5 active givers"),
            Triple("CIRCLE_10", "Circle of 10", "Bring in 10 active givers"),
            Triple("TEAM_CAPTAIN", "Team Captain", "Lead a workplace team"),
            Triple("VOLUNTEER", "Volunteer", "Check in to your first event"),
            Triple("VOLUNTEER_5", "Regular Volunteer", "Check in to 5 events"),
            Triple("MATCHED", "Matched", "Have a gift matched by your employer"),
            Triple("EARLY_BIRD", "Early Bird", "Reserve an invite-only event"),
            Triple("CITY_LEADER", "City Leader", "Reach the top 1% in your city"),
            Triple("NATIONAL_TOP", "National Top 1%", "Reach the national top 1%"),
            Triple("STREAK_SAVER", "Streak Saver", "Recover a streak on the grace day"),
            Triple("INITIATIVE_5", "Changemaker", "Fund 5 distinct initiatives"),
            Triple("EVENT_HOST", "Event Host", "Volunteer at a hosted event"),
            Triple("ANNIVERSARY", "Anniversary", "Give on your giving anniversary"),
            Triple("GENEROUS", "Generous", "Give the $30/day maximum"),
            Triple("CONSISTENT", "Consistent", "Give 500 days running"),
            Triple("LEGACY", "Legacy", "Give 1,460 days running"),
        )
        return specs.mapIndexed { index, (code, title, rule) ->
            val earned = index < 7
            Badge(
                code = code,
                title = title,
                rule = rule,
                perk = perkFor(code),
                earned = earned,
                earnedAt = if (earned) earnedAt else null,
            )
        }
    }

    private fun perkFor(code: String): String = when (code) {
        "FOUNDING_DONOR" -> "Founding Donor recognition"
        "THOUSAND_DAYS" -> "1,000 Days recognition"
        "TEAM_CAPTAIN" -> "Team Captain tools"
        else -> "Recognition"
    }
}
