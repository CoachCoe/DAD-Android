package org.dad.foundation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.api.PartnerApi
import org.dad.foundation.generated.model.EmployeeWorkforceRequest
import org.dad.foundation.generated.model.PartnerAcceptInviteRequest
import org.dad.foundation.generated.model.PartnerInviteUserRequest
import org.dad.foundation.generated.model.PartnerRegisterPushDeviceRequest
import org.dad.foundation.generated.model.PartnerRequestSignInRequest
import org.dad.foundation.generated.model.PartnerSaveLogoRequest
import org.dad.foundation.generated.model.PartnerSaveProfileRequest
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.Date

/**
 * The partner's app session: the bearer `POST /donor/partner/accept-invite` returns to a native request. It lasts
 * 12 hours and cannot be refreshed, so it is kept with its expiry and dropped the moment it lapses or the server
 * rejects it; the partner then signs in again with an emailed link. Stored apart from the donor session.
 */
class PartnerSession(private val store: CredentialStore, private val now: () -> Long = System::currentTimeMillis) {
    private var stored: Credentials? = store.load()

    @get:Synchronized val isSignedIn: Boolean get() = runCatching { token() }.isSuccess

    @Synchronized fun install(token: String) {
        val credentials = Credentials(token, refresh = "", expiresAtMillis = now() + LIFETIME_MILLIS)
        store.save(credentials)
        stored = credentials
    }

    /** The bearer, or [AccountFailure.SignedOut] once it has expired (which also forgets it). */
    @Synchronized fun token(): String {
        val current = stored ?: throw AccountFailure.SignedOut
        if (current.expiresAtMillis <= now()) { clear(); throw AccountFailure.SignedOut }
        return current.access
    }

    @Synchronized fun clear() {
        stored = null
        store.clear()
    }

    companion object { const val LIFETIME_MILLIS = 12 * 60 * 60 * 1000L }
}

/** Partner sign-in: request an emailed link (`partnerRequestSignIn`) and exchange its token for the session (`partnerAcceptInvite`). */
class WirePartnerAuth(
    origin: String,
    private val session: PartnerSession,
    private val baseClient: OkHttpClient = defaultNativeClient(),
) : PartnerSignInService {
    private val url: HttpUrl = validatedOrigin(origin)
    private fun api() = nativeRetrofit(url, baseClient).create(PartnerApi::class.java)

    override suspend fun requestSignInLink(email: String) {
        decoding { api().partnerRequestSignIn(PartnerRequestSignInRequest(email)).requireBody() }
    }

    /** A used or expired link is [AccountFailure.InvalidInput]; a partner who may not sign in is [AccountFailure.Forbidden]. */
    override suspend fun accept(token: String) {
        val response = decoding { api().partnerAcceptInvite(PartnerAcceptInviteRequest(token)) }
        if (response.code() == 403) { response.errorBody()?.close(); throw AccountFailure.Forbidden }
        val bearer = response.requireBody().session
        if (bearer.isNullOrBlank()) throw AccountFailure.InvalidResponse
        session.install(bearer)
    }
}

/** Portal reads carry timestamps, so they are read as JSON with the strict readers, like the donor-home reads. */
private interface PartnerReads {
    @GET("donor/partner/overview") suspend fun overview(): Response<JsonElement>
    @GET("donor/partner/initiatives") suspend fun initiatives(): Response<JsonElement>
    @GET("donor/partner/spend") suspend fun spend(): Response<JsonElement>
    @GET("donor/partner/our-people") suspend fun people(): Response<JsonElement>
    @GET("donor/partner/teams") suspend fun teams(): Response<JsonElement>
    @GET("donor/partner/users") suspend fun users(): Response<JsonElement>
    @GET("donor/partner/exports") suspend fun exports(): Response<JsonElement>
    @POST("donor/partner/users/{user_id}/remove") suspend fun remove(@Path("user_id") id: String, @Body body: JsonObject): Response<JsonElement>
    @POST("donor/partner/sign-out") suspend fun signOut(@Body body: JsonObject): Response<JsonElement>
}

private interface PartnerFiles {
    @GET("donor/partner/logo") suspend fun logo(): Response<ResponseBody>
    @GET("donor/partner/report.pdf") suspend fun report(): Response<ResponseBody>
    @GET("donor/partner/reports/{report_id}/report.pdf") suspend fun retained(@Path("report_id") id: String): Response<ResponseBody>
}

/**
 * The partner portal over the generated client, under the partner session. A 401 means the session ended (expired
 * or revoked): it is forgotten so the app returns to partner sign-in.
 */
class WirePartner(
    origin: String,
    private val session: PartnerSession,
    private val baseClient: OkHttpClient = defaultNativeClient(),
) : PartnerService {
    private val url: HttpUrl = validatedOrigin(origin)

    private suspend fun <T> perform(maxBodyBytes: Long = 1_048_576, operation: suspend (Retrofit) -> T): T {
        val retrofit = nativeRetrofit(url, baseClient, session.token(), maxBodyBytes)
        return try { decoding { operation(retrofit) } }
        catch (ended: AccountFailure.SignedOut) { session.clear(); throw ended }
    }

    private fun <T> Response<T>.partnerBody(): T {
        when (code()) {
            403 -> { errorBody()?.close(); throw AccountFailure.Forbidden }
            404 -> { errorBody()?.close(); throw AccountFailure.NotFound }
        }
        return requireBody(conflict = AccountFailure.Conflict)
    }

    private suspend fun read(call: suspend PartnerReads.() -> Response<JsonElement>): JsonObject =
        perform { it.create(PartnerReads::class.java).call().partnerBody().asObjectOrInvalid() }

    override suspend fun overview(): PartnerOverview {
        val o = read { overview() }
        return PartnerOverview(
            organization = o.str("organization"), description = o.optStr("description") ?: "",
            role = PartnerRole.from(o.str("role")), companyCode = o.str("company_code"),
            committed = VerifiedMoney(o.long("committed_minor"), o.bool("committed_verified")),
            deployed = VerifiedMoney(o.long("deployed_minor"), o.bool("deployed_verified")),
            deployedPercent = o.long("deployed_percent").toIntOrInvalid(),
            peopleReached = VerifiedCount(o.long("people_reached"), o.bool("people_reached_verified")),
            employeesEnrolled = o.optLong("employees_enrolled"), workforceSize = o.long("workforce_size").toIntOrInvalid(),
            enrolledPercent = o.optLong("enrolled_percent")?.toIntOrInvalid(),
            updatedAt = o.optTime("updated_at")?.let { Date.from(it.toInstant()) }, hasLogo = o.bool("has_logo"),
            userId = o.str("user_id"),
        )
    }

    override suspend fun initiatives(): List<PartnerInitiative> = read { initiatives() }.array("initiatives").map { element ->
        val i = element.asObjectOrInvalid()
        PartnerInitiative(
            id = i.str("id"), title = i.str("title"), summary = i.str("summary"), status = InitiativeStatus.from(i.str("status")),
            region = i.str("region"), committedMinor = i.long("committed_minor"), deployedMinor = i.long("deployed_minor"),
            metrics = i.array("metrics").map {
                val m = it.asObjectOrInvalid()
                PartnerMetric(m.str("label"), m.long("value"), PartnerMetric.Unit.from(m.str("unit")))
            },
        )
    }

    override suspend fun spend(): List<PartnerSpendCategory> = read { spend() }.array("categories").map {
        val c = it.asObjectOrInvalid()
        PartnerSpendCategory(c.str("category"), c.long("amount_minor"))
    }

    override suspend fun people(): PartnerPeople {
        val p = read { people() }
        return PartnerPeople(
            p.long("enrolled"), p.long("workforce_size").toIntOrInvalid(), p.long("given_minor"), p.long("matched_minor"),
            p.long("avg_streak_days"), p.long("volunteer_minutes"), p.long("teams"), suppressed = p.bool("suppressed"),
        )
    }

    override suspend fun teams(): List<TeamStanding> = read { teams() }.array("teams").map {
        val t = it.asObjectOrInvalid()
        TeamStanding(t.str("team_id"), t.str("name"), t.long("active_givers"), t.long("annualized_minor"),
            t.long("avg_streak_days"), t.long("volunteer_minutes"))
    }

    override suspend fun users(): List<PartnerUser> = read { users() }.array("users").map {
        val u = it.asObjectOrInvalid()
        PartnerUser(u.str("id"), u.str("email"), u.str("name"), PartnerRole.from(u.str("role")))
    }

    override suspend fun inviteUser(email: String, name: String, role: PartnerRole) {
        perform { it.create(PartnerApi::class.java).partnerInviteUser(PartnerInviteUserRequest(email, name, role.name)).partnerBody() }
    }

    override suspend fun removeUser(id: String) {
        perform { it.create(PartnerReads::class.java).remove(id, JsonObject()).partnerBody() }
    }

    override suspend fun saveProfile(organization: String, description: String) {
        perform { it.create(PartnerApi::class.java).partnerSaveProfile(PartnerSaveProfileRequest(organization, description)).partnerBody() }
    }

    override suspend fun saveWorkforce(size: Int) {
        perform { it.create(PartnerApi::class.java).employeeWorkforce(EmployeeWorkforceRequest(size)).partnerBody() }
    }

    override suspend fun logo(): ByteArray? = try {
        perform { it.create(PartnerFiles::class.java).logo().partnerBody().use(ResponseBody::bytes) }
    } catch (_: AccountFailure.NotFound) { null }

    override suspend fun saveLogo(png: ByteArray?) {
        val dataUrl = png?.let { "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(it) } ?: ""
        perform { it.create(PartnerApi::class.java).partnerSaveLogo(PartnerSaveLogoRequest(dataUrl)).partnerBody() }
    }

    override suspend fun exports(): List<PartnerExport> = read { exports() }.array("exports").map {
        val e = it.asObjectOrInvalid()
        PartnerExport(e.str("id"), e.time("created_at"), e.str("exported_by"), e.long("committed_minor"), e.long("deployed_minor"), e.bool("downloadable"))
    }

    override suspend fun report(): ByteArray =
        perform(PDF_LIMIT) { it.create(PartnerFiles::class.java).report().partnerBody().use(ResponseBody::bytes) }

    override suspend fun retainedReport(id: String): ByteArray =
        perform(PDF_LIMIT) { it.create(PartnerFiles::class.java).retained(id).partnerBody().use(ResponseBody::bytes) }

    /** The local session is forgotten first, so the partner is signed out on this device even when the server
     *  cannot be reached; the server session then simply expires. */
    override suspend fun signOut() {
        val bearer = runCatching { session.token() }.getOrNull()
        session.clear()
        if (bearer == null) return
        runCatching { decoding { nativeRetrofit(url, baseClient, bearer).create(PartnerReads::class.java).signOut(JsonObject()).body() } }
    }

    override suspend fun registerPushDevice(token: String) {
        perform {
            it.create(AccountApi::class.java).partnerRegisterPushDevice(
                PartnerRegisterPushDeviceRequest(PartnerRegisterPushDeviceRequest.Platform.ANDROID, token, environment = null),
            ).partnerBody()
        }
    }

    override suspend fun unregisterPushDevice() {
        perform { it.create(AccountApi::class.java).partnerUnregisterPushDevice().partnerBody() }
    }

    private companion object { const val PDF_LIMIT = 16L * 1_048_576 }
}
