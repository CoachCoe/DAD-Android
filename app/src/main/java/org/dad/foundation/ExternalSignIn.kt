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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.model.FinishDonorPasskeySignInRequest
import org.dad.foundation.generated.model.OidcSignInRequest

// Google sign-in (OIDC id_token exchange, `oidcSignIn`) and passkeys (WebAuthn,
// `beginDonorPasskeySignIn` / `finishDonorPasskeySignIn`). The platform ceremonies sit behind the
// [GoogleIdTokenProvider] / [PasskeyAuthenticator] seams, which need Android Credential Manager (a
// dependency the project does not ship) plus owner-supplied configuration. Everything else here is
// platform-free and tested against stub transports. Each method is switched on only by
// [SignInCapabilities]; by default both render as "Not available yet". Sign in with Apple is not
// offered on Android (iOS uses the native AuthenticationServices sheet, which has no Android twin).

/** Raw build configuration (Gradle properties). Both are blank in the repository; the owner supplies them. */
data class SignInConfig(val googleWebClientId: String?, val passkeyRelyingParty: String?) {
    /** The validated Google web client ID, or null when absent or malformed. */
    val validGoogleClientId: String? get() = googleWebClientId?.trim()?.takeIf { GOOGLE_CLIENT_ID.matches(it) }
    /** The validated passkey relying-party host, or null when absent or malformed. */
    val validPasskeyRelyingParty: String? get() = passkeyRelyingParty?.trim()?.lowercase()?.takeIf { RP_HOST.matches(it) }

    private companion object {
        val GOOGLE_CLIENT_ID = Regex("^[0-9A-Za-z._-]+\\.apps\\.googleusercontent\\.com$")
        val RP_HOST = Regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$")
    }
}

enum class SignInMethod { PHONE, PASSWORD, GOOGLE, PASSKEY }

/**
 * Which sign-in methods can actually run in this build. Phone and password need no client
 * configuration and are always on. Google and passkeys are off until configured AND a platform provider
 * exists (a client ID or domain alone never switches a method on).
 */
data class SignInCapabilities(val google: Boolean = false, val passkey: Boolean = false) {
    fun isAvailable(method: SignInMethod) = when (method) {
        SignInMethod.PHONE, SignInMethod.PASSWORD -> true
        SignInMethod.GOOGLE -> google
        SignInMethod.PASSKEY -> passkey
    }
    val hasUnavailableMethod: Boolean get() = !(google && passkey)

    companion object {
        val NONE = SignInCapabilities()
        fun of(config: SignInConfig, googleProviderAvailable: Boolean = false, passkeyProviderAvailable: Boolean = false) =
            SignInCapabilities(
                google = config.validGoogleClientId != null && googleProviderAvailable,
                passkey = config.validPasskeyRelyingParty != null && passkeyProviderAvailable,
            )
    }
}

/** Why a platform ceremony ended without a result. [Cancelled] is the person backing out and is never an error. */
sealed class ExternalSignInError : Exception() {
    data object Cancelled : ExternalSignInError()
    data object Unsupported : ExternalSignInError()
}

/** Produces a Google `id_token` through the platform account sheet (Credential Manager). */
fun interface GoogleIdTokenProvider { suspend fun idToken(): String }

interface OidcSignInService { suspend fun signIn(idToken: String): Credentials }

class PasskeyChallenge(val ceremony: String, val challenge: ByteArray, val relyingParty: String?)
class PasskeyAssertion(
    val credentialId: ByteArray, val clientDataJson: ByteArray, val authenticatorData: ByteArray,
    val signature: ByteArray, val userHandle: ByteArray?,
)

/** Runs the WebAuthn assertion through the platform (Credential Manager). */
fun interface PasskeyAuthenticator { suspend fun assertion(challenge: PasskeyChallenge): PasskeyAssertion }

interface PasskeySignInService {
    suspend fun begin(): PasskeyChallenge
    suspend fun finish(challenge: PasskeyChallenge, assertion: PasskeyAssertion): Credentials
}

internal fun ByteArray.base64Url(): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)
internal fun String.fromBase64Url(): ByteArray? = try { java.util.Base64.getUrlDecoder().decode(this) } catch (_: IllegalArgumentException) { null }

/** The `PublicKeyCredential` JSON the server verifies (`credential.toJSON()` shape). */
internal fun PasskeyAssertion.credentialJson(): Map<String, Any> {
    val response = linkedMapOf<String, Any>(
        "clientDataJSON" to clientDataJson.base64Url(), "authenticatorData" to authenticatorData.base64Url(),
        "signature" to signature.base64Url(),
    )
    userHandle?.let { response["userHandle"] = it.base64Url() }
    return linkedMapOf("id" to credentialId.base64Url(), "rawId" to credentialId.base64Url(), "type" to "public-key",
        "response" to response, "clientExtensionResults" to emptyMap<String, Any>())
}

/** Used when no API origin is configured (release): every call fails clearly. */
object UnconfiguredExternalSignIn : OidcSignInService, PasskeySignInService {
    override suspend fun signIn(idToken: String): Credentials = throw AccountFailure.InvalidConfiguration
    override suspend fun begin(): PasskeyChallenge = throw AccountFailure.InvalidConfiguration
    override suspend fun finish(challenge: PasskeyChallenge, assertion: PasskeyAssertion): Credentials = throw AccountFailure.InvalidConfiguration
}

/** Real `oidcSignIn` (google) and passkey sign-in exchanges. Credentials are returned, never stored. */
class WireExternalSignIn(
    origin: String,
    private val deviceName: String? = null,
    private val baseClient: OkHttpClient = defaultNativeClient(),
) : OidcSignInService, PasskeySignInService {
    private val url: HttpUrl = validatedOrigin(origin)
    private fun api() = nativeRetrofit(url, baseClient).create(AccountApi::class.java)

    override suspend fun signIn(idToken: String): Credentials = decoding {
        if (idToken.isEmpty() || idToken.toByteArray().size > 8192) throw AccountFailure.InvalidInput
        val request = OidcSignInRequest(idToken, deviceName?.take(64), OidcSignInRequest.Platform.ANDROID)
        val value = api().oidcSignIn("google", request).requireBody()
        nativeCredentials(value.accessToken, value.refreshToken, value.expiresIn)
    }

    override suspend fun begin(): PasskeyChallenge = decoding {
        val ceremony = api().beginDonorPasskeySignIn().requireBody()
        val options = ceremony.options as? Map<*, *>
        val challenge = (options?.get("challenge") as? String)?.fromBase64Url()
        if (ceremony.ceremony.isEmpty() || challenge == null || challenge.isEmpty()) throw AccountFailure.InvalidResponse
        PasskeyChallenge(ceremony.ceremony, challenge, options["rpId"] as? String)
    }

    override suspend fun finish(challenge: PasskeyChallenge, assertion: PasskeyAssertion): Credentials = decoding {
        val request = FinishDonorPasskeySignInRequest(challenge.ceremony, assertion.credentialJson(), deviceName?.take(64),
            FinishDonorPasskeySignInRequest.Platform.ANDROID)
        val value = api().finishDonorPasskeySignIn(request).requireBody()
        nativeCredentials(value.accessToken, value.refreshToken, value.expiresIn)
    }
}

sealed interface ExternalSignInPhase {
    data object Idle : ExternalSignInPhase
    data object Working : ExternalSignInPhase
    data class Failed(val message: String) : ExternalSignInPhase
    data object SignedIn : ExternalSignInPhase
}

class GoogleSignInModel(
    private val tokens: GoogleIdTokenProvider, private val service: OidcSignInService, private val establish: Establish,
) : ViewModel() {
    var phase: ExternalSignInPhase by mutableStateOf(ExternalSignInPhase.Idle)
        private set

    fun start() {
        if (phase is ExternalSignInPhase.Working) return
        phase = ExternalSignInPhase.Working
        viewModelScope.launch {
            try {
                val token = tokens.idToken()
                establish { service.signIn(token) }
                phase = ExternalSignInPhase.SignedIn
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = if (error is ExternalSignInError.Cancelled) ExternalSignInPhase.Idle else ExternalSignInPhase.Failed(message(error))
            }
        }
    }

    internal fun message(error: Exception): String = when (error) {
        is ExternalSignInError.Unsupported -> "Google sign-in isn’t available on this device."
        is AccountFailure.SignedOut -> "We couldn’t find a DAD account for that Google sign-in. Create your account with your email first."
        is AccountFailure.RateLimited -> "Too many attempts. Please wait a moment and try again."
        is AccountFailure.Unavailable -> "Google sign-in isn’t available right now. Please try again in a moment."
        else -> "Something went wrong. Please try again."
    }

    companion object {
        fun factory(tokens: GoogleIdTokenProvider, service: OidcSignInService, establish: Establish): ViewModelProvider.Factory =
            viewModelFactory { initializer { GoogleSignInModel(tokens, service, establish) } }
    }
}

class PasskeySignInModel(
    private val authenticator: PasskeyAuthenticator, private val service: PasskeySignInService, private val establish: Establish,
) : ViewModel() {
    var phase: ExternalSignInPhase by mutableStateOf(ExternalSignInPhase.Idle)
        private set

    fun start() {
        if (phase is ExternalSignInPhase.Working) return
        phase = ExternalSignInPhase.Working
        viewModelScope.launch {
            try {
                val challenge = service.begin()
                val assertion = authenticator.assertion(challenge)
                establish { service.finish(challenge, assertion) }
                phase = ExternalSignInPhase.SignedIn
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = if (error is ExternalSignInError.Cancelled) ExternalSignInPhase.Idle else ExternalSignInPhase.Failed(message(error))
            }
        }
    }

    internal fun message(error: Exception): String = when (error) {
        is ExternalSignInError.Unsupported -> "Passkeys aren’t available on this device."
        is AccountFailure.SignedOut -> "That passkey didn’t work. Try another sign-in method."
        is AccountFailure.RateLimited -> "Too many attempts. Please wait a moment and try again."
        is AccountFailure.Unavailable -> "Passkey sign-in isn’t available right now. Please try again in a moment."
        else -> "Something went wrong. Please try again."
    }

    companion object {
        fun factory(authenticator: PasskeyAuthenticator, service: PasskeySignInService, establish: Establish): ViewModelProvider.Factory =
            viewModelFactory { initializer { PasskeySignInModel(authenticator, service, establish) } }
    }
}
