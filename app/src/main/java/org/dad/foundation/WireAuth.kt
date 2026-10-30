package org.dad.foundation

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.model.BeginPhoneSignInRequest
import org.dad.foundation.generated.model.FinishPhoneSignInRequest

/**
 * Real phone + SMS sign-in against the generated client (`beginPhoneSignIn`, `finishPhoneSignIn`).
 *
 * The contract is stateless: the server issues no challenge id, so the "handle" threaded between the
 * two steps is the E.164 phone number itself (not a secret; the SMS code is the proof). [verifyPhone]
 * returns credentials but never stores them: the caller passes it to [AccountSession.establish] so the
 * session owns the await and its epoch guard. Existing accounts only (an unknown phone is a 401).
 */
class WireAuth(origin: String, private val deviceName: String? = null, private val baseClient: OkHttpClient = defaultNativeClient()) : SignInService {
    private val url: HttpUrl = validatedOrigin(origin)
    private fun api() = nativeRetrofit(url, baseClient).create(AccountApi::class.java)

    override suspend fun requestPhoneCode(phone: String): String = decoding {
        api().beginPhoneSignIn(BeginPhoneSignInRequest(phone)).checked()
        phone
    }

    override suspend fun verifyPhone(handle: String, code: String): Credentials = decoding {
        val request = FinishPhoneSignInRequest(handle, code, deviceName?.take(64), FinishPhoneSignInRequest.Platform.ANDROID)
        val value = api().finishPhoneSignIn(request).requireBody()
        // A browser-shaped `{signed_in:true}` body carries no tokens; never treat it as a session.
        nativeCredentials(value.accessToken, value.refreshToken, value.expiresIn)
    }
}
