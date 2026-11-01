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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.Date

// Partner portal view models + the service seam. The models own load/ready/failed state, role-gated actions
// and push register/unregister, so they are unit-tested without a network or UI — the Kotlin counterpart of
// the iOS partner models.

private const val RATE_LIMITED = "Too many attempts. Please wait a moment and try again."
private const val SOMETHING_WENT_WRONG = "Something went wrong. Please try again."

data class PartnerSnapshot(
    val overview: PartnerOverview,
    val initiatives: List<PartnerInitiative>,
    val spend: List<PartnerSpendCategory>,
    val people: PartnerPeople?,
    val teams: List<TeamStanding>,
    val users: List<PartnerUser>,
    val exports: List<PartnerExport>,
    val logo: ByteArray?,
    /** True when the overview loaded but at least one other section failed to load. */
    val sectionsUnavailable: Boolean,
) {
    val selfId: String? get() = overview.userId
}

/** One earlier report export (P-14). [downloadable] is false once its retained PDF has aged out. */
data class PartnerExport(
    val id: String,
    val createdAt: OffsetDateTime,
    val exportedBy: String,
    val committedMinor: Long,
    val deployedMinor: Long,
    val downloadable: Boolean,
)

interface PartnerService {
    suspend fun overview(): PartnerOverview
    suspend fun initiatives(): List<PartnerInitiative>
    suspend fun spend(): List<PartnerSpendCategory>
    suspend fun people(): PartnerPeople
    /** The company team leaderboard (EM-5); teams under five members are left out server-side. */
    suspend fun teams(): List<TeamStanding>
    suspend fun users(): List<PartnerUser>
    /** Admin-only (P-13). The model gates on role before calling, but the server re-checks. */
    suspend fun inviteUser(email: String, name: String, role: PartnerRole)
    suspend fun removeUser(id: String)
    /** Admin-only (§7.2): the organization's name and description. */
    suspend fun saveProfile(organization: String, description: String)
    /** Admin-only: the headcount enrollment percentages are measured against (EM-3). */
    suspend fun saveWorkforce(size: Int)
    /** The organization's logo as PNG, or null when it has none. */
    suspend fun logo(): ByteArray?
    /** Admin-only: a PNG of at most 512 by 512 pixels and 256 KiB, or null to remove the logo. */
    suspend fun saveLogo(png: ByteArray?)
    /** Admin-only: earlier report exports, newest first. */
    suspend fun exports(): List<PartnerExport>
    /** Admin-only: a new PDF impact report (recorded as an export). */
    suspend fun report(): ByteArray
    suspend fun retainedReport(id: String): ByteArray
    suspend fun signOut()
    /** Partner push (§7.5). Register on sign-in, clear on sign-out; both native-only. */
    suspend fun registerPushDevice(token: String)
    suspend fun unregisterPushDevice()
}

sealed interface PartnerPhase {
    data object Loading : PartnerPhase
    data object Ready : PartnerPhase
    data class Failed(val message: String) : PartnerPhase
}

/** A PDF ready for the share sheet; [id] tells two reports apart even when their bytes match. */
class PartnerReport(val id: Long, val name: String, val pdf: ByteArray)

class PartnerModel(private val service: PartnerService) : ViewModel() {
    var phase: PartnerPhase by mutableStateOf(PartnerPhase.Loading)
        private set
    var snapshot: PartnerSnapshot? by mutableStateOf(null)
        private set
    var actionError: String? by mutableStateOf(null)
        private set
    /** Confirms the last completed action ("Organization profile saved."). */
    var notice: String? by mutableStateOf(null)
        private set
    var isBusy: Boolean by mutableStateOf(false)
        private set
    /** True once the session ended (signed out, expired or revoked): the app returns to sign-in. */
    var signedOut: Boolean by mutableStateOf(false)
        private set
    var pendingRemoval: PartnerUser? by mutableStateOf(null)
        private set
    var report: PartnerReport? by mutableStateOf(null)
        private set
    private var reports = 0L

    /** The current role, defaulting to the least-privileged VIEWER until the overview loads, so
     *  capability gates never open early. */
    val role: PartnerRole get() = snapshot?.overview?.role ?: PartnerRole.VIEWER

    fun load() {
        phase = PartnerPhase.Loading
        viewModelScope.launch {
            try {
                // Overview first: it carries the role every other section and action is gated on.
                val overview = service.overview()
                var partial = false
                // A secondary read: a failure hides that section rather than the whole portal; cancellation
                // and an ended session still propagate.
                suspend fun <T> optional(fallback: T, read: suspend () -> T): T = try {
                    read()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AccountFailure.SignedOut) {
                    throw error
                } catch (_: Exception) {
                    partial = true
                    fallback
                }
                val loaded = coroutineScope {
                    val initiatives = async { optional(emptyList()) { service.initiatives() } }
                    val spend = async { optional(emptyList()) { service.spend() } }
                    val people = async { optional(null) { service.people() } }
                    val teams = async { optional(emptyList()) { service.teams() } }
                    val users = async { optional(emptyList()) { service.users() } }
                    val exports = async { if (overview.role.canExport) optional(emptyList()) { service.exports() } else emptyList() }
                    val logo = async { if (overview.hasLogo) optional(null) { service.logo() } else null }
                    PartnerSnapshot(overview, initiatives.await(), spend.await(), people.await(), teams.await(), users.await(),
                        exports.await(), logo.await(), sectionsUnavailable = false)
                }
                snapshot = loaded.copy(sectionsUnavailable = partial)
                phase = PartnerPhase.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountFailure.SignedOut) {
                signedOut = true
                phase = PartnerPhase.Failed(SESSION_ENDED)
            } catch (error: Exception) {
                phase = PartnerPhase.Failed(message(error))
            }
        }
    }

    /** Runs one portal action: one at a time, errors inline, and a lost session leaves the portal. */
    private fun act(success: String?, invalid: String? = null, conflict: String? = null, work: suspend () -> Unit) {
        if (isBusy) return
        isBusy = true
        actionError = null
        notice = null
        viewModelScope.launch {
            try {
                work()
                notice = success
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountFailure.SignedOut) {
                signedOut = true
            } catch (error: Exception) {
                actionError = when {
                    error is AccountFailure.InvalidInput && invalid != null -> invalid
                    error is AccountFailure.Conflict && conflict != null -> conflict
                    error is AccountFailure.Forbidden -> "Only an admin can do that."
                    else -> message(error)
                }
            } finally {
                isBusy = false
            }
        }
    }

    private fun update(change: (PartnerSnapshot) -> PartnerSnapshot) { snapshot = snapshot?.let(change) }

    /** Invite a user (P-13), admin-only. Gated client-side; the server re-checks. */
    fun inviteUser(email: String, name: String, role: PartnerRole) {
        if (!this.role.canManageUsers) { actionError = "Only an admin can invite users."; return }
        if (!isPlausibleEmail(email)) { actionError = "Enter a valid email address."; return }
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) { actionError = "Enter their name."; return }
        val address = email.trim()
        act("Invitation sent to $address.", invalid = "Check the name and email address, then try again.",
            conflict = "That email already signs in to DAD with another role or organization.") {
            service.inviteUser(address, trimmedName, role)
            val users = runCatching { service.users() }.getOrNull()
            if (users != null) update { it.copy(users = users) }
        }
    }

    /** Whether [user] may be removed by the signed-in admin: never yourself, and never the last admin. */
    fun canRemove(user: PartnerUser): Boolean = role.canManageUsers && removalBlock(user.id) == null

    private fun removalBlock(id: String): String? {
        val current = snapshot ?: return "You can’t remove this user."
        if (id == current.selfId) return "You can’t remove yourself."
        val user = current.users.firstOrNull { it.id == id } ?: return "You can’t remove this user."
        if (user.role == PartnerRole.ADMIN && current.users.count { it.role == PartnerRole.ADMIN } <= 1) return "You can’t remove the last admin."
        return null
    }

    /** Step one of removal: stage the user for the inline confirmation. Nothing is sent yet. */
    fun requestRemoval(id: String) {
        if (!role.canManageUsers) { actionError = "Only an admin can remove users."; return }
        removalBlock(id)?.let { actionError = it; return }
        actionError = null
        pendingRemoval = snapshot?.users?.firstOrNull { it.id == id }
    }

    fun cancelRemoval() { pendingRemoval = null }

    fun confirmRemoval() {
        val user = pendingRemoval ?: return
        pendingRemoval = null
        removeUser(user.id)
    }

    fun removeUser(id: String) {
        if (!role.canManageUsers) { actionError = "Only an admin can remove users."; return }
        removalBlock(id)?.let { actionError = it; return }
        act(null, conflict = "You can’t remove the last admin.") {
            service.removeUser(id)
            update { it.copy(users = it.users.filterNot { u -> u.id == id }) }
        }
    }

    fun saveProfile(organization: String, description: String) {
        if (!role.canEditProfile) { actionError = "Only an admin can edit the organization profile."; return }
        val name = organization.trim()
        val about = description.trim()
        if (name.length !in 1..200 || about.length > 4000) { actionError = PROFILE_RULE; return }
        act("Organization profile saved.", invalid = PROFILE_RULE) {
            service.saveProfile(name, about)
            val overview = service.overview()
            update { it.copy(overview = overview) }
        }
    }

    fun saveWorkforce(raw: String) {
        if (!role.canEditProfile) { actionError = "Only an admin can set the workforce size."; return }
        val size = raw.trim().toIntOrNull()?.takeIf { it >= 0 } ?: run { actionError = WORKFORCE_RULE; return }
        act("Workforce size saved.", invalid = WORKFORCE_RULE) {
            service.saveWorkforce(size)
            val overview = service.overview()
            val people = runCatching { service.people() }.getOrNull()
            update { it.copy(overview = overview, people = people ?: it.people) }
        }
    }

    /** [png] is already scaled to fit the server's limits; null removes the logo. */
    fun saveLogo(png: ByteArray?) {
        if (!role.canEditProfile) { actionError = "Only an admin can change the logo."; return }
        act(if (png == null) "Logo removed." else "Logo saved.", invalid = LOGO_RULE) {
            service.saveLogo(png)
            update { it.copy(logo = png) }
        }
    }

    /** The picked image could not be read or scaled to the logo limits. */
    fun rejectLogo() {
        notice = null
        actionError = LOGO_RULE
    }

    fun exportReport() {
        if (!role.canExport) { actionError = "Only an admin can export a report."; return }
        act(null) {
            report = PartnerReport(++reports, "dad-impact-report.pdf", service.report())
            val exports = runCatching { service.exports() }.getOrNull()
            if (exports != null) update { it.copy(exports = exports) }
        }
    }

    fun download(export: PartnerExport) {
        if (!role.canExport || !export.downloadable) return
        act(null) {
            try {
                report = PartnerReport(++reports, "dad-impact-report-${export.id.take(8)}.pdf", service.retainedReport(export.id))
            } catch (_: AccountFailure.NotFound) {
                actionError = "That report is no longer available. Export a new one."
                val exports = runCatching { service.exports() }.getOrNull()
                if (exports != null) update { it.copy(exports = exports) }
            }
        }
    }

    /** The share sheet has the report; forget the bytes. */
    fun reportShared() { report = null }

    fun signOut() {
        viewModelScope.launch {
            bestEffort { service.unregisterPushDevice() }
            bestEffort { service.signOut() }
            signedOut = true
        }
    }

    /** Register this device for partner push after sign-in (§7.5). Best-effort — never blocks. */
    fun registerPush(token: String) {
        viewModelScope.launch { bestEffort { service.registerPushDevice(token) } }
    }

    private suspend fun bestEffort(action: suspend () -> Unit) {
        try { action() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
    }

    private fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> SESSION_ENDED
        is AccountFailure.RateLimited -> RATE_LIMITED
        else -> SOMETHING_WENT_WRONG
    }

    companion object {
        const val PROFILE_RULE = "Use a name of up to 200 characters and a description of up to 4,000."
        const val WORKFORCE_RULE = "Enter the number of employees as a whole number."
        const val LOGO_RULE = "Use a PNG or JPEG image of at most 512 by 512 pixels."
        const val SESSION_ENDED = "Please sign in again."

        fun factory(service: PartnerService): ViewModelProvider.Factory = viewModelFactory {
            initializer { PartnerModel(service) }
        }
        fun previewFactory(role: PartnerRole = PartnerRole.ADMIN): ViewModelProvider.Factory =
            viewModelFactory { initializer { PartnerModel(PreviewPartnerService(role)) } }
    }
}

// MARK: - Partner sign-in (approved partner users sign in with an emailed link)

interface PartnerSignInService {
    suspend fun requestSignInLink(email: String)
    /** Exchanges an emailed link's token for the partner session and keeps it. */
    suspend fun accept(token: String)
}

sealed interface PartnerSignInPhase {
    data object Editing : PartnerSignInPhase
    data object Submitting : PartnerSignInPhase
    data class Sent(val email: String) : PartnerSignInPhase
    data object Accepting : PartnerSignInPhase
    data object SignedIn : PartnerSignInPhase
    data class Failed(val message: String) : PartnerSignInPhase
}

class PartnerSignInModel(private val service: PartnerSignInService) : ViewModel() {
    var phase: PartnerSignInPhase by mutableStateOf(PartnerSignInPhase.Editing)
        private set
    var email by mutableStateOf("")
    /** The address the last link went to, so a failed link keeps the "check your email" screen. */
    var sentTo: String? by mutableStateOf(null)
        private set

    val canSubmit: Boolean get() = phase !is PartnerSignInPhase.Submitting && CredentialRules.isPlausibleEmail(email)

    fun submit() {
        if (!canSubmit) return
        val address = email.trim()
        phase = PartnerSignInPhase.Submitting
        viewModelScope.launch {
            phase = try {
                service.requestSignInLink(address)
                sentTo = address
                PartnerSignInPhase.Sent(address)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PartnerSignInPhase.Failed(when (error) {
                    is AccountFailure.InvalidInput -> "Check your email address, then try again."
                    is AccountFailure.RateLimited -> RATE_LIMITED
                    else -> "We couldn’t send your sign-in link right now. Please try again in a moment."
                })
            }
        }
    }

    /** Signs in with the token from an emailed partner link, tapped or pasted. */
    fun accept(token: String) {
        if (phase is PartnerSignInPhase.Accepting) return
        phase = PartnerSignInPhase.Accepting
        viewModelScope.launch {
            phase = try {
                service.accept(token)
                PartnerSignInPhase.SignedIn
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PartnerSignInPhase.Failed(when (error) {
                    is AccountFailure.InvalidInput -> "That sign-in link has expired or was already used. Request a new one."
                    is AccountFailure.Forbidden -> "This partner account can’t sign in. Ask your organization’s admin or DAD support."
                    is AccountFailure.RateLimited -> RATE_LIMITED
                    else -> SOMETHING_WENT_WRONG
                })
            }
        }
    }

    /** Accepts a pasted partner link (`…/partner/accept#token=…`) or a bare token. */
    fun acceptPasted(raw: String) {
        val text = raw.trim()
        val token = text.substringAfter('#', "").split('&').firstOrNull { it.startsWith("token=") }?.removePrefix("token=") ?: text
        if (token.isEmpty() || token.any(Char::isWhitespace)) {
            phase = PartnerSignInPhase.Failed("Paste the whole sign-in link from your email.")
            return
        }
        accept(token)
    }

    fun edit() {
        sentTo = null
        phase = PartnerSignInPhase.Editing
    }

    companion object {
        fun factory(service: PartnerSignInService): ViewModelProvider.Factory = viewModelFactory { initializer { PartnerSignInModel(service) } }
    }
}

/** Accepts any partner link, so the DEBUG preview's partner sign-in runs with no backend. */
object PreviewPartnerSignIn : PartnerSignInService {
    override suspend fun requestSignInLink(email: String) {}
    override suspend fun accept(token: String) {}
}

/** In-memory partner service for previews/UITests: an admin or viewer portal. */
class PreviewPartnerService(private val role: PartnerRole = PartnerRole.ADMIN) : PartnerService {
    private val userList = mutableListOf(
        PartnerUser("u1", "ada@acme.com", "Ada Admin", PartnerRole.ADMIN),
        PartnerUser("u2", "val@acme.com", "Val Viewer", PartnerRole.VIEWER),
    )
    private var organization = "Acme Corp"
    private var about = "Doing good together."
    private var workforce = 240
    private var savedLogo: ByteArray? = null

    override suspend fun overview(): PartnerOverview = PartnerOverview(
        organization = organization, description = about, role = role, companyCode = "ACME",
        committed = VerifiedMoney(5_000_000, verified = true),
        deployed = VerifiedMoney(3_000_000, verified = false), // unverified -> hidden (§7.3)
        deployedPercent = 60,
        peopleReached = VerifiedCount(12_400, verified = true),
        employeesEnrolled = 86, workforceSize = workforce, enrolledPercent = 35,
        updatedAt = Date(1_793_000_000_000), hasLogo = savedLogo != null,
        userId = if (role == PartnerRole.ADMIN) "u1" else "u2",
    )

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
        PartnerPeople(86, workforce, 940_000, 470_000, 42, 6_300, 7, suppressed = false)

    override suspend fun teams(): List<TeamStanding> = listOf(TeamStanding("t1", "Engineering", 31, 1_200_000, 40, 1_200))
    override suspend fun users(): List<PartnerUser> = userList.toList()
    override suspend fun inviteUser(email: String, name: String, role: PartnerRole) {
        userList.add(PartnerUser("u${userList.size + 1}", email, name, role))
    }
    override suspend fun removeUser(id: String) { userList.removeAll { it.id == id } }
    override suspend fun saveProfile(organization: String, description: String) { this.organization = organization; about = description }
    override suspend fun saveWorkforce(size: Int) { workforce = size }
    override suspend fun logo(): ByteArray? = savedLogo
    override suspend fun saveLogo(png: ByteArray?) { savedLogo = png }
    override suspend fun exports(): List<PartnerExport> = emptyList()
    override suspend fun report(): ByteArray = "%PDF-1.4 preview".toByteArray()
    override suspend fun retainedReport(id: String): ByteArray = throw AccountFailure.NotFound
    override suspend fun signOut() {}
    override suspend fun registerPushDevice(token: String) {}
    override suspend fun unregisterPushDevice() {}
}
