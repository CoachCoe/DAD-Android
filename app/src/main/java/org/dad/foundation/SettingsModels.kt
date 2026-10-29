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

// Settings view model + the service seam. The model owns load/ready/failed state, the
// toggle-and-save path (optimistic version bump), and the delete-account flow (confirm gate ->
// server delete -> local wipe), so it is unit-tested without a network or UI.

data class SettingsSnapshot(val profile: ProfileSummary, val preferences: Preferences)

/** The seam the settings model calls. A conformer maps to the generated client or a test double. */
interface SettingsService {
    suspend fun load(): SettingsSnapshot
    /** Save the preference block; returns the new snapshot (with the bumped version). */
    suspend fun savePreferences(preferences: Preferences): SettingsSnapshot
    /** Delete the account server-side (personal data removed, gift records kept). */
    suspend fun deleteAccount()
    /** Clear the local session (sign out / post-delete wipe). */
    suspend fun signOut()
}

sealed interface SettingsPhase {
    data object Loading : SettingsPhase
    data object Ready : SettingsPhase
    data class Failed(val message: String) : SettingsPhase
}

class SettingsModel(private val service: SettingsService) : ViewModel() {
    var phase: SettingsPhase by mutableStateOf(SettingsPhase.Loading)
        private set
    var snapshot: SettingsSnapshot? by mutableStateOf(null)
        private set
    /** True once the account is deleted or the donor signed out — the UI leaves the settings shell. */
    var signedOut: Boolean by mutableStateOf(false)
        private set
    var actionError: String? by mutableStateOf(null)
        private set

    fun load() {
        phase = SettingsPhase.Loading
        viewModelScope.launch {
            try {
                snapshot = service.load()
                phase = SettingsPhase.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                phase = SettingsPhase.Failed(message(error))
            }
        }
    }

    /** Flip one preference toggle and persist. The snapshot is replaced in place on success so the
     *  version advances; on failure the old snapshot stays and the error surfaces inline. */
    fun toggle(key: String, value: Boolean) {
        val current = snapshot ?: return
        actionError = null
        viewModelScope.launch {
            try {
                snapshot = service.savePreferences(current.preferences.with(key, value))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = message(error)
            }
        }
    }

    /** Delete the account only when the confirm gate is satisfied, then wipe the local session. */
    fun deleteAccount(gate: DeleteAccountGate) {
        if (!gate.isConfirmed) { actionError = "Type ${DeleteAccountGate.CONFIRM_WORD} to confirm."; return }
        actionError = null
        viewModelScope.launch {
            try {
                service.deleteAccount()
                service.signOut()
                signedOut = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = message(error)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                service.signOut()
                signedOut = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = message(error)
            }
        }
    }

    private fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> "Please sign in again."
        else -> "Something went wrong. Please try again."
    }

    companion object {
        fun factory(service: SettingsService): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsModel(service) }
        }
        fun previewFactory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsModel(PreviewSettingsService()) }
        }
    }
}

/** In-memory settings service for previews and Compose UI tests. */
class PreviewSettingsService : SettingsService {
    private var prefs = Preferences(
        version = 3, referralEmails = true, marketingEmails = false,
        pushReferralConverted = true, pushStreakAtRisk = true, pushStreakMilestone = true,
        pushStreakLapsed = true, pushBadgeEarned = true, pushEventNearby = false,
        pushEventReminder = true, pushEventPromoted = false, pushGiftProcessed = true,
        rankingsOptedOut = false, networkVisible = true, volunteer = true,
    )

    override suspend fun load(): SettingsSnapshot = SettingsSnapshot(
        profile = ProfileSummary(email = "donor@example.com", accountRole = "DONOR",
            zip = "78701", city = "Austin", phone = null),
        preferences = prefs,
    )

    override suspend fun savePreferences(preferences: Preferences): SettingsSnapshot {
        prefs = preferences.copy(version = preferences.version + 1) // server bumps the version
        return load()
    }

    override suspend fun deleteAccount() {}
    override suspend fun signOut() {}
}
