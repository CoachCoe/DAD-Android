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

/**
 * Transport seam for the sign-in flows, so the view model is unit-testable without a network.
 * [WireAuth] implements it against the generated client; tests substitute a fake.
 */
interface SignInService {
    /** Request a one-time SMS code for [phone] (E.164). Returns the handle [verifyPhone] consumes. */
    suspend fun requestPhoneCode(phone: String): String
    /** Verify the SMS [code] against the handle from [requestPhoneCode], returning credentials. */
    suspend fun verifyPhone(handle: String, code: String): Credentials
}

/** Used when no API origin is configured (release): sign-in fails clearly, never via a preview double. */
object UnconfiguredSignIn : SignInService {
    override suspend fun requestPhoneCode(phone: String): String = throw AccountFailure.InvalidConfiguration
    override suspend fun verifyPhone(handle: String, code: String): Credentials = throw AccountFailure.InvalidConfiguration
}

/**
 * Runs the credential-producing work inside the session ([AccountSession.establish]) so the session
 * owns the await and its epoch guard: a sign-in that resolves after a sign-out is discarded instead
 * of clobbering state. Returns the credentials that were installed.
 */
typealias Establish = suspend (suspend () -> Credentials) -> Credentials

sealed interface PhoneSignInPhase {
    data object EnterPhone : PhoneSignInPhase
    data object RequestingCode : PhoneSignInPhase
    data class EnterCode(val handle: String) : PhoneSignInPhase
    data class Verifying(val handle: String) : PhoneSignInPhase
    data class Failed(val message: String) : PhoneSignInPhase
    data object SignedIn : PhoneSignInPhase
}

/**
 * Phone + SMS one-time-code journey: pick number, request code, enter code, done. An explicit state
 * machine so every transition is unit-testable; the screen binds to [phase]. The handle is kept
 * across a wrong code so the donor re-enters it without requesting a fresh SMS.
 */
class PhoneSignInModel(private val service: SignInService, private val establish: Establish) : ViewModel() {
    var phase: PhoneSignInPhase by mutableStateOf(PhoneSignInPhase.EnterPhone)
        private set
    var phone: String by mutableStateOf("")
    var code: String by mutableStateOf("")
    private var activeHandle: String? = null
    private var work: Job? = null

    /** True once a code was sent and not abandoned, including after a wrong code (stay on code entry). */
    val isAwaitingCode: Boolean
        get() = when (phase) {
            is PhoneSignInPhase.EnterCode, is PhoneSignInPhase.Verifying -> true
            is PhoneSignInPhase.Failed -> activeHandle != null
            else -> false
        }

    /** E.164-ish: a leading + and 8–15 digits. Keeps obviously-bad input off the wire. */
    val canRequestCode: Boolean get() = PHONE.matches(phone)
    /** The server issues 6-digit codes; enable verify only when the field is complete. */
    val canVerify: Boolean get() = CODE.matches(code)

    fun requestCode() {
        if (!canRequestCode || phase is PhoneSignInPhase.RequestingCode || phase is PhoneSignInPhase.Verifying) return
        phase = PhoneSignInPhase.RequestingCode
        val number = phone
        work = viewModelScope.launch {
            try {
                val handle = service.requestPhoneCode(number)
                activeHandle = handle
                code = ""
                phase = PhoneSignInPhase.EnterCode(handle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                activeHandle = null
                phase = PhoneSignInPhase.Failed(message(error))
            }
        }
    }

    fun verify() {
        val handle = activeHandle ?: return
        if (!canVerify || phase is PhoneSignInPhase.Verifying) return
        phase = PhoneSignInPhase.Verifying(handle)
        val entered = code
        work = viewModelScope.launch {
            try {
                establish { service.verifyPhone(handle, entered) }
                phase = PhoneSignInPhase.SignedIn
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Keep the handle so the donor can re-enter the code without re-requesting.
                phase = PhoneSignInPhase.Failed(message(error))
            }
        }
    }

    /** Return to code entry after a recoverable failure, preserving the handle. */
    fun retryCodeEntry() {
        val handle = activeHandle
        phase = if (handle == null) PhoneSignInPhase.EnterPhone else PhoneSignInPhase.EnterCode(handle)
    }

    private fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> "That code didn’t match. Check it and try again."
        is AccountFailure.Unavailable -> "We couldn’t reach DAD. Please try again in a moment."
        is AccountFailure.InvalidInput -> "That phone number doesn’t look right. Check it and try again."
        is AccountFailure.RateLimited -> "Too many attempts. Please wait a moment and try again."
        is AccountFailure.InvalidConfiguration -> NOT_CONFIGURED
        else -> "Something went wrong. Please try again."
    }

    companion object {
        const val NOT_CONFIGURED = "This build isn’t configured to reach DAD. Install a build with a valid API origin."
        private val PHONE = Regex("^\\+[1-9][0-9]{7,14}$")
        private val CODE = Regex("^[0-9]{6}$")
        fun factory(service: SignInService, establish: Establish): ViewModelProvider.Factory = viewModelFactory {
            initializer { PhoneSignInModel(service, establish) }
        }
    }
}
