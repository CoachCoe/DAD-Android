package org.dad.foundation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.UUID

/**
 * The giving routes, read as raw JSON on purpose. The generated models cannot express what the server
 * sends here: `RecurringDetails.subscription` is `anyOf [Subscription, null]`, `AmountOptions` and
 * `PaymentUpdate` are `oneOf`, and `Subscription.status` is an enum that Gson silently turns into `null`
 * when Stripe sends a value the contract does not list (`paused`, `trialing`). Reading JSON and mapping
 * it explicitly lets an unknown status degrade gracefully instead of failing the whole response.
 */
internal interface GivingApi {
    @GET("donor/me/recurring") suspend fun recurring(): Response<JsonElement>
    @GET("donor/me/recurring/amount") suspend fun amountOptions(): Response<JsonElement>
    @POST("donor/me/recurring/amount")
    suspend fun changeAmount(@Header("Idempotency-Key") key: String, @Body body: JsonObject): Response<JsonElement>
    @POST("donor/me/recurring/cancel")
    suspend fun cancelRecurring(@Header("Idempotency-Key") key: String, @Body body: JsonObject): Response<JsonElement>
    @POST("donor/me/recurring/checkout")
    suspend fun startRecurringCheckout(@Header("Idempotency-Key") key: String, @Body body: JsonObject): Response<JsonElement>
    @GET("donor/me/payment-method") suspend fun paymentMethod(): Response<JsonElement>
    @POST("donor/me/payment-method/setup") suspend fun beginPaymentUpdate(@Header("Idempotency-Key") key: String): Response<JsonElement>
}

/**
 * One Idempotency-Key per distinct giving command, held until the command reaches a definitive
 * outcome. A command that ends in a timeout, a 429, a 503 or a lost response is retried with the SAME
 * key (the server then returns the original outcome and never repeats the effect); a success or a
 * definitive refusal (401/403/409/422) retires the key so the next command is a fresh one.
 */
class IdempotencyLedger {
    private val keys = HashMap<String, String>()
    @Synchronized fun key(fingerprint: String): String = keys.getOrPut(fingerprint) { UUID.randomUUID().toString() }
    @Synchronized fun settle(fingerprint: String) { keys.remove(fingerprint) }
    @Synchronized fun isHolding(fingerprint: String): Boolean = keys.containsKey(fingerprint)
}

/**
 * Real giving service (PRD G-1..G-11, D-5): `recurring`, `amountOptions`, `changeAmount`,
 * `cancelRecurring`, `startRecurringCheckout`, `paymentMethod` and `beginPaymentUpdate`, each authorized
 * through [ProductClient] (single refresh + one retry on 401). The app never handles card data: card
 * entry happens on a Stripe-hosted page opened in a Custom Tab.
 *
 * Status mapping: 401 -> signed out; 403 -> [AccountFailure.Forbidden]; 409 -> [AccountFailure.Conflict]
 * (billing moved, or a checkout is already open; the model re-reads); 422 -> invalid input; 429 (with or
 * without a body: the ingress limiter sends none) -> rate limited; 503 with a `code` -> payments not
 * configured (do not retry); any other status, or a body that does not decode -> unavailable / invalid
 * response. Error bodies are never surfaced. Cancellation propagates untouched.
 */
class WireGiving(
    private val product: ProductClient,
    private val ledger: IdempotencyLedger = IdempotencyLedger(),
    /** The referral token a link visit kept (contract 2.10), credited at checkout as the website's cookie is. */
    private val referral: () -> String? = { null },
) : GivingService {

    override suspend fun recurring(): RecurringState = product.perform { retrofit ->
        mapRecurring(retrofit.create(GivingApi::class.java).recurring().givingBody().asObjectOrInvalid())
    }

    override suspend fun amountOffer(): AmountOffer? = product.perform { retrofit ->
        val json = retrofit.create(GivingApi::class.java).amountOptions().givingBody().asObjectOrInvalid()
        when (json.str("state")) {
            "ready" -> AmountOffer(
                subscriptionId = json.str("subscription_id"),
                nextAmountMinor = json.long("amount_minor").toIntOrInvalid(),
                effectiveAt = json.time("effective_at").toDate(),
                pending = json.optObj("change")?.let { change ->
                    AmountOffer.Pending(
                        amountMinor = change.long("amount_minor").toIntOrInvalid(),
                        previousMinor = change.long("previous_minor").toIntOrInvalid(),
                        effectiveAt = change.time("effective_at").toDate(),
                        confirmed = change.bool("confirmed"),
                    )
                },
            )
            // `unavailable`, and any state this build does not know: the server cannot offer a change.
            else -> null
        }
    }

    override suspend fun changeAmount(subscriptionId: String, amountMinor: Int): RecurringState {
        if (!subscriptionId.isUuid() || GiftAmount.ofMonthlyMinor(amountMinor) == null) throw AccountFailure.InvalidInput
        // The command echoes what the matching GET returned, so read it fresh; a subscription that is no
        // longer the one on screen is a conflict, not something to send.
        val offer = amountOffer() ?: throw AccountFailure.Unavailable
        if (!offer.subscriptionId.equals(subscriptionId, ignoreCase = true)) throw AccountFailure.Conflict
        val body = JsonObject().apply {
            addProperty("subscription_id", offer.subscriptionId)
            addProperty("amount_minor", amountMinor.toLong())
            addProperty("previous_minor", offer.nextAmountMinor.toLong())
            addProperty("effective_at", offer.effectiveAt.toIsoString())
        }
        val fingerprint = "amount:${offer.subscriptionId}:$amountMinor:${offer.nextAmountMinor}:${offer.effectiveAt.time}"
        command(fingerprint) { key ->
            product.perform { retrofit -> retrofit.create(GivingApi::class.java).changeAmount(key, body).givingBody() }
        }
        return recurring()
    }

    override suspend fun cancel(subscriptionId: String, periodEnd: Date): RecurringState {
        if (!subscriptionId.isUuid()) throw AccountFailure.InvalidInput
        val body = JsonObject().apply {
            addProperty("subscription_id", subscriptionId)
            addProperty("period_end", periodEnd.toIsoString())
        }
        return command("cancel:$subscriptionId:${periodEnd.time}") { key ->
            product.perform { retrofit ->
                mapRecurring(retrofit.create(GivingApi::class.java).cancelRecurring(key, body).givingBody().asObjectOrInvalid())
            }
        }
    }

    override suspend fun startGift(amountMinor: Int): PaymentPath {
        if (GiftAmount.ofMonthlyMinor(amountMinor) == null) throw AccountFailure.InvalidInput
        val referral = referral()
        val body = JsonObject().apply {
            addProperty("amount_minor", amountMinor.toLong())
            referral?.let { addProperty("referral_token", it) }
        }
        // A retry under one key must repeat its referral, so a changed referral is a different command.
        return command("checkout:$amountMinor:${referralFingerprint(referral)}") { key ->
            product.perform { retrofit ->
                val json = retrofit.create(GivingApi::class.java).startRecurringCheckout(key, body).givingBody().asObjectOrInvalid()
                json.str("session_id")
                json.time("expires_at")
                PaymentPath.WebCheckoutHandoff(hostedUrl(json.str("url")))
            }
        }
    }

    override suspend fun paymentMethod(): PaymentMethodState = product.perform { retrofit ->
        val json = retrofit.create(GivingApi::class.java).paymentMethod().givingBody().asObjectOrInvalid()
        PaymentMethodState(PaymentMethodState.Kind.parse(json.str("status")), json.optTime("completed_at")?.toDate())
    }

    override suspend fun beginPayment(): PaymentPath = command("payment-setup") { key ->
        product.perform { retrofit ->
            val json = retrofit.create(GivingApi::class.java).beginPaymentUpdate(key).givingBody().asObjectOrInvalid()
            val url = json.optStr("url")
            if (url != null) PaymentPath.WebCheckoutHandoff(hostedUrl(url))
            else PaymentPath.AlreadyFinished(
                PaymentMethodState(PaymentMethodState.Kind.parse(json.str("status")), json.optTime("completed_at")?.toDate()),
            )
        }
    }

    // MARK: helpers

    /**
     * Run a command under its Idempotency-Key. Definitive refusals and successes retire the key; a retryable
     * failure (429, 503, a network error, a malformed or empty body, or the caller giving up mid-flight) keeps
     * it, so the next attempt for the same command reuses it and the server replays the original outcome.
     */
    private suspend fun <T> command(fingerprint: String, run: suspend (String) -> T): T {
        val key = ledger.key(fingerprint)
        try {
            val value = run(key)
            ledger.settle(fingerprint)
            return value
        } catch (failure: AccountFailure) {
            when (failure) {
                AccountFailure.Forbidden, AccountFailure.Conflict, AccountFailure.InvalidInput,
                AccountFailure.PaymentsUnavailable, AccountFailure.SignedOut -> ledger.settle(fingerprint)
                else -> Unit
            }
            throw failure
        }
    }

    private fun Response<JsonElement>.givingBody(): JsonElement {
        if (isSuccessful) return body() ?: throw AccountFailure.InvalidResponse
        val (problemCode, _) = problem()
        throw when (code()) {
            401 -> AccountFailure.SignedOut
            403 -> AccountFailure.Forbidden
            409 -> AccountFailure.Conflict
            422 -> AccountFailure.InvalidInput
            429 -> AccountFailure.RateLimited
            // A 503 carrying a `code` is a feature this deployment has not configured: do not retry.
            503 -> if (problemCode != null) AccountFailure.PaymentsUnavailable else AccountFailure.Unavailable
            else -> AccountFailure.Unavailable
        }
    }

    private fun mapRecurring(json: JsonObject): RecurringState {
        val sub = json.optObj("subscription")
            ?: return if (json.optStr("state") == "preparing") RecurringState.Preparing else RecurringState.None
        val rawStatus = sub.optStr("status")
        // An ended subscription is not a live gift: there is nothing to change, cancel or show as active.
        if (SubscriptionStatus.isEnded(rawStatus)) return RecurringState.None
        val status = SubscriptionStatus.parse(rawStatus)
        val id = sub.str("id")
        val amount = sub.long("amount_minor").toIntOrInvalid()
        val periodEnd = sub.time("period_end").toDate()
        val stops = sub.optTime("stops_at")?.toDate()
        return if (stops != null) RecurringState.Ending(id, amount, periodEnd, stops, status)
        else RecurringState.Active(id, amount, periodEnd, status)
    }

    companion object {
        /** A hosted page must be https with no embedded credentials; anything else is never opened. */
        internal fun hostedUrl(text: String): String {
            val url = text.toHttpUrlOrNull() ?: throw AccountFailure.InvalidResponse
            if (!url.isHttps || url.host.isEmpty() || url.username.isNotEmpty() || url.password.isNotEmpty()) throw AccountFailure.InvalidResponse
            return url.toString()
        }
    }
}

private fun String.isUuid(): Boolean = length == 36 && runCatching { UUID.fromString(this) }.isSuccess
private fun OffsetDateTime.toDate(): Date = Date.from(toInstant())
private fun Date.toIsoString(): String = Instant.ofEpochMilli(time).toString()
