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
import java.util.Date

// Events view models + the service seam. The models own explicit load/ready/failed state and the
// reserve/cancel/check-in transitions, so they are unit-tested without a network, a camera, or UI
// — the Kotlin counterpart of the iOS Phase E events models.

/** Domain mirror of the contract's CheckInResult so the model/tests never import transport. */
data class CheckInResultValue(val eventId: String, val checkedIn: Boolean, val alreadyCheckedIn: Boolean)

/**
 * The seam the events models call. A conformer maps to the generated client (WireAPI) or a
 * preview/test double; all value types so the models stay testable. The server is authoritative —
 * these calls can throw (401/404/409/422) and the models surface that.
 */
interface EventsService {
    suspend fun events(): List<DonorEvent>
    suspend fun event(id: String): DonorEvent
    suspend fun reserve(eventId: String): ReservationOutcome
    suspend fun cancel(eventId: String)
    suspend fun checkIn(code: String): CheckInResultValue
}

sealed interface EventsPhase {
    data object Loading : EventsPhase
    data object Ready : EventsPhase
    data class Failed(val message: String) : EventsPhase
}

/**
 * Drives the events list and detail. One load fetches the list; reserving/cancelling updates the
 * affected event in place so the list and detail re-render without a full reload.
 */
class EventsModel(private val service: EventsService) : ViewModel() {
    var phase: EventsPhase by mutableStateOf(EventsPhase.Loading)
        private set
    var events: List<DonorEvent> by mutableStateOf(emptyList())
        private set
    /** The last reserve outcome, so the detail screen can present the confirmation (E-4). */
    var lastOutcome: ReservationOutcome? by mutableStateOf(null)
        private set
    /** A transient action error (reserve/cancel 409 etc.) shown inline without losing the list. */
    var actionError: String? by mutableStateOf(null)
        private set
    private val inFlight = mutableSetOf<String>()

    fun load() {
        phase = EventsPhase.Loading
        viewModelScope.launch {
            try {
                events = service.events().sortedBy { it.startsAt } // soonest-first (E-2)
                phase = EventsPhase.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                phase = EventsPhase.Failed(message(error))
            }
        }
    }

    fun reserve(eventId: String) = perform(eventId) {
        lastOutcome = service.reserve(eventId)
        refresh(eventId)
    }

    fun cancel(eventId: String) = perform(eventId) {
        service.cancel(eventId)
        lastOutcome = null
        refresh(eventId)
    }

    /** Runs one reserve/cancel at a time per event so a double tap cannot book or cancel twice. */
    private fun perform(eventId: String, action: suspend () -> Unit) {
        if (!inFlight.add(eventId)) return
        actionError = null
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = message(error)
            } finally {
                inFlight.remove(eventId)
            }
        }
    }

    fun clearOutcome() { lastOutcome = null }

    private suspend fun refresh(eventId: String) {
        // The action already succeeded; a failed re-read just leaves the row as it was until the next load.
        val updated = try { service.event(eventId) } catch (error: CancellationException) { throw error } catch (_: Exception) { return }
        events = events.map { if (it.id == eventId) updated else it }
    }

    private fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> "Please sign in again."
        else -> "Something went wrong. Please try again."
    }

    companion object {
        fun factory(service: EventsService): ViewModelProvider.Factory = viewModelFactory {
            initializer { EventsModel(service) }
        }
        fun previewFactory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { EventsModel(PreviewEventsService()) }
        }
    }
}

/**
 * Drives the QR check-in flow (E-8). The CameraX scanner (a device seam, validated structurally)
 * feeds a scanned code in; this model applies the client-side window pre-check, calls the service,
 * and exposes a result the inline success/failure view renders. Server remains authoritative.
 */
class CheckInModel(private val service: EventsService) : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Success(val alreadyCheckedIn: Boolean) : State
        data class OutsideWindow(val reason: String) : State
        data class Failed(val message: String) : State
    }

    var state: State by mutableStateOf<State>(State.Idle)
        private set

    /** Apply the client-side window gate, then (if open) check in. [window]/[now] injected so the
     *  too-early/too-late branch is deterministic in tests; the server still validates. */
    fun submit(code: String, window: CheckInWindow, now: Date) {
        when (window.state(now)) {
            is CheckInWindow.State.TooEarly -> { state = State.OutsideWindow("Check-in hasn't opened yet."); return }
            is CheckInWindow.State.TooLate -> { state = State.OutsideWindow("Check-in for this event has closed."); return }
            CheckInWindow.State.Open -> Unit
        }
        state = State.Checking
        viewModelScope.launch {
            try {
                val result = service.checkIn(code)
                state = State.Success(result.alreadyCheckedIn)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = State.Failed(
                    if (error is AccountFailure.SignedOut) "Please sign in again."
                    else "Something went wrong. Please try again.",
                )
            }
        }
    }

    fun reset() { state = State.Idle }
}

/**
 * In-memory events service for previews and Compose UI tests: one reservable public event, one
 * full event (waitlist), and one invite-only event — exercising the real EventsModel + screens.
 */
class PreviewEventsService : EventsService {
    private val soon = Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
    private val store = linkedMapOf(
        "e1" to DonorEvent("e1", "park-cleanup", "Park Cleanup", "volunteer", "Zilker Park", "Austin",
            soon, Date(soon.time + 7_200_000), capacity = 50, spotsLeft = 8, status = ReservationStatus.NONE,
            reportIssueUrl = "mailto:help@dad.org"),
        "e2" to DonorEvent("e2", "food-bank", "Food Bank Night", "volunteer", "Central Kitchen", "Austin",
            Date(soon.time + 86_400_000), Date(soon.time + 93_600_000), capacity = 30, spotsLeft = 0,
            status = ReservationStatus.NONE, reportIssueUrl = "mailto:help@dad.org"),
        "e3" to DonorEvent("e3", "founders-dinner", "Founders Dinner", "social", "The Loft", "Austin",
            Date(soon.time + 2 * 86_400_000L), Date(soon.time + 2 * 86_400_000L + 7_200_000), capacity = 20,
            spotsLeft = 5, status = ReservationStatus.NONE, invitationNote = "Founding donors only",
            reportIssueUrl = "mailto:help@dad.org"),
    )

    override suspend fun events(): List<DonorEvent> = store.values.sortedBy { it.startsAt }
    override suspend fun event(id: String): DonorEvent = store.getValue(id)

    override suspend fun reserve(eventId: String): ReservationOutcome {
        val e = store.getValue(eventId)
        val outcome = if (e.spotsLeft > 0) ReservationOutcome.RESERVED else ReservationOutcome.WAITLISTED
        store[eventId] = e.copy(
            spotsLeft = maxOf(0, e.spotsLeft - 1),
            status = if (outcome == ReservationOutcome.RESERVED) ReservationStatus.RESERVED else ReservationStatus.WAITLISTED,
        )
        return outcome
    }

    override suspend fun cancel(eventId: String) {
        val e = store.getValue(eventId)
        store[eventId] = e.copy(spotsLeft = e.spotsLeft + 1, status = ReservationStatus.NONE)
    }

    override suspend fun checkIn(code: String): CheckInResultValue =
        CheckInResultValue(eventId = "e1", checkedIn = true, alreadyCheckedIn = false)
}
