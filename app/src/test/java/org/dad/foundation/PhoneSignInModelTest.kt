package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSignInModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class Fake(var requestFailure: Exception? = null, var verifyFailure: Exception? = null) : SignInService {
        val verified = mutableListOf<Pair<String, String>>()
        override suspend fun requestPhoneCode(phone: String): String { requestFailure?.let { throw it }; return phone }
        override suspend fun verifyPhone(handle: String, code: String): Credentials {
            verified += handle to code
            verifyFailure?.let { throw it }
            return validCredentials("signed-in")
        }
    }
    private val passThrough: Establish = { produce -> produce() }

    private fun model(fake: Fake, establish: Establish = passThrough) = PhoneSignInModel(fake, establish)

    @Test fun obviouslyBadNumbersAndCodesStayOffTheWire() {
        val m = model(Fake())
        m.phone = "4155551234"
        assertFalse(m.canRequestCode)
        m.requestCode()
        assertEquals(PhoneSignInPhase.EnterPhone, m.phase)
        m.phone = "+14155551234"
        assertTrue(m.canRequestCode)
        m.code = "12345"
        assertFalse(m.canVerify)
        m.code = "12345a"
        assertFalse(m.canVerify)
    }

    @Test fun happyPathMovesEnterPhoneToEnterCodeToSignedIn() {
        val fake = Fake()
        var established: Credentials? = null
        val m = model(fake) { produce -> produce().also { established = it } }
        m.phone = "+14155551234"
        m.requestCode()
        assertEquals(PhoneSignInPhase.EnterCode("+14155551234"), m.phase)
        assertTrue(m.isAwaitingCode)
        m.code = "123456"
        m.verify()
        assertEquals(PhoneSignInPhase.SignedIn, m.phase)
        assertEquals("signed-in", established?.access)
        assertEquals(listOf("+14155551234" to "123456"), fake.verified)
    }

    @Test fun aWrongCodeKeepsTheHandleSoTheDonorRetriesWithoutANewSms() {
        val fake = Fake(verifyFailure = AccountFailure.SignedOut)
        val m = model(fake)
        m.phone = "+14155551234"; m.requestCode()
        m.code = "654321"; m.verify()
        assertEquals(PhoneSignInPhase.Failed("That code didn’t match. Check it and try again."), m.phase)
        assertTrue(m.isAwaitingCode)
        fake.verifyFailure = null
        m.retryCodeEntry()
        assertEquals(PhoneSignInPhase.EnterCode("+14155551234"), m.phase)
        m.code = "123456"; m.verify()
        assertEquals(PhoneSignInPhase.SignedIn, m.phase)
    }

    @Test fun requestFailuresMapToUserCopyAndReturnToThePhoneScreen() {
        val cases = mapOf<Exception, String>(
            AccountFailure.InvalidInput to "That phone number doesn’t look right. Check it and try again.",
            AccountFailure.RateLimited to "Too many attempts. Please wait a moment and try again.",
            AccountFailure.Unavailable to "We couldn’t reach DAD. Please try again in a moment.",
            AccountFailure.InvalidConfiguration to PhoneSignInModel.NOT_CONFIGURED,
            IllegalStateException("boom") to "Something went wrong. Please try again.",
        )
        cases.forEach { (failure, message) ->
            val m = model(Fake(requestFailure = failure))
            m.phone = "+14155551234"; m.requestCode()
            assertEquals(PhoneSignInPhase.Failed(message), m.phase)
            assertFalse(m.isAwaitingCode)
        }
    }

    @Test fun throttledVerificationShowsTheThrottleMessage() {
        val m = model(Fake(verifyFailure = AccountFailure.RateLimited))
        m.phone = "+14155551234"; m.requestCode(); m.code = "000000"; m.verify()
        assertEquals(PhoneSignInPhase.Failed("Too many attempts. Please wait a moment and try again."), m.phase)
    }

    @Test fun aSessionThatDiscardsTheSignInIsReportedAsFailureNotSuccess() {
        val m = model(Fake()) { throw AccountFailure.SignedOut }
        m.phone = "+14155551234"; m.requestCode(); m.code = "123456"; m.verify()
        assertTrue(m.phase is PhoneSignInPhase.Failed)
    }

    @Test fun cancellationIsNotReportedAsAFailure() {
        val m = model(Fake()) { throw CancellationException("cancelled") }
        m.phone = "+14155551234"; m.requestCode(); m.code = "123456"; m.verify()
        assertTrue(m.phase is PhoneSignInPhase.Verifying)
    }

    @Test fun unconfiguredSignInFailsClearlyAndNeverSucceeds() {
        val m = PhoneSignInModel(UnconfiguredSignIn, passThrough)
        m.phone = "+14155551234"; m.requestCode()
        assertEquals(PhoneSignInPhase.Failed(PhoneSignInModel.NOT_CONFIGURED), m.phase)
    }
}
