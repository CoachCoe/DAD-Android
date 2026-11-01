package org.dad.foundation

import com.google.gson.JsonElement
import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.api.DonorApi
import org.dad.foundation.generated.api.WalletApi
import org.dad.foundation.generated.model.EmployeeEnrollRequest
import org.dad.foundation.generated.model.EmployeeJoinTeamRequest
import org.dad.foundation.generated.model.RequestEmailChangeRequest
import org.dad.foundation.generated.model.SaveProfileRequest
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Session lists carry timestamps, so they are read as JSON with the strict readers, like the donor-home reads. */
private interface SessionReads {
    @GET("donor/me/sessions") suspend fun sessions(): Response<JsonElement>
    @POST("donor/me/sessions/{session_id}/revoke") suspend fun revoke(@Path("session_id") id: String): Response<JsonElement>
    @POST("donor/me/sessions/revoke-others") suspend fun revokeOthers(): Response<JsonElement>
}

/**
 * Real account management over the generated client: `saveProfile`, `requestEmailChange`, `sessions` /
 * `revokeSession` / `revokeOtherSessions`, `logoutAll`, and the employee calls `employeeTeam` /
 * `employeeEnroll` / `employeeJoinTeam`, all authorized through the session.
 */
class WireAccountManagement(private val product: ProductClient) : AccountManagementService {
    override suspend fun saveProfile(zip: String, city: String, phone: String): ProfileSummary {
        return product.perform { it.create(DonorApi::class.java).saveProfile(SaveProfileRequest(zip = zip, city = city, phone = phone)).requireBody() }.summary()
    }

    override suspend fun requestEmailChange(email: String) {
        product.perform { it.create(AccountApi::class.java).requestEmailChange(RequestEmailChangeRequest(email)).requireBody(conflict = AccountFailure.Conflict) }
    }

    override suspend fun sessions() = product.perform { sessionList(it.create(SessionReads::class.java).sessions().body_()) }

    override suspend fun revokeSession(id: String) = product.perform { retrofit ->
        val response = retrofit.create(SessionReads::class.java).revoke(id)
        if (response.code() == 404) { response.errorBody()?.close(); throw AccountFailure.NotFound }
        sessionList(response.requireBody(conflict = AccountFailure.Conflict))
    }

    override suspend fun revokeOtherSessions() = product.perform { sessionList(it.create(SessionReads::class.java).revokeOthers().body_()) }

    /**
     * Local credentials are cleared only once the server confirms, so a failed request leaves the donor signed in
     * to retry rather than believing the other devices were signed out.
     */
    override suspend fun signOutEverywhere() {
        try { product.perform { it.create(AccountApi::class.java).logoutAll().requireBody() } }
        catch (_: AccountFailure.SignedOut) { }
        product.session.discardLocal()
    }

    override suspend fun employer(): EmployerTeam {
        val body = product.perform { it.create(DonorApi::class.java).employeeTeam().requireBody() }
        return EmployerTeam(
            organization = body.organization.ifEmpty { null },
            teamName = body.teamName?.ifEmpty { null },
            leaderboard = body.leaderboard.map {
                TeamStanding(it.teamId, it.name, it.activeGivers, it.annualizedMinor, it.avgStreakDays, it.volunteerMinutes)
            },
        )
    }

    override suspend fun enroll(companyCode: String): String = product.perform {
        it.create(DonorApi::class.java).employeeEnroll(EmployeeEnrollRequest(companyCode)).requireBody(conflict = AccountFailure.Conflict).organization
    }

    override suspend fun joinTeam(name: String) {
        product.perform { it.create(DonorApi::class.java).employeeJoinTeam(EmployeeJoinTeamRequest(name)).requireBody() }
    }

    private fun sessionList(json: JsonElement): List<DeviceSessionSummary> =
        json.asObjectOrInvalid().array("sessions").map { element ->
            val s = element.asObjectOrInvalid()
            DeviceSessionSummary(id = s.str("id"), label = s.str("label"), isApp = s.str("client") == "APP",
                lastUsedAt = s.time("last_used_at"), current = s.bool("current"))
        }
}

/** The referral code as a Google Wallet pass (`walletProviders`, `googlePass`). */
class WireWalletPass(private val product: ProductClient) : WalletPassService {
    override suspend fun googleWalletAvailable(): Boolean =
        product.perform { it.create(WalletApi::class.java).walletProviders().requireBody().google }

    override suspend fun googlePassUrl(): String =
        product.perform { it.create(WalletApi::class.java).googlePass().requireBody(conflict = AccountFailure.Conflict).url.toString() }
}
