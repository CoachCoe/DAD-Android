package org.dad.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The application form model: it validates the way the website's form does before a submit is allowed, locks onto a
 * frozen attempt after an unconfirmed send so a retry repeats it exactly, and unlocks on a definitive refusal so the
 * applicant can correct and send anew.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartnerApplicationModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Service(var failure: ApplicationSubmitFailure? = null) : PartnerApplicationService {
        val sent = mutableListOf<PartnerApplicationAttempt>()
        override suspend fun submit(attempt: PartnerApplicationAttempt) {
            sent += attempt
            failure?.let { throw it }
        }
    }

    private fun fill(model: PartnerApplicationModel) {
        model.organization = "Acme"
        model.website = "acme.example"
        model.contactName = "Ada Admin"
        model.title = "COO"
        model.workEmail = "ada@acme.example"
        model.phone = "+1 555 0100"
        model.workforce = "240"
    }

    @Test fun submitIsBlockedUntilEveryRequiredFieldIsValid() {
        val model = PartnerApplicationModel(Service())
        assertFalse(model.canSubmit)
        assertNull(model.application)
        fill(model)
        assertNull("a non-https host without a dot is rejected", PartnerApplicationModel.normalizedWebsite("localhost"))
        assertTrue(model.canSubmit)
        assertEquals("https://acme.example", model.application?.website)
        model.workEmail = "not-an-email"
        assertNull(model.application)
    }

    @Test fun aSuccessfulSubmitReachesSubmittedAndUnlocks() = runTest {
        val service = Service()
        val model = PartnerApplicationModel(service)
        fill(model)
        model.submit()
        advanceUntilIdle()
        assertEquals(ApplicationSubmitPhase.Submitted, model.phase)
        assertEquals(1, service.sent.size)
        assertFalse(model.isLocked)
    }

    @Test fun anUnconfirmedSendLocksTheFormAndARetryRepeatsTheSameAttempt() = runTest {
        val service = Service(ApplicationSubmitFailure.Unconfirmed)
        val model = PartnerApplicationModel(service)
        fill(model)
        model.submit()
        advanceUntilIdle()
        assertTrue(model.phase is ApplicationSubmitPhase.Failed)
        assertTrue("the form locks onto the attempt", model.isLocked)
        service.failure = null
        model.submit()
        advanceUntilIdle()
        assertEquals(ApplicationSubmitPhase.Submitted, model.phase)
        assertEquals("the same attempt (key + receipt) was resent", service.sent[0], service.sent[1])
    }

    @Test fun aDefinitiveRefusalUnlocksSoTheApplicantCanCorrectAndResend() = runTest {
        for (failure in listOf(ApplicationSubmitFailure.Invalid, ApplicationSubmitFailure.Conflict, ApplicationSubmitFailure.Forbidden)) {
            val service = Service(failure)
            val model = PartnerApplicationModel(service)
            fill(model)
            model.submit()
            advanceUntilIdle()
            assertTrue("$failure shows an error", model.phase is ApplicationSubmitPhase.Failed)
            assertFalse("$failure unlocks the form", model.isLocked)
        }
    }

    @Test fun aThrottleKeepsTheFormLockedSoTheWaitingAttemptIsResentOnRetry() = runTest {
        val service = Service(ApplicationSubmitFailure.RateLimited(90))
        val model = PartnerApplicationModel(service)
        fill(model)
        model.submit()
        advanceUntilIdle()
        val failed = model.phase as ApplicationSubmitPhase.Failed
        assertTrue(failed.message.contains("2 minutes"))
        assertTrue("a throttle keeps the attempt to resend", model.isLocked)
    }

    @Test fun theWaitMessageRoundsSecondsToMinutesAndFallsBackWhenUnknown() {
        assertTrue(PartnerApplicationModel.waitMessage(30).contains("30 seconds"))
        assertTrue(PartnerApplicationModel.waitMessage(1).contains("1 second"))
        assertTrue(PartnerApplicationModel.waitMessage(61).contains("2 minutes"))
        assertTrue(PartnerApplicationModel.waitMessage(null).contains("wait a moment"))
    }
}
