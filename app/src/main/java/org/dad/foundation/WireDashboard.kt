package org.dad.foundation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The donor-home reads. The pinned contract is hash-locked, but the generated Kotlin models for these
 * responses cannot express what the server sends: `dashboard` is a `oneOf` (preparing | ready) that the
 * generator flattens, nullable members (`streak`, `rank`, `network`, `referrals`, `given_minor`,
 * `next_offset`) become non-null types that Gson silently fills with 0/null, and `today-initiative` is
 * not generated at all. A zero for an unknown figure is exactly what this feature must never show, so
 * these five GETs are read as raw JSON and mapped by [WireDashboard] with explicit null handling.
 * Paths are the contract's; the conformer tests pin them.
 */
internal interface HomeReads {
    @GET("donor/me/dashboard") suspend fun dashboard(): Response<JsonElement>
    @GET("donor/me/today-initiative") suspend fun todayInitiative(): Response<JsonElement>
    @GET("donor/me/gifts") suspend fun gifts(@Query("offset") offset: Long): Response<JsonElement>
    @GET("donor/me/receipts/{gift_id}") suspend fun receipt(@Path("gift_id") giftId: String): Response<JsonElement>
    @GET("donor/me/annual-summary/{year}") suspend fun annualSummary(@Path("year") year: Int): Response<JsonElement>
}

/**
 * Real donor-home reads (`dashboard`, `todayInitiative`, `gifts`, `receipt`, `annualSummary`), each
 * authorized through [ProductClient] (single refresh + one retry on 401, then sign-out).
 *
 * Status mapping (the contract documents no 403 or 429 on these GETs, but the global rate limiter and app
 * guard can still answer them): 401 -> signed out; 429 -> rate limited; 404 on today's initiative -> null
 * (nothing in rotation); 404/422 on a gift read -> invalid input; every other status (403, 409, 5xx) ->
 * unavailable; a body that does not decode or is over 1 MiB -> invalid response. Error bodies are never read.
 */
class WireDashboard(private val product: ProductClient) : DashboardService {
    override suspend fun dashboard(): DashboardState = product.perform { retrofit ->
        val json = retrofit.create(HomeReads::class.java).dashboard().body_()
        mapDashboard(json.asObjectOrInvalid())
    }

    override suspend fun todayInitiative(): HomeInitiative? = product.perform { retrofit ->
        val response = retrofit.create(HomeReads::class.java).todayInitiative()
        if (response.code() == 404) { response.errorBody()?.close(); return@perform null }
        mapInitiative(response.body_().asObjectOrInvalid())
    }

    override suspend fun gifts(offset: Int): GiftHistoryPage {
        if (offset < 0) throw AccountFailure.InvalidInput
        return product.perform { retrofit ->
            val page = retrofit.create(HomeReads::class.java).gifts(offset.toLong()).body_().asObjectOrInvalid()
            val rows = page.array("gifts").map { mapGift(it.asObjectOrInvalid()) }
            // Defensive page cap: the server pages at 50, so a larger body is truncated rather than trusted.
            GiftHistoryPage(rows.take(PAGE_ROW_LIMIT), page.optLong("next_offset")?.let { it.toIntOrInvalid() })
        }
    }

    override suspend fun receipt(giftId: String): HomeReceipt {
        // A malformed id is a 404 server-side; refuse it before it becomes a path segment.
        if (runCatching { UUID.fromString(giftId) }.isFailure || giftId.length != 36) throw AccountFailure.InvalidInput
        return product.perform { retrofit ->
            val response = retrofit.create(HomeReads::class.java).receipt(giftId)
            if (response.code() == 404) { response.errorBody()?.close(); throw AccountFailure.InvalidInput }
            val body = response.body_().asObjectOrInvalid()
            val gift = body.obj("gift")
            HomeReceipt(
                gift = mapGift(gift),
                refunds = gift.array("refunds").map {
                    val refund = it.asObjectOrInvalid()
                    HomeRefund(refund.str("id"), refund.long("amount_minor"), refund.str("status"), refund.time("observed_at"))
                },
                taxReceipt = body.bool("tax_receipt"),
                asOf = body.time("as_of"),
            )
        }
    }

    override suspend fun annualSummary(year: Int): AnnualGivingSummary {
        if (year !in 2000..9999) throw AccountFailure.InvalidInput
        return product.perform { retrofit ->
            val response = retrofit.create(HomeReads::class.java).annualSummary(year)
            if (response.code() == 404) { response.errorBody()?.close(); throw AccountFailure.InvalidInput }
            val body = response.body_().asObjectOrInvalid()
            AnnualGivingSummary(
                year = body.long("year").toIntOrInvalid(), giftCount = body.array("gifts").size(),
                amountMinor = body.long("amount_minor"), refundedMinor = body.long("refunded_minor"),
                netMinor = body.long("net_minor"), taxReceipt = body.bool("tax_receipt"),
            )
        }
    }

    // MARK: mapping

    private fun mapDashboard(json: JsonObject): DashboardState {
        when (json.str("state")) {
            "preparing" -> return DashboardState.Preparing
            "ready" -> Unit
            else -> throw AccountFailure.InvalidResponse
        }
        val optedOut = json.bool("rankings_opted_out")
        val rank = json.optObj("rank")
        val impact = json.obj("impact")
        return DashboardState.Ready(HomeDashboard(
            computedAt = json.time("computed_at"),
            foundingDonor = json.bool("founding_donor"),
            streak = json.optObj("streak")?.let { streak ->
                HomeStreak(
                    current = streak.long("current").toIntOrInvalid(), atRisk = streak.bool("at_risk"),
                    graceEndsAt = streak.optTime("grace_ends_at"),
                    ladder = streak.optObj("ladder")?.let { ladder ->
                        HomeLadder(
                            days = ladder.long("days"), lastMilestone = ladder.long("last_milestone"),
                            nextMilestone = ladder.optLong("next_milestone"), daysToNext = ladder.optLong("days_to_next"),
                            bar = ladder.array("bar").map { it.asBooleanOrInvalid() },
                        )
                    },
                )
            },
            rank = when {
                optedOut -> RankLine.OptedOut
                rank == null -> RankLine.NotRankedYet
                else -> RankLine.Ranked(RankingStanding.fromDashboard(
                    rank.long("national_percentile").toIntOrInvalid(), rank.optStr("city"),
                    rank.optLong("city_rank")?.toIntOrInvalid(), rankingsOptedOut = false,
                ))
            },
            impact = ImpactTotals(
                givenMinor = impact.optLong("given_minor"),
                initiativesFunded = impact.long("initiatives_funded").clampToInt(),
                eventsVolunteered = impact.long("events_volunteered").clampToInt(),
            ),
            network = json.optObj("network")?.let { HomeNetwork(it.long("active_count"), it.long("annualized_minor")) },
            referrals = json.optObj("referrals")?.let { HomeReferrals(it.long("link"), it.long("irl"), it.long("total_minor")) },
        ))
    }

    private fun mapInitiative(json: JsonObject) = HomeInitiative(
        id = json.str("initiative_id"), slug = json.str("slug"), title = json.str("title"), summary = json.str("summary"),
        location = json.str("location"), kind = json.str("type"), startedOn = json.str("started_on"),
        metrics = json.array("metrics").map {
            val metric = it.asObjectOrInvalid()
            HomeMetric(
                label = metric.str("label"), value = metric.long("value"),
                // An unknown unit still shows its number; it is never dropped or guessed.
                unit = HomeMetric.Unit.entries.firstOrNull { unit -> unit.name == metric.optStr("unit") },
                verifiedOn = metric.str("verified_on"),
            )
        },
    )

    private fun mapGift(json: JsonObject) = HomeGift(
        id = json.str("id"), amountMinor = json.long("amount_minor"), paidAt = json.time("paid_at"),
        monthly = json.str("frequency") == "MONTHLY", refundedMinor = json.long("refunded_minor"), netMinor = json.long("net_minor"),
    )

    private companion object { const val PAGE_ROW_LIMIT = 100 }
}

// MARK: strict JSON readers. Anything absent or of the wrong type is an invalid response, never a default.

internal fun Response<JsonElement>.body_(): JsonElement = requireBody()

internal fun JsonElement.asObjectOrInvalid(): JsonObject = if (isJsonObject) asJsonObject else throw AccountFailure.InvalidResponse
internal fun JsonElement.asBooleanOrInvalid(): Boolean =
    if (isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else throw AccountFailure.InvalidResponse

internal fun JsonObject.value(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
internal fun JsonObject.str(key: String): String =
    value(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: throw AccountFailure.InvalidResponse
internal fun JsonObject.optStr(key: String): String? =
    value(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeUnless { it.isEmpty() }
internal fun JsonObject.bool(key: String): Boolean = value(key)?.asBooleanOrInvalid() ?: throw AccountFailure.InvalidResponse
internal fun JsonObject.long(key: String): Long = optLong(key) ?: throw AccountFailure.InvalidResponse
internal fun JsonObject.optLong(key: String): Long? {
    val element = value(key) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) throw AccountFailure.InvalidResponse
    return try { element.asBigDecimal.longValueExact() } catch (_: ArithmeticException) { throw AccountFailure.InvalidResponse }
}
internal fun JsonObject.obj(key: String): JsonObject = value(key)?.asObjectOrInvalid() ?: throw AccountFailure.InvalidResponse
internal fun JsonObject.optObj(key: String): JsonObject? = value(key)?.asObjectOrInvalid()
internal fun JsonObject.array(key: String): com.google.gson.JsonArray =
    value(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: throw AccountFailure.InvalidResponse
internal fun JsonObject.time(key: String): OffsetDateTime = optTime(key) ?: throw AccountFailure.InvalidResponse
internal fun JsonObject.optTime(key: String): OffsetDateTime? {
    val text = value(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
    return try { OffsetDateTime.parse(text) } catch (_: java.time.format.DateTimeParseException) { throw AccountFailure.InvalidResponse }
}
internal fun Long.toIntOrInvalid(): Int = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else throw AccountFailure.InvalidResponse
internal fun Long.clampToInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
