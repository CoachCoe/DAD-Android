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

// Email signup, email + password sign-in and set/change password (PRD 5.1, D-2). Pure rules, service
// seams and state machines so every path is unit-testable without a network or a view. Passwords are
// never stored, logged or echoed: models clear them the moment they are handed to a call.

/** Input rules shared by signup, password sign-in and the iOS app (same rule on both platforms). */
object CredentialRules {
    /**
     * Plausible-address check: trimmed, at most 254 characters, no whitespace, exactly one `@`, a
     * non-empty local part and a domain of at least two non-empty dot-separated labels. The server
     * stays the authority on deliverability.
     */
    fun isPlausibleEmail(raw: String): Boolean {
        val email = raw.trim()
        if (email.length > 254 || email.any(Char::isWhitespace)) return false
        val parts = email.split("@")
        if (parts.size != 2 || parts[0].isEmpty()) return false
        val labels = parts[1].split(".")
        return labels.size >= 2 && labels.all { it.isNotEmpty() }
    }

    /** The contract and server accept a new password of 12 to 72 BYTES (bcrypt's limit). */
    val passwordBytes = 12..72

    fun isAcceptableNewPassword(password: String) = password.toByteArray(Charsets.UTF_8).size in passwordBytes

    /** Sign-in only needs a non-empty value the server can bound-check; over 72 bytes can never match. */
    fun isSubmittablePassword(password: String) = password.toByteArray(Charsets.UTF_8).size in 1..72

    /** US ZIP: exactly five digits (the server derives local-day rollover from it). */
    fun isPlausibleZip(raw: String) = ZIP.matches(raw.trim())

    private val ZIP = Regex("^[0-9]{5}$")
}

data class SignupForm(val name: String, val email: String, val zip: String, val city: String)

/**
 * New-donor signup (D-2). The server never reveals whether the address already exists; it replies only
 * that a single-use sign-in link was emailed, which the app claims via [AccountSession.claim].
 */
interface SignupService {
    suspend fun requestSignup(form: SignupForm)
}

/**
 * A sign-in link for an existing donor (`login`). The server answers the same whether or not the address
 * has an account, so the app only ever says a link is on its way; the donor claims it via [AccountSession.claim].
 */
interface SignInLinkService {
    suspend fun requestSignInLink(email: String)
}

interface PasswordSignInService {
    /** Returns native credentials; the caller hands them to [AccountSession.establish]. */
    suspend fun signIn(email: String, password: String): Credentials
}

/** Set or change the signed-in donor's password. [current] may be null right after a fresh sign-in. */
interface PasswordSettingsService {
    suspend fun setPassword(current: String?, new: String)
}

/** Used when no API origin is configured (release): every call fails clearly, never via a preview double. */
object UnconfiguredCredentialAuth : SignupService, SignInLinkService, PasswordSignInService {
    override suspend fun requestSignup(form: SignupForm) = throw AccountFailure.InvalidConfiguration
    override suspend fun requestSignInLink(email: String) = throw AccountFailure.InvalidConfiguration
    override suspend fun signIn(email: String, password: String): Credentials = throw AccountFailure.InvalidConfiguration
}

private const val RATE_LIMITED = "Too many attempts. Please wait a moment and try again."
private const val SOMETHING_WENT_WRONG = "Something went wrong. Please try again."

sealed interface SignupPhase {
    data object Editing : SignupPhase
    data object Submitting : SignupPhase
    data class Sent(val email: String) : SignupPhase
    data class Failed(val message: String) : SignupPhase
}

class SignupModel(private val service: SignupService) : ViewModel() {
    var phase: SignupPhase by mutableStateOf(SignupPhase.Editing)
        private set
    var name by mutableStateOf("")
    var email by mutableStateOf("")
    var zip by mutableStateOf("")
    var city by mutableStateOf("")

    val canSubmit: Boolean
        get() = phase !is SignupPhase.Submitting && name.trim().let { it.isNotEmpty() && it.length <= 100 } &&
            CredentialRules.isPlausibleEmail(email) && CredentialRules.isPlausibleZip(zip) &&
            city.trim().let { it.isNotEmpty() && it.length <= 100 }

    fun submit() {
        if (!canSubmit) return
        phase = SignupPhase.Submitting
        val form = SignupForm(name.trim(), email.trim(), zip.trim(), city.trim())
        viewModelScope.launch {
            try {
                service.requestSignup(form)
                phase = SignupPhase.Sent(form.email)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = SignupPhase.Failed(message(error))
            }
        }
    }

    /** Back to the form after a failure or to change the address; entered values are kept. */
    fun edit() { phase = SignupPhase.Editing }

    internal fun message(error: Exception): String = when (error) {
        is AccountFailure.InvalidInput -> "Check your name, email, city and ZIP code, then try again."
        is AccountFailure.RateLimited -> RATE_LIMITED
        is AccountFailure.Unavailable -> "We couldn’t send your sign-in link right now. Please try again in a moment."
        is AccountFailure.InvalidConfiguration -> PhoneSignInModel.NOT_CONFIGURED
        else -> SOMETHING_WENT_WRONG
    }

    companion object {
        fun factory(service: SignupService): ViewModelProvider.Factory = viewModelFactory { initializer { SignupModel(service) } }
    }
}

sealed interface SignInLinkPhase {
    data object Editing : SignInLinkPhase
    data object Submitting : SignInLinkPhase
    data class Sent(val email: String) : SignInLinkPhase
    data class Failed(val message: String) : SignInLinkPhase
}

class SignInLinkModel(private val service: SignInLinkService) : ViewModel() {
    var phase: SignInLinkPhase by mutableStateOf(SignInLinkPhase.Editing)
        private set
    var email by mutableStateOf("")

    val canSubmit: Boolean get() = phase !is SignInLinkPhase.Submitting && CredentialRules.isPlausibleEmail(email)

    fun submit() {
        if (!canSubmit) return
        phase = SignInLinkPhase.Submitting
        val address = email.trim()
        viewModelScope.launch {
            try {
                service.requestSignInLink(address)
                phase = SignInLinkPhase.Sent(address)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = SignInLinkPhase.Failed(message(error))
            }
        }
    }

    fun edit() { phase = SignInLinkPhase.Editing }

    internal fun message(error: Exception): String = when (error) {
        is AccountFailure.InvalidInput -> "Check your email address, then try again."
        is AccountFailure.RateLimited -> RATE_LIMITED
        is AccountFailure.Unavailable -> "We couldn’t send your sign-in link right now. Please try again in a moment."
        is AccountFailure.InvalidConfiguration -> PhoneSignInModel.NOT_CONFIGURED
        else -> SOMETHING_WENT_WRONG
    }

    companion object {
        fun factory(service: SignInLinkService): ViewModelProvider.Factory = viewModelFactory { initializer { SignInLinkModel(service) } }
    }
}

sealed interface PasswordSignInPhase {
    data object Editing : PasswordSignInPhase
    data object Submitting : PasswordSignInPhase
    data class Failed(val message: String) : PasswordSignInPhase
    data object SignedIn : PasswordSignInPhase
}

class PasswordSignInModel(private val service: PasswordSignInService, private val establish: Establish) : ViewModel() {
    var phase: PasswordSignInPhase by mutableStateOf(PasswordSignInPhase.Editing)
        private set
    var email by mutableStateOf("")
    var password by mutableStateOf("")

    val canSubmit: Boolean
        get() = phase !is PasswordSignInPhase.Submitting && CredentialRules.isPlausibleEmail(email) && CredentialRules.isSubmittablePassword(password)

    fun submit() {
        if (!canSubmit) return
        phase = PasswordSignInPhase.Submitting
        val address = email.trim()
        val secret = password
        // The password leaves the model the moment it is handed to the call; a failure re-asks for it.
        password = ""
        viewModelScope.launch {
            try {
                establish { service.signIn(address, secret) }
                phase = PasswordSignInPhase.SignedIn
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = PasswordSignInPhase.Failed(message(error))
            }
        }
    }

    internal fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> "The email or password is incorrect."
        is AccountFailure.InvalidInput -> "Check your email and password, then try again."
        is AccountFailure.RateLimited -> RATE_LIMITED
        is AccountFailure.Unavailable -> "We couldn’t reach DAD. Please try again in a moment."
        is AccountFailure.InvalidConfiguration -> PhoneSignInModel.NOT_CONFIGURED
        else -> SOMETHING_WENT_WRONG
    }

    companion object {
        fun factory(service: PasswordSignInService, establish: Establish): ViewModelProvider.Factory =
            viewModelFactory { initializer { PasswordSignInModel(service, establish) } }
    }
}

sealed interface PasswordChangePhase {
    data object Editing : PasswordChangePhase
    data object Saving : PasswordChangePhase
    data object Saved : PasswordChangePhase
    data class Failed(val message: String) : PasswordChangePhase
}

/** Set or change the password from Settings. A wrong current password keeps the session; only a real 401 signs out. */
class PasswordChangeModel(private val service: PasswordSettingsService) : ViewModel() {
    var phase: PasswordChangePhase by mutableStateOf(PasswordChangePhase.Editing)
        private set
    /** True once the session ended (expired or revoked) so the screen leaves the signed-in shell. */
    var signedOut by mutableStateOf(false)
        private set
    var currentPassword by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var confirmPassword by mutableStateOf("")

    val newPasswordHint: String?
        get() = if (newPassword.isEmpty() || CredentialRules.isAcceptableNewPassword(newPassword)) null else "Use 12 to 72 characters."
    val confirmMismatch: Boolean get() = confirmPassword.isNotEmpty() && confirmPassword != newPassword
    val canSave: Boolean
        get() = phase !is PasswordChangePhase.Saving && CredentialRules.isAcceptableNewPassword(newPassword) &&
            newPassword == confirmPassword && currentPassword.toByteArray(Charsets.UTF_8).size <= 72

    fun save() {
        if (!canSave) return
        phase = PasswordChangePhase.Saving
        val current = currentPassword.ifEmpty { null }
        val new = newPassword
        viewModelScope.launch {
            try {
                service.setPassword(current, new)
                currentPassword = ""; newPassword = ""; confirmPassword = ""
                phase = PasswordChangePhase.Saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is AccountFailure.SignedOut) signedOut = true
                phase = PasswordChangePhase.Failed(message(error))
            }
        }
    }

    fun edit() { phase = PasswordChangePhase.Editing }

    internal fun message(error: Exception): String = when (error) {
        is AccountFailure.PasswordIncorrect -> "Your current password didn’t match. Check it and try again."
        is AccountFailure.InvalidInput -> "Use a password of 12 to 72 characters."
        is AccountFailure.RateLimited -> RATE_LIMITED
        is AccountFailure.SignedOut -> "Please sign in again."
        is AccountFailure.Unavailable -> "We couldn’t save your password right now. Please try again in a moment."
        is AccountFailure.InvalidConfiguration -> PhoneSignInModel.NOT_CONFIGURED
        else -> SOMETHING_WENT_WRONG
    }

    companion object {
        fun factory(service: PasswordSettingsService): ViewModelProvider.Factory =
            viewModelFactory { initializer { PasswordChangeModel(service) } }
    }
}

/**
 * Claim-link recognition for the "Check your email" screen: a donor can paste the link from the email
 * when the app did not open it directly. Only a DAD claim link is accepted; the token never leaves
 * this call except to the session.
 */
object PastedLink {
    /** The claim token in [text] (trimmed), or null when it is not a DAD sign-in link for this build. */
    fun claimToken(text: String, router: LinkRouter?): String? =
        (router?.route(text.trim()) as? LinkIntent.Claim)?.token
}
