package org.dad.foundation

import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.api.DonorApi
import org.dad.foundation.generated.model.ClaimRequest
import org.dad.foundation.generated.model.NativeSession
import org.dad.foundation.generated.model.RefreshRequest
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

// Generator 7.20 emits a String literal for this boolean const; keep the wire contract intact.
data class LogoutAcknowledgement(@com.google.gson.annotations.SerializedName("signed_out") val signedOut: Boolean)

class WireAPI(origin: String, private val baseClient: OkHttpClient = client()) : AccountAPI {
    private val url = origin.toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null) { "A direct HTTPS API origin is required" }
    }
    private fun retrofit(access: String? = null): Retrofit {
        val builder = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES)
        builder.interceptors().add(0, Interceptor { chain ->
            val request = chain.request().newBuilder().header("X-DAD-Client", "native").removeHeader("Origin").removeHeader("Cookie")
            if (access != null) request.header("Authorization", "Bearer $access") else request.removeHeader("Authorization")
            val response = chain.proceed(request.build())
            val body = response.body
            if (body.contentLength() > 1_048_576) { response.close(); throw AccountFailure.InvalidResponse }
            val source = body.source()
            if (source.request(1_048_577)) { response.close(); throw AccountFailure.InvalidResponse }
            val bytes = source.readByteArray()
            response.close()
            response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
        })
        val http = builder.build()
        return Retrofit.Builder().baseUrl(url).client(http).addConverterFactory(GsonConverterFactory.create()).build()
    }
    override suspend fun profile(access: String): AccountProfile {
        val value = body(retrofit(access).create(DonorApi::class.java).profile())
        if (value.email.isNullOrBlank()) throw AccountFailure.InvalidResponse
        return AccountProfile(value.email, value.publicName)
    }
    override suspend fun refresh(token: String) = credentials(body(retrofit().create(AccountApi::class.java).refresh(RefreshRequest(token))))
    override suspend fun claim(token: String) = credentials(body(retrofit().create(AccountApi::class.java).claim(ClaimRequest(token, platform = ClaimRequest.Platform.ANDROID))))
    override suspend fun logout(access: String) {
        if (!body(retrofit(access).create(AccountApi::class.java).logout()).signedOut) throw AccountFailure.InvalidResponse
    }
    private fun <T> body(response: Response<T>): T {
        if (response.code() == 401) throw AccountFailure.SignedOut
        if (!response.isSuccessful) { response.errorBody()?.close(); throw AccountFailure.Unavailable }
        return response.body() ?: throw AccountFailure.InvalidResponse
    }
    private fun credentials(value: NativeSession): Credentials {
        if (value.accessToken.isNullOrBlank() || value.refreshToken.isNullOrBlank() || value.expiresIn <= 0) throw AccountFailure.InvalidResponse
        return Credentials(value.accessToken, value.refreshToken, System.currentTimeMillis() + value.expiresIn.toLong() * 1000)
    }
    companion object {
        private fun client() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES).build()
    }
}
