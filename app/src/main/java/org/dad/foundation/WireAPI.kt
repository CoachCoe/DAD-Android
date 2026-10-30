package org.dad.foundation

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.api.DonorApi
import org.dad.foundation.generated.model.ClaimRequest
import org.dad.foundation.generated.model.RefreshRequest

// Generator 7.20 emits a String literal for this boolean const; keep the wire contract intact.
data class LogoutAcknowledgement(@com.google.gson.annotations.SerializedName("signed_out") val signedOut: Boolean)

/** Session-bootstrap operations: profile, refresh, claim, logout. */
class WireAPI(origin: String, private val baseClient: OkHttpClient = defaultNativeClient()) : AccountAPI {
    private val url: HttpUrl = validatedOrigin(origin)
    private fun retrofit(access: String? = null) = nativeRetrofit(url, baseClient, access)
    override suspend fun profile(access: String): AccountProfile = decoding {
        val value = retrofit(access).create(DonorApi::class.java).profile().requireBody()
        if (value.email.isNullOrBlank()) throw AccountFailure.InvalidResponse
        AccountProfile(value.email, value.publicName)
    }
    override suspend fun refresh(token: String) = decoding {
        val value = retrofit().create(AccountApi::class.java).refresh(RefreshRequest(token)).requireBody()
        nativeCredentials(value.accessToken, value.refreshToken, value.expiresIn)
    }
    override suspend fun claim(token: String) = decoding {
        val value = retrofit().create(AccountApi::class.java)
            .claim(ClaimRequest(token, platform = ClaimRequest.Platform.ANDROID)).requireBody()
        nativeCredentials(value.accessToken, value.refreshToken, value.expiresIn)
    }
    override suspend fun logout(access: String) {
        decoding {
            if (!retrofit(access).create(AccountApi::class.java).logout().requireBody().signedOut) throw AccountFailure.InvalidResponse
        }
    }
}
