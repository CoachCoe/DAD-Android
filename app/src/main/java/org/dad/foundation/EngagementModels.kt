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

// Impact/engagement view model + the service seam. The model owns an explicit load/ready/failed
// state machine driven through [EngagementService], so the impact home, streak, rankings and
// spend drill-in are unit-tested without a network or UI — the Kotlin counterpart of the iOS
// Phase D engagement model.

/**
 * Everything the impact home needs in one load. Grouping the reads lets the model expose a single
 * ready/failed phase instead of several independent spinners. Nullable [streak] follows the
 * contract rule: a null figure is "not currently known", never shown as zero.
 */
data class EngagementSnapshot(
    val today: TodayInitiative,
    val impact: ImpactTotals,
    val streak: Streak?,
    val ranking: RankingStanding,
    val spend: InitiativeSpend,
)

/**
 * The seam the engagement model calls. A conformer maps to the generated client (WireAPI) or to a
 * preview/test double; all value types so the model stays testable.
 */
interface EngagementService {
    suspend fun snapshot(): EngagementSnapshot

    /** Opt into or out of public rankings (K-2). Opting out yields an all-null standing. */
    suspend fun setRankingOptIn(optIn: Boolean): RankingStanding
}

/** Explicit phase so the UI never has to infer "is the load in flight". */
sealed interface EngagementPhase {
    data object Loading : EngagementPhase
    data object Ready : EngagementPhase
    data class Failed(val message: String) : EngagementPhase
}

/**
 * Drives the impact home. One load fetches the snapshot; the rankings toggle replaces the ranking
 * in place so the card re-renders to the opt-in prompt or the restored position.
 */
class EngagementModel(
    private val service: EngagementService,
) : ViewModel() {
    var phase: EngagementPhase by mutableStateOf(EngagementPhase.Loading)
        private set
    var snapshot: EngagementSnapshot? by mutableStateOf(null)
        private set

    fun load() {
        phase = EngagementPhase.Loading
        viewModelScope.launch {
            try {
                snapshot = service.snapshot()
                phase = EngagementPhase.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                phase = EngagementPhase.Failed(message(error))
            }
        }
    }

    /** Toggle rankings visibility (K-2); on success the snapshot's ranking is replaced in place. */
    fun setRankingOptIn(optIn: Boolean) {
        val current = snapshot ?: return
        viewModelScope.launch {
            try {
                val ranking = service.setRankingOptIn(optIn)
                snapshot = current.copy(ranking = ranking)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                phase = EngagementPhase.Failed(message(error))
            }
        }
    }

    private fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> "Please sign in again."
        else -> "Something went wrong. Please try again."
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

/**
 * In-memory engagement service for previews and Compose UI tests. One known snapshot with a
 * 12-day streak, a $1,240 lifetime total, and an opted-in ranking that the opt-out toggle flips.
 */
class PreviewEngagementService : EngagementService {
    private var optedIn = true

    private fun ranking() =
        if (optedIn) RankingStanding(92, "Austin", 4) else RankingStanding(null, null, null)

    override suspend fun snapshot(): EngagementSnapshot = EngagementSnapshot(
        today = TodayInitiative(
            slug = "park-cleanup",
            title = "Austin Parks Cleanup",
            summary = "Today your \$1 helped fund a neighborhood cleanup.",
            metrics = listOf(
                ImpactMetric("Bags collected", "412"),
                ImpactMetric("Volunteers", "58"),
                ImpactMetric("Parks", "6"),
            ),
        ),
        impact = ImpactTotals(givenMinor = 124_000, initiativesFunded = 3, eventsVolunteered = 2),
        streak = Streak(current = 12, atRisk = false),
        ranking = ranking(),
        spend = InitiativeSpend(
            totalMinor = 500_000,
            categories = listOf(SpendCategory("Food", 300_000), SpendCategory("Housing", 200_000)),
        ),
    )

    override suspend fun setRankingOptIn(optIn: Boolean): RankingStanding {
        optedIn = optIn
        return ranking()
    }
}
