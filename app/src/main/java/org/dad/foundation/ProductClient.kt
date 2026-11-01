package org.dad.foundation

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * Authenticated transport seam for product (donor) calls. Every call goes through [perform], which
 * hands the operation a Retrofit carrying a fresh bearer from [AccountSession.authorized]
 * (single-flight refresh, one bounded 401 retry, epoch-guarded) and the native headers
 * (no cookies, no Origin, bounded response body). One `Wire<X>` conformer per `<X>Service`.
 */
class ProductClient(origin: String, val session: AccountSession, private val baseClient: OkHttpClient = defaultNativeClient()) {
    private val url: HttpUrl = validatedOrigin(origin)

    suspend fun <T> perform(operation: suspend (Retrofit) -> T): T = decoding {
        session.authorized { access -> operation(nativeRetrofit(url, baseClient, access)) }
    }
}
