package org.dad.foundation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.net.URI
import java.util.UUID

/**
 * The engagement reads and the sharing write, read as raw JSON on purpose: `Conversion.kind` is a contract enum
 * the docs say clients must tolerate new values of (Gson would turn an unlisted one into null and fail the list),
 * and a null `next_milestone` / `earned_at` must stay unknown rather than become a default.
 */
internal interface EngagementApi {
    @GET("donor/me/badges") suspend fun badges(): Response<JsonElement>
    @GET("donor/me/network") suspend fun network(): Response<JsonElement>
    @GET("donor/me/sharing") suspend fun sharing(): Response<JsonElement>
    @POST("donor/me/sharing") suspend fun saveSharing(@Body body: JsonObject): Response<JsonElement>
    @GET("donor/me/conversions") suspend fun conversions(): Response<JsonElement>
    @GET("initiatives/{id}/spend") suspend fun spend(@Path("id") id: String): Response<JsonElement>
}

/**
 * Real engagement service (PRD A-3, I-*, K-*, N-*, R-*, B-*): `dashboard` (impact, rank, referral counts),
 * `donorBadges`, `donorNetwork`, `sharing` + `conversions` (the invite), `profile` + `savePreferences` (the
 * rankings choice), `saveSharing` (the public name) and `initiativeSpend`, each authorized through [ProductClient]
 * (single refresh + one retry on 401).
 *
 * [snapshot] reads the sections concurrently and keeps the ones that answered: a section whose read failed is
 * null in the snapshot (shown as unavailable), never an empty value or a zero. The whole load fails only when the
 * session ended, the work was cancelled, or every section failed.
 *
 * Status mapping ([failureForStatus]): 401 signed out; 403 forbidden; 404 not found (an initiative with no
 * published spend); 409 conflict (a stale preferences version, after one retry); 422 invalid input; 429 (with or
 * without a body: the ingress limiter sends none) rate limited; anything else, or a body that does not decode or is
 * over 1 MiB, unavailable / invalid response. Cancellation propagates untouched so the model keeps its prior state.
 * Error bodies are never surfaced and nothing here logs.
 */
class WireEngagement(private val product: ProductClient) : EngagementService {
    private val dashboard = WireDashboard(product)
    private val settings = WireSettings(product)

    override suspend fun snapshot(): EngagementSnapshot = coroutineScope {
        val dash = async { capture { dashboard.dashboard() } }
        val badges = async { capture { badges() } }
        val network = async { capture { network() } }
        val invite = async { capture { invite() } }
        val results = listOf(dash.await(), badges.await(), network.await(), invite.await())

        val failures = results.mapNotNull { it.exceptionOrNull() }
        // A signed-out load is not a partial one; neither is one where nothing answered.
        failures.firstOrNull { it is AccountFailure.SignedOut }?.let { throw it }
        if (failures.size == results.size) throw failures.first()

        var impact: ImpactTotals? = null
        var ranking: RankingStanding? = null
        var counts: HomeReferrals? = null
        dash.await().getOrNull()?.let { state ->
            when (state) {
                is DashboardState.Ready -> {
                    impact = state.dashboard.impact
                    counts = state.dashboard.referrals
                    ranking = when (val rank = state.dashboard.rank) {
                        is RankLine.Ranked -> rank.standing
                        RankLine.NotRankedYet -> RankingStanding(null, null, null)
                        RankLine.OptedOut -> RankingStanding.optedOut()
                    }
                }
                // Figures are not computed yet, but the donor's rankings choice is still theirs to make.
                DashboardState.Preparing -> {
                    val optedOut = capture { settings.load().preferences.rankingsOptedOut }
                    if (optedOut.exceptionOrNull() is AccountFailure.SignedOut) throw AccountFailure.SignedOut
                    optedOut.getOrNull()?.let { ranking = if (it) RankingStanding.optedOut() else RankingStanding(null, null, null) }
                }
            }
        }
        var referral: ReferralInfo? = null
        var needsName = false
        invite.await().getOrNull()?.let { found ->
            when (val outcome = found.outcome) {
                is InviteOutcome.NeedsName -> needsName = true
                is InviteOutcome.Code -> referral = ReferralInfo(
                    code = outcome.code, linkCount = counts?.link, irlCount = counts?.irl,
                    conversions = found.conversions, hasMoreConversions = found.more,
                )
                InviteOutcome.Unusable -> Unit
            }
        }
        EngagementSnapshot(
            impact = impact, badges = badges.await().getOrNull(), ranking = ranking, network = network.await().getOrNull(),
            referral = referral, needsPublicName = needsName,
        )
    }

    // MARK: sections

    internal suspend fun badges(): BadgeGrid = product.perform { retrofit ->
        val list = retrofit.create(EngagementApi::class.java).badges().successBody().asObjectOrInvalid().array("badges")
        BadgeGrid(
            list.map { it.asObjectOrInvalid() }
                .filter { it.optStr("code") != null && it.optStr("title") != null }
                .take(BADGE_LIMIT)
                .map { badge ->
                    Badge(
                        code = badge.str("code"), title = badge.str("title"), rule = badge.optStr("rule").orEmpty(),
                        perk = Badge.perkCopy(badge.optStr("perk").orEmpty()),
                        earned = badge.bool("earned"), earnedAt = badge.optTime("earned_at"),
                        city = badge.optStr("city"), displayTitle = badge.optStr("display_title").orEmpty(),
                    )
                },
        )
    }

    internal suspend fun network(): NetworkView = product.perform { retrofit ->
        val json = retrofit.create(EngagementApi::class.java).network().successBody().asObjectOrInvalid()
        NetworkView(
            activeCount = json.long("active_count").coerceAtLeast(0), annualizedMinor = json.long("annualized_minor").coerceAtLeast(0),
            nextMilestone = json.optObj("next_milestone")?.let {
                NetworkMilestone(it.str("label"), it.long("target").coerceAtLeast(0), it.long("remaining").coerceAtLeast(0))
            },
            invitees = json.array("invitees").take(INVITEE_LIMIT).map {
                val invitee = it.asObjectOrInvalid()
                NetworkInvitee(invitee.str("name"), invitee.bool("active"))
            },
            anonymousActive = json.long("anonymous_active").coerceAtLeast(0),
        )
    }

    private sealed interface InviteOutcome {
        data class Code(val code: String) : InviteOutcome
        data object NeedsName : InviteOutcome
        data object Unusable : InviteOutcome
    }
    private class Invite(val outcome: InviteOutcome, val conversions: List<Conversion>?, val more: Boolean)

    /**
     * The invite code (from `sharing`) and the recent conversions. A failed conversions read leaves the code usable
     * with the conversions unknown; a failed sharing read fails the invite section.
     */
    private suspend fun invite(): Invite {
        val sharing = product.perform { it.create(EngagementApi::class.java).sharing().successBody().asObjectOrInvalid() }
        val outcome = outcome(sharing)
        if (outcome !is InviteOutcome.Code) return Invite(outcome, null, false)
        val conversions = capture { conversions() }
        // Only an ended session stops the whole snapshot; any other failure leaves the conversions unknown.
        if (conversions.exceptionOrNull() is AccountFailure.SignedOut) throw AccountFailure.SignedOut
        val page = conversions.getOrNull()
        return Invite(outcome, page?.first, page?.second ?: false)
    }

    private fun outcome(sharing: JsonObject): InviteOutcome {
        val name = sharing.optStr("public_name")
        val link = sharing.optStr("link")
        if (name.isNullOrBlank() || link == null) return InviteOutcome.NeedsName
        // The code is the contract's `referral_code` (null exactly when the links are); the shareable link is rebuilt from the configured public origin.
        val code = Attribution.sanitized(sharing.optStr("referral_code") ?: return InviteOutcome.Unusable) ?: return InviteOutcome.Unusable
        return InviteOutcome.Code(code)
    }

    private suspend fun conversions(): Pair<List<Conversion>, Boolean> = product.perform { retrofit ->
        val json = retrofit.create(EngagementApi::class.java).conversions().successBody().asObjectOrInvalid()
        json.array("conversions").take(CONVERSION_LIMIT).map {
            val conversion = it.asObjectOrInvalid()
            // The kind is an open string: a value a newer server adds still shows as a conversion.
            Conversion(conversion.time("converted_at"), conversion.optStr("kind").orEmpty())
        } to (json.value("more")?.asBooleanOrInvalid() ?: false)
    }

    // MARK: commands

    override suspend fun setRankingOptIn(optIn: Boolean): RankingStanding {
        var retried = false
        while (true) {
            val wanted = settings.load().preferences.with("rankings_opted_out", !optIn)
            try {
                settings.savePreferencesKeepingConflict(wanted)
                break
            } catch (_: AccountFailure.Conflict) {
                // The profile changed between the read and the save (another device, the settings screen): read again once.
                if (retried) throw AccountFailure.Conflict
                retried = true
            }
        }
        if (!optIn) return RankingStanding.optedOut()
        // Back in the rankings: the server decides the position. If that read fails the choice still stands.
        val state = capture { dashboard.dashboard() }
        if (state.exceptionOrNull() is AccountFailure.SignedOut) throw AccountFailure.SignedOut
        val rank = (state.getOrNull() as? DashboardState.Ready)?.dashboard?.rank
        return (rank as? RankLine.Ranked)?.standing ?: RankingStanding(null, null, null)
    }

    override suspend fun choosePublicName(name: String) {
        val trimmed = name.trim()
        if (trimmed.length !in 1..MAX_PUBLIC_NAME) throw AccountFailure.InvalidInput
        val body = JsonObject().apply { addProperty("public_name", trimmed); addProperty("replace_code", false) }
        product.perform { it.create(EngagementApi::class.java).saveSharing(body).successBody() }
    }

    override suspend fun initiativeSpend(id: String): InitiativeSpend {
        // A malformed id is a 404 server-side; refuse it before it becomes a path segment.
        if (id.length != 36 || runCatching { UUID.fromString(id) }.isFailure) throw AccountFailure.InvalidInput
        return product.perform { retrofit ->
            val json = retrofit.create(EngagementApi::class.java).spend(id).successBody().asObjectOrInvalid()
            val total = json.long("total_minor")
            if (total < 0) throw AccountFailure.InvalidResponse
            InitiativeSpend(
                totalMinor = total,
                categories = json.array("categories").take(SPEND_CATEGORY_LIMIT).map {
                    val category = it.asObjectOrInvalid()
                    SpendCategory(category.str("category"), category.long("amount_minor").coerceAtLeast(0))
                },
            )
        }
    }

    /** A failed section is a value, so the others keep their answers; cancellation is never captured. */
    private suspend fun <T> capture(work: suspend () -> T): Result<T> =
        try { Result.success(work()) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }

    private companion object {
        /** Defensive caps: a larger body is truncated rather than trusted. */
        const val BADGE_LIMIT = 64
        const val INVITEE_LIMIT = 200
        const val CONVERSION_LIMIT = 50
        const val SPEND_CATEGORY_LIMIT = 100
    }
}
