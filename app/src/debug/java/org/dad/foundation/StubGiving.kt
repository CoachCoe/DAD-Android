package org.dad.foundation

/**
 * The DEBUG stub backend's giving routes: one stateful monthly gift behind the REAL [WireGiving] conformer. The
 * hosted Stripe pages are stubbed by the UI, and [complete] plays what the Stripe webhook would do once the donor
 * finishes there (a gift starts, a card is applied). Shaped like the contract's responses. Debug source set only.
 *
 * `giftAmount` is the integer-cent monthly charge; null means no gift. Under [StubGift.ACTIVE_FAIL_FIRST] the
 * first recurring read is a 503, so the UI sees the error and its retry.
 */
internal class StubGiving(scenario: StubGift) {
    private var giftAmount: Int? = if (scenario == StubGift.NONE) null else START_AMOUNT
    private var ending = false
    private var payment = "NONE"
    private var checkoutAmount: Int? = null
    private var checkoutKey: String? = null
    private var failFirst = scenario == StubGift.ACTIVE_FAIL_FIRST

    /** A gift that has not ended still renews, so the server refuses account deletion while it is live. */
    val blocksDeletion: Boolean get() = giftAmount != null && !ending

    fun complete(purpose: HostedHandoff.Purpose) {
        when (purpose) {
            HostedHandoff.Purpose.START_GIFT -> { giftAmount = checkoutAmount ?: START_AMOUNT; ending = false; checkoutAmount = null; checkoutKey = null }
            HostedHandoff.Purpose.UPDATE_PAYMENT -> payment = "APPLIED"
        }
    }

    private fun recurringJson(): String {
        val amount = giftAmount ?: return """{"mode":"test","state":"ready","subscription":null}"""
        val stops = if (ending) "\"$PERIOD_END\"" else "null"
        return """{"mode":"test","state":"ready","observed_at":"2026-10-30T12:00:00Z","subscription":{"id":"$SUBSCRIPTION","amount_minor":$amount,"currency":"USD","period_end":"$PERIOD_END","status":"active","stops_at":$stops}}"""
    }

    fun answer(method: String, path: String, body: String, idempotencyKey: String?): Pair<Int, String> = when (method to path) {
        "GET" to "/donor/me/recurring" ->
            if (failFirst) { failFirst = false; 503 to """{"detail":"unavailable"}""" } else 200 to recurringJson()
        "GET" to "/donor/me/recurring/amount" -> {
            val amount = giftAmount
            if (amount == null || ending) 200 to """{"state":"unavailable"}"""
            else 200 to """{"state":"ready","subscription_id":"$SUBSCRIPTION","amount_minor":$amount,"effective_at":"$PERIOD_END","change":null}"""
        }
        "POST" to "/donor/me/recurring/amount" -> {
            val requested = Regex("\"amount_minor\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            when {
                giftAmount == null || ending || !body.contains(SUBSCRIPTION) -> 409 to "{}"
                requested == null || requested % 3000 != 0 -> 422 to "{}"
                else -> {
                    val previous = giftAmount
                    giftAmount = requested
                    200 to """{"amount_minor":$requested,"confirmed":true,"effective_at":"$PERIOD_END","previous_minor":$previous,"request_id":"$SUBSCRIPTION"}"""
                }
            }
        }
        "POST" to "/donor/me/recurring/cancel" ->
            if (giftAmount == null || !body.contains(SUBSCRIPTION)) 409 to "{}" else { ending = true; 200 to recurringJson() }
        "POST" to "/donor/me/recurring/checkout" -> {
            val requested = Regex("\"amount_minor\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            when {
                idempotencyKey.isNullOrBlank() -> 422 to "{}"
                requested == null || requested % 3000 != 0 -> 422 to "{}"
                // A gift that has not yet stopped (even one set to end), or a different checkout still open: the same refusals the server gives.
                giftAmount != null -> 409 to "{}"
                checkoutKey != null && checkoutKey != idempotencyKey -> 409 to "{}"
                else -> {
                    checkoutKey = idempotencyKey
                    checkoutAmount = requested
                    201 to """{"url":"https://checkout.stub.invalid/c/pay/cs_stub_1","session_id":"cs_stub_1","expires_at":"2026-10-31T13:00:00Z"}"""
                }
            }
        }
        "GET" to "/donor/me/payment-method" ->
            200 to """{"mode":"test","status":"$payment","completed_at":${if (payment == "APPLIED") "\"2026-10-30T12:05:00Z\"" else "null"}}"""
        "POST" to "/donor/me/payment-method/setup" ->
            if (giftAmount == null) 409 to "{}" else { payment = "OPEN"; 200 to """{"url":"https://billing.stub.invalid/p/session/bps_stub_1"}""" }
        else -> 404 to """{"detail":"not found"}"""
    }

    private companion object {
        const val START_AMOUNT = 15000
        const val SUBSCRIPTION = "11111111-2222-4333-8444-555555555555"
        const val PERIOD_END = "2026-11-30T12:00:00Z"
    }
}
