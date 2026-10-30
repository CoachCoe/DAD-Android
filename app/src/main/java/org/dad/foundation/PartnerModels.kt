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

// Partner portal view model + the service seam. The model owns load/pending/ready/failed state,
// the pending-approval gate, role-gated actions, and push register/unregister, so it is
// unit-tested without a network or UI — the Kotlin counterpart of the iOS Phase F partner model.

data class PartnerSnapshot(
    val overview: PartnerOverview,
    val initiatives: List<PartnerInitiative>,
    val spend: List<PartnerSpendCategory>,
    val people: PartnerPeople?,
    val users: List<PartnerUser>,
)

/** Thrown by a conformer when the signed-in partner account is still awaiting approval (P-2):
 *  the portal must show the pending screen and nothing else. */
class PartnerPendingApproval : Exception()

interface PartnerService {
    suspend fun overview(): PartnerOverview
    suspend fun initiatives(): List<PartnerInitiative>
    suspend fun spend(): List<PartnerSpendCategory>
    suspend fun people(): PartnerPeople
    suspend fun users(): List<PartnerUser>
    suspend fun inviteUser(email: String, role: PartnerRole)
    suspend fun removeUser(id: String)
    suspend fun registerPushDevice(token: String)
    suspend fun unregisterPushDevice()
}

sealed interface PartnerPhase {
    data object Loading : PartnerPhase
    /** Signed in but awaiting approval — show the pending screen and nothing else (P-2). */
    data object Pending : PartnerPhase
    data object Ready : PartnerPhase
    data class Failed(val message: String) : PartnerPhase
}

class PartnerModel(private val service: PartnerService) : ViewModel() {
    var phase: PartnerPhase by mutableStateOf(PartnerPhase.Loading)
        private set
    var snapshot: PartnerSnapshot? by mutableStateOf(null)
        private set
    var actionError: String? by mutableStateOf(null)
        private set

    /** The current role, defaulting to the least-privileged VIEWER until the overview loads, so
     *  capability gates never open early. */
    val role: PartnerRole get() = snapshot?.overview?.role ?: PartnerRole.VIEWER

    fun load() {
        phase = PartnerPhase.Loading
        viewModelScope.launch {
            try {
                // Overview first — it carries the role and gates everything; a pending account
                // throws before any portal data is fetched (P-2).
                val overview = service.overview()
                snapshot = PartnerSnapshot(
                    overview = overview,
                    initiatives = runCatching { service.initiatives() }.getOrDefault(emptyList()),
                    spend = runCatching { service.spend() }.getOrDefault(emptyList()),
                    people = runCatching { service.people() }.getOrNull(),
                    users = runCatching { service.users() }.getOrDefault(emptyList()),
                )
                phase = PartnerPhase.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: PartnerPendingApproval) {
                phase = PartnerPhase.Pending
            } catch (error: Exception) {
                phase = PartnerPhase.Failed(message(error))
            }
        }
    }

    /** Invite a user (P-13), admin-only. Gated client-side; the server re-checks. A viewer call
     *  is a no-op that surfaces the gate rather than hitting the network. */
    fun inviteUser(email: String, role: PartnerRole) {
        if (!this.role.canManageUsers) { actionError = "Only an admin can invite users."; return }
        actionError = null
        viewModelScope.launch {
            try {
                service.inviteUser(email, role)
                snapshot = snapshot?.copy(users = runCatching { service.users() }.getOrDefault(snapshot?.users ?: emptyList()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = message(error)
            }
        }
    }

    fun removeUser(id: String) {
        if (!role.canManageUsers) { actionError = "Only an admin can remove users."; return }
        actionError = null
        viewModelScope.launch {
            try {
                service.removeUser(id)
                snapshot = snapshot?.let { it.copy(users = it.users.filterNot { u -> u.id == id }) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = message(error)
            }
        }
    }

    /** Register this device for partner push after sign-in (§7.5). Best-effort — never blocks. */
    fun registerPush(token: String) {
        viewModelScope.launch { runCatching { service.registerPushDevice(token) } }
    }

    fun unregisterPush() {
        viewModelScope.launch { runCatching { service.unregisterPushDevice() } }
    }

    private fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> "Please sign in again."
        else -> "Something went wrong. Please try again."
    }

    companion object {
        fun factory(service: PartnerService): ViewModelProvider.Factory = viewModelFactory {
            initializer { PartnerModel(service) }
        }
        fun previewFactory(pending: Boolean = false, role: PartnerRole = PartnerRole.ADMIN): ViewModelProvider.Factory =
            viewModelFactory { initializer { PartnerModel(PreviewPartnerService(pending, role)) } }
    }
}

/** In-memory partner service for previews/UITests: admin or viewer portal, or the pending gate. */
class PreviewPartnerService(
    private val pending: Boolean = false,
    private val role: PartnerRole = PartnerRole.ADMIN,
) : PartnerService {
    private val userList = mutableListOf(
        PartnerUser("u1", "ada@acme.com", "Ada Admin", PartnerRole.ADMIN),
        PartnerUser("u2", "val@acme.com", "Val Viewer", PartnerRole.VIEWER),
    )

    override suspend fun overview(): PartnerOverview {
        if (pending) throw PartnerPendingApproval()
        return PartnerOverview(
            organization = "Acme Corp", description = "Doing good together.", role = role, companyCode = "ACME",
            committed = VerifiedMoney(5_000_000, verified = true),
            deployed = VerifiedMoney(3_000_000, verified = false), // unverified -> hidden (§7.3)
            deployedPercent = 60,
            peopleReached = VerifiedCount(12_400, verified = true),
            employeesEnrolled = 86, workforceSize = 240, enrolledPercent = 35,
            updatedAt = Date(1_793_000_000_000), hasLogo = false,
        )
    }

    override suspend fun initiatives(): List<PartnerInitiative> = listOf(
        PartnerInitiative("i1", "Clean Water West", "Wells across 6 counties.", InitiativeStatus.ACTIVE,
            "West", 2_000_000, 1_200_000,
            listOf(PartnerMetric("People reached", 8400, PartnerMetric.Unit.PEOPLE),
                   PartnerMetric("Wells", 32, PartnerMetric.Unit.INITIATIVES))),
    )

    override suspend fun spend(): List<PartnerSpendCategory> = listOf(
        PartnerSpendCategory("Water", 1_800_000), PartnerSpendCategory("Housing", 1_200_000),
    )

    override suspend fun people(): PartnerPeople =
        PartnerPeople(86, 240, 940_000, 470_000, 42, 6_300, 7, suppressed = false)

    override suspend fun users(): List<PartnerUser> = userList
    override suspend fun inviteUser(email: String, role: PartnerRole) {
        userList.add(PartnerUser("u${userList.size + 1}", email, email, role))
    }
    override suspend fun removeUser(id: String) { userList.removeAll { it.id == id } }
    override suspend fun registerPushDevice(token: String) {}
    override suspend fun unregisterPushDevice() {}
}
