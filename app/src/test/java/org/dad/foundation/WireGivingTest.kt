package org.dad.foundation

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real conformer + real AccountSession + real Retrofit stack over a scripted transport. */
class WireGivingTest {
    private class Rig(scope: kotlinx.coroutines.CoroutineScope, handler: (Recorded) -> Pair<Int, String>) {
        val http = ScriptedHttp(handler)
        val store = RecordingStore(validCredentials())
        val client = http.client()
        val session = AccountSession(WireAPI(TEST_ORIGIN, client), store, scope)
        val ledger = IdempotencyLedger()
        val wire = WireGiving(ProductClient(TEST_ORIGIN, session, client), ledger)
        fun posts(path: String) = http.requests.filter { it.method == "POST" && it.path == path }
    }

    private suspend fun failure(block: suspend () -> Any?): Throwable? = runCatching { block() }.exceptionOrNull()

    private val sub = "11111111-2222-4333-8444-555555555555"
    private val periodEnd = "2026-11-30T12:00:00Z"
    private fun periodEndDate(): Date = Date.from(java.time.Instant.parse(periodEnd))

    private fun subscription(status: String = "active", stops: String = "null", amount: Long = 15000) =
        """{"id":"$sub","amount_minor":$amount,"currency":"USD","period_end":"$periodEnd","status":"$status","stops_at":$stops}"""

    private fun recurring(subscription: String = subscription(), state: String? = null) =
        """{"mode":"test",${state?.let { "\"state\":\"$it\"," }.orEmpty()}"observed_at":"2026-10-30T12:00:00Z","subscription":$subscription}"""

    private fun offer(change: String = "null", amount: Long = 15000) =
        """{"state":"ready","subscription_id":"$sub","amount_minor":$amount,"effective_at":"$periodEnd","change":$change}"""

    private val checkout = """{"url":"https://checkout.stripe.com/c/pay/cs_test_1","session_id":"cs_test_1","expires_at":"2026-10-31T13:00:00Z"}"""
    private val ok = 200 to "{}"

    // --- recurring ---

    @Test fun anActiveSubscriptionMapsAndSendsTheNativeBearerAndHeader() = runTest {
        val rig = Rig(backgroundScope) { 200 to recurring() }
        val state = rig.wire.recurring() as RecurringState.Active
        assertEquals(sub, state.subscriptionId)
        assertEquals(15000, state.amountMinor)
        assertEquals(periodEndDate(), state.periodEnd)
        assertEquals(SubscriptionStatus.ACTIVE, state.status)
        val sent = rig.http.requests.single()
        assertEquals("GET", sent.method)
        assertEquals("/donor/me/recurring", sent.path)
        assertEquals("Bearer access-0", sent.headers["authorization"])
        assertEquals("native", sent.headers["x-dad-client"])
    }

    @Test fun aSubscriptionWithAStopDateIsEnding() = runTest {
        val rig = Rig(backgroundScope) { 200 to recurring(subscription(stops = "\"$periodEnd\"")) }
        val state = rig.wire.recurring() as RecurringState.Ending
        assertEquals(periodEndDate(), state.stopsAt)
        assertTrue(state.isEnding)
    }

    @Test fun noSubscriptionIsNoneAndPreparingIsItsOwnState() = runTest {
        assertEquals(RecurringState.None, Rig(backgroundScope) { 200 to recurring("null", state = "ready") }.wire.recurring())
        assertEquals(RecurringState.None, Rig(backgroundScope) { 200 to """{"mode":"test","subscription":null}""" }.wire.recurring())
        assertEquals(RecurringState.Preparing, Rig(backgroundScope) { 200 to recurring("null", state = "preparing") }.wire.recurring())
    }

    @Test fun everyDocumentedStatusIsDecoded() = runTest {
        val expected = mapOf(
            "active" to SubscriptionStatus.ACTIVE, "past_due" to SubscriptionStatus.PAST_DUE, "unpaid" to SubscriptionStatus.UNPAID,
            "incomplete" to SubscriptionStatus.INCOMPLETE,
        )
        for ((raw, status) in expected) {
            val state = Rig(backgroundScope) { 200 to recurring(subscription(raw)) }.wire.recurring() as RecurringState.Active
            assertEquals(raw, status, state.status)
        }
        assertTrue(SubscriptionStatus.PAST_DUE.needsPaymentAttention)
        assertTrue(SubscriptionStatus.UNPAID.needsPaymentAttention)
        assertFalse(SubscriptionStatus.ACTIVE.needsPaymentAttention)
        assertFalse(SubscriptionStatus.INCOMPLETE.needsPaymentAttention)
    }

    @Test fun endedStatusesAreNotALiveGift() = runTest {
        for (raw in listOf("canceled", "incomplete_expired")) {
            assertEquals(raw, RecurringState.None, Rig(backgroundScope) { 200 to recurring(subscription(raw)) }.wire.recurring())
        }
    }

    @Test fun anUnknownStatusDoesNotFailTheResponse() = runTest {
        // Stripe has more statuses than the contract lists: "paused", "trialing", and whatever it adds next.
        for (raw in listOf("paused", "trialing", "something_new", "")) {
            val state = Rig(backgroundScope) { 200 to recurring(subscription(raw)) }.wire.recurring() as RecurringState.Active
            assertEquals("'$raw'", SubscriptionStatus.OTHER, state.status)
            assertEquals(15000, state.amountMinor)
        }
        // A missing or non-string status is tolerated the same way.
        val missing = """{"mode":"test","subscription":{"id":"$sub","amount_minor":15000,"currency":"USD","period_end":"$periodEnd","stops_at":null}}"""
        assertEquals(SubscriptionStatus.OTHER, (Rig(backgroundScope) { 200 to missing }.wire.recurring() as RecurringState.Active).status)
    }

    @Test fun malformedEmptyAndMistypedBodiesAreInvalidResponses() = runTest {
        val bad = listOf(
            "", "not json", "[]", "null", "\"text\"", "{\"mode\":\"test\",\"subscription\":\"oops\"}",
            """{"subscription":{"id":"$sub","amount_minor":"15000","period_end":"$periodEnd","status":"active","stops_at":null}}""",
            """{"subscription":{"id":"$sub","amount_minor":15000.5,"period_end":"$periodEnd","status":"active","stops_at":null}}""",
            """{"subscription":{"amount_minor":15000,"period_end":"$periodEnd","status":"active","stops_at":null}}""",
            """{"subscription":{"id":"$sub","amount_minor":15000,"period_end":"yesterday","status":"active","stops_at":null}}""",
            """{"subscription":{"id":"$sub","amount_minor":15000,"period_end":"$periodEnd","status":"active","stops_at":"soon"}}""",
        )
        for (body in bad) {
            assertEquals("body: $body", AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to body }.wire.recurring() })
        }
    }

    @Test fun anEmptyBodyOnACommandIsInvalidAndKeepsTheKey() = runTest {
        val rig = Rig(backgroundScope) { 200 to "" }
        assertEquals(AccountFailure.InvalidResponse, failure { rig.wire.startGift(15000) })
        assertTrue(rig.ledger.isHolding("checkout:15000:none"))
    }

    @Test fun everyStatusMapsOnTheReads() = runTest {
        val table = listOf(
            403 to AccountFailure.Forbidden, 404 to AccountFailure.Unavailable, 409 to AccountFailure.Conflict,
            422 to AccountFailure.InvalidInput, 429 to AccountFailure.RateLimited, 500 to AccountFailure.Unavailable,
            503 to AccountFailure.Unavailable,
        )
        for ((code, expected) in table) {
            assertEquals("status $code", expected, failure { Rig(backgroundScope) { code to "" }.wire.recurring() })
        }
    }

    @Test fun anUnauthorizedReadRefreshesOnceThenSignsOut() = runTest {
        var calls = 0
        val rig = Rig(backgroundScope) { r ->
            when (r.path) {
                "/donor/refresh" -> 200 to nativeSessionJson("access-new", "refresh-new")
                else -> if (++calls == 1) 401 to "" else 200 to recurring()
            }
        }
        assertTrue(rig.wire.recurring() is RecurringState.Active)
        assertEquals(listOf("Bearer access-0", "Bearer access-new"), rig.http.requests.filter { it.path == "/donor/me/recurring" }.map { it.headers["authorization"] })

        val dead = Rig(backgroundScope) { r -> if (r.path == "/donor/refresh") 200 to nativeSessionJson("access-new") else 401 to "" }
        assertEquals(AccountFailure.SignedOut, failure { dead.wire.recurring() })
        assertFalse(dead.session.isSignedIn())
    }

    // --- amount options ---

    @Test fun amountOptionsMapsTheOfferAndAPendingChange() = runTest {
        val change = """{"amount_minor":30000,"confirmed":false,"effective_at":"$periodEnd","previous_minor":15000,"request_id":"$sub"}"""
        val rig = Rig(backgroundScope) { 200 to offer(change) }
        val result = rig.wire.amountOffer()!!
        assertEquals(sub, result.subscriptionId)
        assertEquals(15000, result.nextAmountMinor)
        assertEquals(periodEndDate(), result.effectiveAt)
        assertEquals(AmountOffer.Pending(30000, 15000, periodEndDate(), confirmed = false), result.pending)
        assertEquals("/donor/me/recurring/amount", rig.http.requests.single().path)
        assertNull(Rig(backgroundScope) { 200 to offer() }.wire.amountOffer()!!.pending)
    }

    @Test fun anUnavailableOrUnknownOfferStateIsNullNotAFailure() = runTest {
        assertNull(Rig(backgroundScope) { 200 to """{"state":"unavailable"}""" }.wire.amountOffer())
        assertNull(Rig(backgroundScope) { 200 to """{"state":"brand_new"}""" }.wire.amountOffer())
    }

    @Test fun aMalformedOfferIsInvalid() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "" }.wire.amountOffer() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to """{"state":"ready"}""" }.wire.amountOffer() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "[]" }.wire.amountOffer() })
    }

    // --- change amount ---

    @Test fun changeAmountReadsTheOfferThenPostsItsEchoWithAKeyAndReReads() = runTest {
        val rig = Rig(backgroundScope) { r ->
            when {
                r.method == "GET" && r.path == "/donor/me/recurring/amount" -> 200 to offer()
                r.method == "POST" -> 200 to """{"amount_minor":30000,"confirmed":true,"effective_at":"$periodEnd","previous_minor":15000,"request_id":"$sub"}"""
                else -> 200 to recurring(subscription(amount = 30000))
            }
        }
        val state = rig.wire.changeAmount(sub, 30000) as RecurringState.Active
        assertEquals(30000, state.amountMinor)
        assertEquals(
            listOf("GET /donor/me/recurring/amount", "POST /donor/me/recurring/amount", "GET /donor/me/recurring"),
            rig.http.requests.map { "${it.method} ${it.path}" },
        )
        val post = rig.http.requests[1]
        val body = JsonParser.parseString(post.body).asJsonObject
        assertEquals(sub, body["subscription_id"].asString)
        assertEquals(30000L, body["amount_minor"].asLong)
        assertEquals(15000L, body["previous_minor"].asLong)
        assertEquals(periodEnd, body["effective_at"].asString)
        assertEquals(4, body.size())
        assertTrue(post.headers["idempotency-key"]!!.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        assertEquals("native", post.headers["x-dad-client"])
        assertEquals("Bearer access-0", post.headers["authorization"])
    }

    @Test fun changeAmountRefusesBadInputWithoutARequest() = runTest {
        val rig = Rig(backgroundScope) { ok }
        for ((id, amount) in listOf("nope" to 30000, sub to 100, sub to 3001, sub to 93000, sub to 0)) {
            assertEquals("$id/$amount", AccountFailure.InvalidInput, failure { rig.wire.changeAmount(id, amount) })
        }
        assertTrue(rig.http.requests.isEmpty())
    }

    @Test fun aSubscriptionThatIsNoLongerTheOneOnScreenIsAConflictAndNothingIsSent() = runTest {
        val rig = Rig(backgroundScope) { 200 to offer().replace(sub, "99999999-2222-4333-8444-555555555555") }
        assertEquals(AccountFailure.Conflict, failure { rig.wire.changeAmount(sub, 30000) })
        assertTrue(rig.posts("/donor/me/recurring/amount").isEmpty())
    }

    @Test fun anUnavailableOfferStopsChangeAmount() = runTest {
        val rig = Rig(backgroundScope) { 200 to """{"state":"unavailable"}""" }
        assertEquals(AccountFailure.Unavailable, failure { rig.wire.changeAmount(sub, 30000) })
        assertTrue(rig.posts("/donor/me/recurring/amount").isEmpty())
    }

    @Test fun changeAmountReusesItsKeyAfterA503AndRetiresItOnSuccess() = runTest {
        var attempts = 0
        val rig = Rig(backgroundScope) { r ->
            when {
                r.method == "GET" && r.path == "/donor/me/recurring/amount" -> 200 to offer()
                r.method == "POST" -> if (++attempts == 1) 503 to "" else 200 to "{}"
                else -> 200 to recurring()
            }
        }
        assertEquals(AccountFailure.Unavailable, failure { rig.wire.changeAmount(sub, 30000) })
        rig.wire.changeAmount(sub, 30000)
        val keys = rig.posts("/donor/me/recurring/amount").map { it.headers["idempotency-key"] }
        assertEquals(2, keys.size)
        assertEquals("a retry after a 503 must reuse the key", keys[0], keys[1])
        // Settled: the same command again is a new one.
        rig.wire.changeAmount(sub, 30000)
        assertNotEquals(keys[0], rig.posts("/donor/me/recurring/amount")[2].headers["idempotency-key"])
    }

    @Test fun everyChangeAmountStatusMaps() = runTest {
        val table = listOf(
            401 to AccountFailure.SignedOut, 403 to AccountFailure.Forbidden, 409 to AccountFailure.Conflict,
            422 to AccountFailure.InvalidInput, 429 to AccountFailure.RateLimited, 500 to AccountFailure.Unavailable,
        )
        for ((code, expected) in table) {
            val rig = Rig(backgroundScope) { r ->
                when {
                    r.path == "/donor/refresh" -> 200 to nativeSessionJson("access-new")
                    r.method == "GET" -> 200 to offer()
                    else -> code to ""
                }
            }
            assertEquals("status $code", expected, failure { rig.wire.changeAmount(sub, 30000) })
        }
    }

    // --- cancel ---

    @Test fun cancelPostsTheSubscriptionAndPeriodEndAndMapsTheResult() = runTest {
        val rig = Rig(backgroundScope) { 200 to recurring(subscription(stops = "\"$periodEnd\"")) }
        val state = rig.wire.cancel(sub, periodEndDate()) as RecurringState.Ending
        assertEquals(sub, state.subscriptionId)
        val post = rig.http.requests.single()
        assertEquals("POST", post.method)
        assertEquals("/donor/me/recurring/cancel", post.path)
        val body = JsonParser.parseString(post.body).asJsonObject
        assertEquals(sub, body["subscription_id"].asString)
        assertEquals(periodEnd, body["period_end"].asString)
        assertEquals(2, body.size())
        assertNotNull(post.headers["idempotency-key"])
    }

    @Test fun cancelKeepsItsKeyOnRetryableFailuresAndRetiresItOnDefinitiveOnes() = runTest {
        var next = 429
        val rig = Rig(backgroundScope) { if (next == 200) 200 to recurring(subscription(stops = "\"$periodEnd\"")) else next to "" }
        suspend fun attempt() = failure { rig.wire.cancel(sub, periodEndDate()) }
        assertEquals(AccountFailure.RateLimited, attempt())          // empty-body ingress 429
        next = 503
        assertEquals(AccountFailure.Unavailable, attempt())          // 503 without a code
        next = 200
        assertNull(attempt())
        val keys = rig.posts("/donor/me/recurring/cancel").map { it.headers["idempotency-key"] }
        assertEquals(1, keys.toSet().size)
        assertEquals(3, keys.size)
        // 403, 409 and 422 retire the key, so the attempt after each is a fresh command with a new key.
        for (code in listOf(403, 409, 422)) {
            next = 503
            attempt()
            val held = rig.posts("/donor/me/recurring/cancel").last().headers["idempotency-key"]
            next = code
            attempt()
            assertEquals("status $code reuses the held key for its own attempt", held, rig.posts("/donor/me/recurring/cancel").last().headers["idempotency-key"])
            next = 503
            attempt()
            assertNotEquals("status $code retires the key", held, rig.posts("/donor/me/recurring/cancel").last().headers["idempotency-key"])
        }
    }

    @Test fun cancelRefusesAnInvalidSubscriptionId() = runTest {
        val rig = Rig(backgroundScope) { ok }
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.cancel("nope", Date()) })
        assertTrue(rig.http.requests.isEmpty())
    }

    // --- start a gift ---

    @Test fun startGiftPostsOnlyTheAmountWithAKeyAndReturnsTheHostedUrl() = runTest {
        val rig = Rig(backgroundScope) { 201 to checkout }
        val path = rig.wire.startGift(15000) as PaymentPath.WebCheckoutHandoff
        assertEquals("https://checkout.stripe.com/c/pay/cs_test_1", path.url)
        val post = rig.http.requests.single()
        assertEquals("POST", post.method)
        assertEquals("/donor/me/recurring/checkout", post.path)
        assertEquals("""{"amount_minor":15000}""", post.body)
        assertEquals("Bearer access-0", post.headers["authorization"])
        assertEquals("native", post.headers["x-dad-client"])
        assertNotNull(post.headers["idempotency-key"])
        assertNull(post.headers["cookie"])
        assertNull(post.headers["origin"])
    }

    @Test fun startGiftCarriesTheKeptReferralAndKeysByIt() = runTest {
        var checkouts = 0
        val http = ScriptedHttp { if (it.path == "/donor/me/recurring/checkout" && checkouts++ == 0) 503 to "{}" else 201 to checkout }
        val client = http.client()
        val session = AccountSession(WireAPI(TEST_ORIGIN, client), RecordingStore(validCredentials()), backgroundScope)
        var kept = "r" + "1".repeat(42)
        val wire = WireGiving(ProductClient(TEST_ORIGIN, session, client), referral = { kept })
        assertEquals(AccountFailure.Unavailable, failure { wire.startGift(6000) })
        kept = "r" + "2".repeat(42)
        wire.startGift(6000)
        val posts = http.requests.filter { it.path == "/donor/me/recurring/checkout" }
        assertEquals(listOf("""{"amount_minor":6000,"referral_token":"r${"1".repeat(42)}"}""", """{"amount_minor":6000,"referral_token":"r${"2".repeat(42)}"}"""),
            posts.map { it.body })
        assertNotEquals("a changed referral reused the key", posts[0].headers["idempotency-key"], posts[1].headers["idempotency-key"])
    }

    @Test fun startGiftRefusesAnAmountOffTheDailyRuleWithoutARequest() = runTest {
        val rig = Rig(backgroundScope) { 201 to checkout }
        for (amount in listOf(0, 100, 2999, 3001, 93000, -3000)) {
            assertEquals("$amount", AccountFailure.InvalidInput, failure { rig.wire.startGift(amount) })
        }
        assertTrue(rig.http.requests.isEmpty())
    }

    @Test fun startGiftOnlyOpensAnHttpsUrlWithoutCredentials() = runTest {
        for (url in listOf("http://checkout.stripe.com/x", "ftp://x.test/a", "https://user:pw@checkout.stripe.com/x", "javascript:alert(1)", "/relative", "", "not a url")) {
            val body = checkout.replace("https://checkout.stripe.com/c/pay/cs_test_1", url)
            assertEquals("url '$url'", AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 201 to body }.wire.startGift(15000) })
        }
    }

    @Test fun aCheckoutMissingItsSessionOrExpiryIsInvalid() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 201 to """{"url":"https://checkout.stripe.com/x","expires_at":"2026-10-31T13:00:00Z"}""" }.wire.startGift(15000) })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 201 to """{"url":"https://checkout.stripe.com/x","session_id":"cs"}""" }.wire.startGift(15000) })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 201 to "[]" }.wire.startGift(15000) })
    }

    @Test fun everyStartGiftStatusMaps() = runTest {
        val table = listOf(
            403 to AccountFailure.Forbidden, 409 to AccountFailure.Conflict, 422 to AccountFailure.InvalidInput,
            429 to AccountFailure.RateLimited, 500 to AccountFailure.Unavailable, 413 to AccountFailure.Unavailable, 415 to AccountFailure.Unavailable,
        )
        for ((code, expected) in table) {
            assertEquals("status $code", expected, failure { Rig(backgroundScope) { code to "" }.wire.startGift(15000) })
        }
        // 429 from the ingress limiter has no body at all; a documented one carries a Problem body. Both are rate limits.
        assertEquals(AccountFailure.RateLimited, failure { Rig(backgroundScope) { 429 to """{"detail":"slow down"}""" }.wire.startGift(15000) })
    }

    @Test fun aUnconfiguredProcessorIsDistinctFromATransientOutage() = runTest {
        val unconfigured = Rig(backgroundScope) { 503 to """{"detail":"Payments are not configured.","code":"PAYMENTS_UNCONFIGURED"}""" }
        assertEquals(AccountFailure.PaymentsUnavailable, failure { unconfigured.wire.startGift(15000) })
        assertFalse("not retryable: the key is retired", unconfigured.ledger.isHolding("checkout:15000:none"))
        val outage = Rig(backgroundScope) { 503 to """{"detail":"try later"}""" }
        assertEquals(AccountFailure.Unavailable, failure { outage.wire.startGift(15000) })
        assertTrue(outage.ledger.isHolding("checkout:15000:none"))
    }

    @Test fun startGiftReusesItsKeyOnA503A429AndALostResponseThenRetiresItOnSuccess() = runTest {
        var step = 0
        val rig = Rig(backgroundScope) {
            when (++step) {
                1 -> 503 to ""
                2 -> 429 to ""
                3 -> 200 to ""            // lost / empty response
                4 -> 201 to checkout
                else -> 201 to checkout
            }
        }
        repeat(3) { assertNotNull(failure { rig.wire.startGift(15000) }) }
        rig.wire.startGift(15000)
        val keys = rig.posts("/donor/me/recurring/checkout").map { it.headers["idempotency-key"] }
        assertEquals(4, keys.size)
        assertEquals("the same command keeps one key", 1, keys.toSet().size)
        rig.wire.startGift(15000)
        assertNotEquals("a success retires the key", keys[0], rig.posts("/donor/me/recurring/checkout")[4].headers["idempotency-key"])
    }

    @Test fun startGiftRetiresItsKeyOn403And409And422() = runTest {
        for (code in listOf(403, 409, 422)) {
            var current = code
            val rig = Rig(backgroundScope) { current to "" }
            failure { rig.wire.startGift(15000) }
            assertFalse("status $code", rig.ledger.isHolding("checkout:15000:none"))
            current = 503
            failure { rig.wire.startGift(15000) }
            val keys = rig.posts("/donor/me/recurring/checkout").map { it.headers["idempotency-key"] }
            assertNotEquals("status $code", keys[0], keys[1])
        }
    }

    @Test fun aDifferentAmountIsADifferentCommandWithItsOwnKey() = runTest {
        val rig = Rig(backgroundScope) { 503 to "" }
        failure { rig.wire.startGift(15000) }
        failure { rig.wire.startGift(30000) }
        val keys = rig.posts("/donor/me/recurring/checkout").map { it.headers["idempotency-key"] }
        assertNotEquals("a key reused with a different amount would be a 409", keys[0], keys[1])
    }

    @Test fun anUnauthorizedStartRefreshesOnceAndKeepsTheSameKey() = runTest {
        var calls = 0
        val rig = Rig(backgroundScope) { r ->
            if (r.path == "/donor/refresh") 200 to nativeSessionJson("access-new") else if (++calls == 1) 401 to "" else 201 to checkout
        }
        rig.wire.startGift(15000)
        val posts = rig.posts("/donor/me/recurring/checkout")
        assertEquals(listOf("Bearer access-0", "Bearer access-new"), posts.map { it.headers["authorization"] })
        assertEquals(1, posts.map { it.headers["idempotency-key"] }.toSet().size)
    }

    // --- payment method ---

    @Test fun paymentMethodMapsEveryStatusAndToleratesUnknownOnes() = runTest {
        for (kind in PaymentMethodState.Kind.entries) {
            val state = Rig(backgroundScope) { 200 to """{"mode":"test","status":"${kind.name}","completed_at":null}""" }.wire.paymentMethod()
            assertEquals(kind, state.kind)
            assertNull(state.completedAt)
        }
        val applied = Rig(backgroundScope) { 200 to """{"mode":"test","status":"APPLIED","completed_at":"$periodEnd"}""" }.wire.paymentMethod()
        assertEquals(periodEndDate(), applied.completedAt)
        assertEquals(PaymentMethodState.Kind.NONE, Rig(backgroundScope) { 200 to """{"status":"FUTURE","completed_at":null}""" }.wire.paymentMethod().kind)
        assertTrue(PaymentMethodState(PaymentMethodState.Kind.OPEN).isInFlight)
        assertTrue(PaymentMethodState(PaymentMethodState.Kind.PENDING).isInFlight)
        assertFalse(PaymentMethodState(PaymentMethodState.Kind.APPLIED).isInFlight)
    }

    @Test fun aMalformedPaymentMethodIsInvalid() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "" }.wire.paymentMethod() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "{}" }.wire.paymentMethod() })
    }

    @Test fun beginPaymentReturnsTheHostedUrlOrAnAlreadyFinishedUpdate() = runTest {
        val rig = Rig(backgroundScope) { 200 to """{"url":"https://billing.stripe.com/p/session/bps_1"}""" }
        val path = rig.wire.beginPayment() as PaymentPath.WebCheckoutHandoff
        assertEquals("https://billing.stripe.com/p/session/bps_1", path.url)
        val post = rig.http.requests.single()
        assertEquals("POST", post.method)
        assertEquals("/donor/me/payment-method/setup", post.path)
        assertEquals("", post.body)
        assertNotNull(post.headers["idempotency-key"])

        val finished = Rig(backgroundScope) { 200 to """{"url":null,"status":"APPLIED","completed_at":"$periodEnd"}""" }
        val done = finished.wire.beginPayment() as PaymentPath.AlreadyFinished
        assertEquals(PaymentMethodState.Kind.APPLIED, done.state.kind)
        assertEquals(periodEndDate(), done.state.completedAt)
        val cancelled = Rig(backgroundScope) { 200 to """{"url":null,"status":"CANCELLED","completed_at":null}""" }
        assertEquals(PaymentMethodState.Kind.CANCELLED, (cancelled.wire.beginPayment() as PaymentPath.AlreadyFinished).state.kind)
    }

    @Test fun beginPaymentRejectsAnUnsafeUrlAndMapsStatuses() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to """{"url":"http://insecure.test/x"}""" }.wire.beginPayment() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to """{"url":null}""" }.wire.beginPayment() })
        assertEquals(AccountFailure.Conflict, failure { Rig(backgroundScope) { 409 to "" }.wire.beginPayment() })
        assertEquals(AccountFailure.Forbidden, failure { Rig(backgroundScope) { 403 to "" }.wire.beginPayment() })
        assertEquals(AccountFailure.InvalidInput, failure { Rig(backgroundScope) { 422 to "" }.wire.beginPayment() })
        assertEquals(AccountFailure.RateLimited, failure { Rig(backgroundScope) { 429 to "" }.wire.beginPayment() })
        assertEquals(AccountFailure.PaymentsUnavailable, failure { Rig(backgroundScope) { 503 to """{"code":"PAYMENTS_UNCONFIGURED"}""" }.wire.beginPayment() })
    }

    // --- errors never leak ---

    @Test fun serverErrorBodiesAreNeverSurfaced() = runTest {
        val error = failure { Rig(backgroundScope) { 500 to """{"detail":"alex@example.org card 4242"}""" }.wire.recurring() }
        assertEquals(AccountFailure.Unavailable, error)
        assertFalse(error.toString().contains("alex"))
    }

    // --- cancellation ---

    @Test fun cancellingACommandInFlightRethrowsCancellationAndKeepsTheKey() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rig = Rig(backgroundScope) { _ ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            201 to checkout
        }
        val thrown = AtomicReference<Throwable?>()
        val start = async(Dispatchers.Default) {
            try { rig.wire.startGift(15000) } catch (error: Throwable) { thrown.set(error); throw error }
        }
        withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
        start.cancel()
        release.countDown()
        withContext(Dispatchers.IO) { start.join() }
        assertTrue("expected CancellationException, got ${thrown.get()}", thrown.get() is CancellationException)
        assertTrue("the outcome is unknown, so a retry must reuse the key", rig.ledger.isHolding("checkout:15000:none"))
        assertTrue(rig.session.isSignedIn())
    }

    // --- ledger ---

    @Test fun theLedgerIssuesVersion4KeysPerFingerprintAndForgetsSettledOnes() {
        val ledger = IdempotencyLedger()
        val first = ledger.key("a")
        assertEquals(first, ledger.key("a"))
        assertNotEquals(first, ledger.key("b"))
        assertTrue(first.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        ledger.settle("a")
        assertNotEquals(first, ledger.key("a"))
    }
}
