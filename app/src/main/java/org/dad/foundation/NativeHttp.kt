package org.dad.foundation

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** The one HTTP shape every native call uses: direct HTTPS origin, no cookies/redirects, bounded bodies. */
internal fun validatedOrigin(origin: String): HttpUrl = origin.toHttpUrl().also {
    require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null) {
        "A direct HTTPS API origin is required"
    }
}

internal fun defaultNativeClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .cookieJar(CookieJar.NO_COOKIES).build()

/** A Retrofit bound to [url] that sends the native headers, plus a bearer when [access] is given. */
internal fun nativeRetrofit(url: HttpUrl, baseClient: OkHttpClient, access: String? = null): Retrofit {
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
    return Retrofit.Builder().baseUrl(url).client(builder.build()).addConverterFactory(GsonConverterFactory.create()).build()
}

/**
 * Map a non-2xx status to the one failure the app reasons about: 401 signed out, 422 invalid input,
 * 429 rate limited, 409 [conflict] (call-specific: a stale write is just unavailable, but a blocked
 * account deletion is its own failure), anything else unavailable. Server error bodies are never
 * surfaced: they are not user copy and may be sensitive.
 */
internal fun <T> Response<T>.checked(conflict: AccountFailure = AccountFailure.Unavailable): Response<T> {
    if (isSuccessful) return this
    errorBody()?.close()
    throw when (code()) {
        401 -> AccountFailure.SignedOut
        409 -> conflict
        422 -> AccountFailure.InvalidInput
        429 -> AccountFailure.RateLimited
        else -> AccountFailure.Unavailable
    }
}

/** `code` and `detail` of an error body as raw text (so an unknown or new value is simply ignored); null when absent or malformed. */
internal fun <T> Response<T>.problem(): Pair<String?, String?> = try {
    val json = errorBody()?.use { com.google.gson.JsonParser.parseString(it.string()).asJsonObject }
    fun text(key: String) = json?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
    text("code") to text("detail")
} catch (_: Exception) { null to null }

internal fun <T> Response<T>.requireBody(conflict: AccountFailure = AccountFailure.Unavailable): T =
    checked(conflict).body() ?: throw AccountFailure.InvalidResponse

/**
 * A malformed 200 body is an invalid response, not an unhandled parser crash. An empty body surfaces from
 * Gson as an [java.io.EOFException]; the transport has already read the whole body by then (see
 * [nativeRetrofit]), so it is a decode failure and never a dropped connection.
 */
internal inline fun <T> decoding(block: () -> T): T =
    try { block() } catch (_: JsonParseException) { throw AccountFailure.InvalidResponse }
    catch (_: MalformedJsonException) { throw AccountFailure.InvalidResponse }
    catch (_: java.io.EOFException) { throw AccountFailure.InvalidResponse }

internal fun nativeCredentials(accessToken: String?, refreshToken: String?, expiresIn: Int): Credentials {
    if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || expiresIn <= 0) throw AccountFailure.InvalidResponse
    return Credentials(accessToken, refreshToken, System.currentTimeMillis() + expiresIn.toLong() * 1000)
}
