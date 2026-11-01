package org.dad.foundation

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.api.DonorApi
import org.dad.foundation.generated.model.PostDonorMePasswordRequest
import org.dad.foundation.generated.model.PostDonorPasswordSignInRequest
import org.dad.foundation.generated.model.LoginRequest
import org.dad.foundation.generated.model.PostDonorSignupRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Contract 2.5.0 types the signup answer as `SignupSent` (200 `{"sent":true}`), but the outcome is only
 * "a link was emailed": the body is deliberately NOT decoded, so any 2xx is success even with an empty or
 * undecodable body (a typed Retrofit decode would surface Gson/EOF errors for a request that succeeded).
 */
private interface SignupEndpoint {
    @POST("donor/signup")
    suspend fun signup(@Body request: PostDonorSignupRequest): Response<ResponseBody>
}

/**
 * Unauthenticated credential flows over the generated client: email signup (`postDonorSignup`), an emailed
 * sign-in link (`login`) and email + password sign-in (`postDonorPasswordSignIn`). Same shape as [WireAuth]: sign-in returns
 * credentials but never stores them; the caller passes the call to [AccountSession.establish]. No
 * password is ever logged or placed in an error.
 */
class WireCredentialAuth(
    origin: String,
    private val deviceName: String? = null,
    private val baseClient: OkHttpClient = defaultNativeClient(),
) : SignupService, SignInLinkService, PasswordSignInService {
    private val url: HttpUrl = validatedOrigin(origin)
    private fun retrofit() = nativeRetrofit(url, baseClient)

    override suspend fun requestSignup(form: SignupForm) {
        val response = retrofit().create(SignupEndpoint::class.java)
            .signup(PostDonorSignupRequest(name = form.name, email = form.email, zip = form.zip, city = form.city))
        response.checked()
        response.body()?.close()
    }

    /** No `next`: the app claims the link itself rather than handing the donor to a web page. */
    override suspend fun requestSignInLink(email: String) {
        decoding { retrofit().create(AccountApi::class.java).login(LoginRequest(email = email)).requireBody() }
    }

    override suspend fun signIn(email: String, password: String): Credentials = decoding {
        val request = PostDonorPasswordSignInRequest(
            email = email, password = password, deviceName = deviceName?.take(64), platform = PostDonorPasswordSignInRequest.Platform.ANDROID)
        // A 401 here means the email or password is wrong; the model words it that way. There is no session to refresh.
        val value = retrofit().create(AccountApi::class.java).postDonorPasswordSignIn(request).requireBody()
        nativeCredentials(value.accessToken, value.refreshToken, value.expiresIn)
    }
}

/**
 * Authenticated password management (`postDonorMePassword`) through the session.
 *
 * Contract 2.4.0: a missing or wrong `current_password` is a 422 whose Problem `code` is
 * `CURRENT_PASSWORD_INCORRECT`; that is [AccountFailure.PasswordIncorrect] and the session stays. Any
 * other 422 is a malformed password. A 401 is a real session failure and takes the normal
 * refresh-once path, EXCEPT an older server's 401 whose detail is the credential-rejection sentence on
 * this route, which is the same wrong-password case and must never end the session. Unknown `code`
 * values are ignored.
 */
class WirePasswordSettings(private val product: ProductClient) : PasswordSettingsService {
    override suspend fun setPassword(current: String?, new: String) {
        product.perform { retrofit ->
            val response = retrofit.create(DonorApi::class.java).postDonorMePassword(PostDonorMePasswordRequest(password = new, currentPassword = current))
            if (response.isSuccessful) return@perform
            val (code, detail) = response.problem()
            when (response.code()) {
                422 -> throw if (code == CURRENT_PASSWORD_INCORRECT) AccountFailure.PasswordIncorrect else AccountFailure.InvalidInput
                401 -> throw if (detail == LEGACY_REJECTED_DETAIL) AccountFailure.PasswordIncorrect else AccountFailure.SignedOut
                429 -> throw AccountFailure.RateLimited
                else -> throw AccountFailure.Unavailable
            }
        }
    }

    companion object {
        const val CURRENT_PASSWORD_INCORRECT = "CURRENT_PASSWORD_INCORRECT"
        const val LEGACY_REJECTED_DETAIL = "The email or password is incorrect"
    }
}
