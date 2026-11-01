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

// Account management beyond preferences (PRD §6.12, EM-2/EM-4/EM-5): contact details, the account
// email, where the donor is signed in, their employer and team, and the referral code as a Google Wallet
// pass. Each model owns its own phase so a failure in one card never blanks the rest of Settings.

data class DeviceSessionSummary(val id: String, val label: String, val isApp: Boolean, val lastUsedAt: OffsetDateTime, val current: Boolean)

data class TeamStanding(
    val id: String, val name: String, val activeGivers: Long, val annualizedMinor: Long, val avgStreakDays: Long, val volunteerMinutes: Long,
)

/** The donor's employer link: [organization] is null until a company code links one. The leaderboard only
 *  lists teams of five or more (EM-5), so it can be empty while linked. */
data class EmployerTeam(val organization: String?, val teamName: String?, val leaderboard: List<TeamStanding>)

interface AccountManagementService {
    /** Empty strings clear a field. Returns the saved profile. */
    suspend fun saveProfile(zip: String, city: String, phone: String): ProfileSummary
    /** Starts the two-step change: a confirmation goes to the current address first. */
    suspend fun requestEmailChange(email: String)
    suspend fun sessions(): List<DeviceSessionSummary>
    suspend fun revokeSession(id: String): List<DeviceSessionSummary>
    suspend fun revokeOtherSessions(): List<DeviceSessionSummary>
    /** Ends every session, this one included, then clears local credentials. */
    suspend fun signOutEverywhere()
    suspend fun employer(): EmployerTeam
    /** Returns the linked organization's name. */
    suspend fun enroll(companyCode: String): String
    suspend fun joinTeam(name: String)
}

interface WalletPassService {
    /** Whether this deployment can issue Google Wallet passes. */
    suspend fun googleWalletAvailable(): Boolean
    /** The Google Wallet save link for the donor's referral code. */
    suspend fun googlePassUrl(): String
}

object ProfileRules {
    const val CITY_LIMIT = 80
    private val ZIP = Regex("^[0-9]{5}(-[0-9]{4})?$")
    private val E164 = Regex("^\\+[1-9][0-9]{1,14}$")

    /** US ZIP or ZIP+4, or empty to clear it: the contract's pattern. */
    fun isAcceptableZip(raw: String): Boolean = raw.trim().let { it.isEmpty() || ZIP.matches(it) }

    /** The number as E.164, accepting the ways people type a US number; "" for an empty field and null when it cannot be one. */
    fun e164(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val plus = trimmed.startsWith("+")
        val body = if (plus) trimmed.drop(1) else trimmed
        if (!body.all { it in '0'..'9' || it in " -()." }) return null
        val digits = body.filter { it in '0'..'9' }
        val candidate = when {
            plus -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> return null
        }
        return candidate.takeIf { E164.matches(it) }
    }

    /** Only a Google Wallet save link is opened, never an arbitrary URL from a response. */
    fun isGoogleWalletSaveUrl(url: String): Boolean = try {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host == "pay.google.com" && uri.rawUserInfo == null && uri.port == -1 &&
            uri.rawPath.orEmpty().startsWith("/gp/v/save/")
    } catch (_: java.net.URISyntaxException) { false }
}

private const val SOMETHING_WENT_WRONG = "Something went wrong. Please try again."

private fun generic(error: Exception): String = when (error) {
    is AccountFailure.SignedOut -> "Please sign in again."
    is AccountFailure.RateLimited -> "Too many attempts. Please wait a moment and try again."
    else -> SOMETHING_WENT_WRONG
}

sealed interface DetailsPhase {
    data object Idle : DetailsPhase
    data object Saving : DetailsPhase
    data object Saved : DetailsPhase
    data class Failed(val message: String) : DetailsPhase
}

class ProfileDetailsModel(initial: ProfileSummary, private val service: AccountManagementService) : ViewModel() {
    var profile: ProfileSummary by mutableStateOf(initial)
        private set
    var zip by mutableStateOf(initial.zip.orEmpty())
    var city by mutableStateOf(initial.city.orEmpty())
    var phone by mutableStateOf(initial.phone.orEmpty())
    var phase: DetailsPhase by mutableStateOf(DetailsPhase.Idle)
        private set

    val zipValid: Boolean get() = ProfileRules.isAcceptableZip(zip)
    val phoneValid: Boolean get() = ProfileRules.e164(phone) != null
    val cityValid: Boolean get() = city.trim().length <= ProfileRules.CITY_LIMIT
    val changed: Boolean
        get() = zip.trim() != profile.zip.orEmpty() || city.trim() != profile.city.orEmpty() || ProfileRules.e164(phone) != profile.phone.orEmpty()
    val canSave: Boolean get() = zipValid && phoneValid && cityValid && changed && phase != DetailsPhase.Saving

    fun save() {
        val normalized = ProfileRules.e164(phone)
        if (!canSave || normalized == null) return
        phase = DetailsPhase.Saving
        val sentZip = zip.trim()
        val sentCity = city.trim()
        viewModelScope.launch {
            try {
                profile = service.saveProfile(sentZip, sentCity, normalized)
                zip = profile.zip.orEmpty(); city = profile.city.orEmpty(); phone = profile.phone.orEmpty()
                phase = DetailsPhase.Saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = DetailsPhase.Failed(
                    if (error is AccountFailure.InvalidInput) "Check the ZIP code and phone number, then try again." else generic(error))
            }
        }
    }

    companion object {
        fun factory(initial: ProfileSummary, service: AccountManagementService): ViewModelProvider.Factory =
            viewModelFactory { initializer { ProfileDetailsModel(initial, service) } }
    }
}

sealed interface EmailChangePhase {
    data object Editing : EmailChangePhase
    data object Submitting : EmailChangePhase
    data object Sent : EmailChangePhase
    data class Failed(val message: String) : EmailChangePhase
}

class EmailChangeModel(private val current: String, private val service: AccountManagementService) : ViewModel() {
    var email by mutableStateOf("")
    var phase: EmailChangePhase by mutableStateOf(EmailChangePhase.Editing)
        private set

    val canSubmit: Boolean
        get() = email.trim().let { CredentialRules.isPlausibleEmail(it) && !it.equals(current, ignoreCase = true) } &&
            phase != EmailChangePhase.Submitting

    fun submit() {
        if (!canSubmit) return
        phase = EmailChangePhase.Submitting
        val address = email.trim()
        viewModelScope.launch {
            try {
                service.requestEmailChange(address)
                phase = EmailChangePhase.Sent
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = EmailChangePhase.Failed(when (error) {
                    is AccountFailure.InvalidInput -> "Check the email address and try again."
                    is AccountFailure.Conflict -> "That address can’t replace your account email. Existing accounts are never merged."
                    is AccountFailure.Unavailable -> "We couldn’t request the change right now. Please try again in a moment."
                    else -> generic(error)
                })
            }
        }
    }

    fun edit() { phase = EmailChangePhase.Editing }

    companion object {
        fun factory(current: String, service: AccountManagementService): ViewModelProvider.Factory =
            viewModelFactory { initializer { EmailChangeModel(current, service) } }
    }
}

sealed interface LoadPhase {
    data object Loading : LoadPhase
    data object Ready : LoadPhase
    data class Failed(val message: String) : LoadPhase
}

class DeviceSessionsModel(private val service: AccountManagementService) : ViewModel() {
    var phase: LoadPhase by mutableStateOf(LoadPhase.Loading)
        private set
    var sessions: List<DeviceSessionSummary> by mutableStateOf(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var actionError: String? by mutableStateOf(null)
        private set
    var notice: String? by mutableStateOf(null)
        private set
    /** True once "sign out on every device" ended this session too. */
    var signedOut by mutableStateOf(false)
        private set

    val others: List<DeviceSessionSummary> get() = sessions.filterNot { it.current }

    fun load() {
        phase = LoadPhase.Loading
        viewModelScope.launch {
            try {
                sessions = service.sessions()
                phase = LoadPhase.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                phase = LoadPhase.Failed("Your signed-in devices couldn’t be loaded right now. Please try again in a moment.")
            }
        }
    }

    fun revoke(id: String) = act { sessions = service.revokeSession(id) }

    fun revokeOthers() = act {
        sessions = service.revokeOtherSessions()
        notice = "Signed out of every other device."
    }

    fun signOutEverywhere() = act {
        service.signOutEverywhere()
        signedOut = true
    }

    private fun act(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        notice = null
        viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AccountFailure.NotFound) { busy = false; load() }
            catch (_: AccountFailure.SignedOut) { signedOut = true }
            catch (_: Exception) { actionError = "Sign-out wasn’t confirmed. Refresh the list to check before trying again." }
            finally { busy = false }
        }
    }

    companion object {
        fun factory(service: AccountManagementService): ViewModelProvider.Factory = viewModelFactory { initializer { DeviceSessionsModel(service) } }
    }
}

class EmployerModel(private val service: AccountManagementService) : ViewModel() {
    var phase: LoadPhase by mutableStateOf(LoadPhase.Loading)
        private set
    var team: EmployerTeam? by mutableStateOf(null)
        private set
    var companyCode by mutableStateOf("")
    var teamName by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var actionError: String? by mutableStateOf(null)
        private set

    val canEnroll: Boolean get() = companyCode.isNotBlank() && !busy
    val canJoinTeam: Boolean get() = teamName.trim().let { it.isNotEmpty() && it.length <= 80 } && !busy

    fun load() {
        phase = LoadPhase.Loading
        viewModelScope.launch {
            try {
                team = service.employer()
                phase = LoadPhase.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                phase = LoadPhase.Failed("Your employer details couldn’t be loaded right now. Please try again in a moment.")
            }
        }
    }

    fun enroll() {
        if (!canEnroll) return
        val code = companyCode.trim()
        act(invalid = "That company code isn’t recognized. Check it with your employer and try again.",
            conflict = "Your account is already linked to another employer.") {
            service.enroll(code)
            companyCode = ""
        }
    }

    fun joinTeam() {
        if (!canJoinTeam) return
        val name = teamName.trim()
        act(invalid = "That team name couldn’t be saved. Use up to 80 characters.", conflict = null) {
            service.joinTeam(name)
            teamName = ""
        }
    }

    private fun act(invalid: String, conflict: String?, work: suspend () -> Unit) {
        busy = true
        actionError = null
        viewModelScope.launch {
            try {
                work()
                team = service.employer()
                phase = LoadPhase.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                actionError = when {
                    error is AccountFailure.InvalidInput -> invalid
                    error is AccountFailure.Conflict && conflict != null -> conflict
                    else -> generic(error)
                }
            } finally {
                busy = false
            }
        }
    }

    companion object {
        fun factory(service: AccountManagementService): ViewModelProvider.Factory = viewModelFactory { initializer { EmployerModel(service) } }
    }
}

/**
 * "Add to Google Wallet" for the referral code. The pass is a convenience: when Wallet is not configured or
 * the pass cannot be prepared, the on-screen code still works and the screen says so.
 */
class WalletPassModel(private val service: WalletPassService) : ViewModel() {
    /** null until checked, or when the check failed. */
    var available: Boolean? by mutableStateOf(null)
        private set
    var checkFailed by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    /** A validated save link ready to open; the screen clears it once opened. */
    var saveUrl: String? by mutableStateOf(null)

    fun check() {
        checkFailed = false
        viewModelScope.launch {
            try { available = service.googleWalletAvailable() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { available = null; checkFailed = true }
        }
    }

    fun prepare() {
        if (available != true || preparing) return
        preparing = true
        error = null
        viewModelScope.launch {
            try {
                val url = service.googlePassUrl()
                if (ProfileRules.isGoogleWalletSaveUrl(url)) saveUrl = url else error = FAILURE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = FAILURE
            } finally {
                preparing = false
            }
        }
    }

    companion object {
        const val FAILURE = "The Wallet pass couldn’t be prepared. Please try again. Your on-screen code still works."
        fun factory(service: WalletPassService): ViewModelProvider.Factory = viewModelFactory { initializer { WalletPassModel(service) } }
    }
}
