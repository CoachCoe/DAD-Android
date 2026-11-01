package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialAuthModelsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val passThrough: Establish = { produce -> produce() }

    // MARK: rules

    @Test fun emailRuleMatchesTheSharedPlatformRule() {
        listOf("a@b.co", "alex.doe+tag@example.org", "x@sub.example.co.uk", "  a@b.co  ").forEach { assertTrue(it, CredentialRules.isPlausibleEmail(it)) }
        listOf("", "a", "a@", "@b.co", "a@b", "a@b.", "a@.co", "a@b..co", "a b@c.co", "a@@b.co", "a@b@c.co", "a@b .co",
            "a@" + "b".repeat(250) + ".co").forEach { assertFalse(it, CredentialRules.isPlausibleEmail(it)) }
        assertTrue(CredentialRules.isPlausibleEmail("a@" + "b".repeat(246) + ".co")) // 254 total
    }

    @Test fun passwordRulesCountBytesNotCharacters() {
        assertFalse(CredentialRules.isAcceptableNewPassword("a".repeat(11)))
        assertTrue(CredentialRules.isAcceptableNewPassword("a".repeat(12)))
        assertTrue(CredentialRules.isAcceptableNewPassword("a".repeat(72)))
        assertFalse(CredentialRules.isAcceptableNewPassword("a".repeat(73)))
        assertFalse(CredentialRules.isAcceptableNewPassword("é".repeat(37))) // 74 bytes
        assertTrue(CredentialRules.isAcceptableNewPassword("é".repeat(36)))  // 72 bytes
        assertFalse(CredentialRules.isSubmittablePassword(""))
        assertTrue(CredentialRules.isSubmittablePassword("x"))
        assertFalse(CredentialRules.isSubmittablePassword("x".repeat(73)))
    }

    @Test fun zipIsExactlyFiveDigits() {
        assertTrue(CredentialRules.isPlausibleZip("78701"))
        assertTrue(CredentialRules.isPlausibleZip(" 78701 "))
        listOf("7870", "787011", "7870a", "", "78 701").forEach { assertFalse(it, CredentialRules.isPlausibleZip(it)) }
    }

    // MARK: sign-in link

    private class FakeLink(var failure: Exception? = null) : SignInLinkService {
        val emails = mutableListOf<String>()
        override suspend fun requestSignInLink(email: String) { emails += email; failure?.let { throw it } }
    }

    @Test fun signInLinkSendsTheTrimmedAddressOnlyWhenPlausible() {
        val service = FakeLink()
        val model = SignInLinkModel(service)
        model.email = "alex@example"
        assertFalse(model.canSubmit)
        model.submit()
        assertTrue(service.emails.isEmpty())
        model.email = " alex@example.org "
        model.submit()
        assertEquals(listOf("alex@example.org"), service.emails)
        assertEquals(SignInLinkPhase.Sent("alex@example.org"), model.phase)
        model.edit()
        assertEquals(SignInLinkPhase.Editing, model.phase)
    }

    @Test fun signInLinkFailuresExplainThemselves() {
        mapOf(
            AccountFailure.InvalidInput to "Check your email address, then try again.",
            AccountFailure.RateLimited to "Too many attempts. Please wait a moment and try again.",
            AccountFailure.Unavailable to "We couldn’t send your sign-in link right now. Please try again in a moment.",
        ).forEach { (failure, message) ->
            val model = SignInLinkModel(FakeLink(failure)).apply { email = "alex@example.org" }
            model.submit()
            assertEquals(SignInLinkPhase.Failed(message), model.phase)
        }
    }

    // MARK: signup

    private class FakeSignup(var failure: Exception? = null, var gate: CompletableDeferred<Unit>? = null) : SignupService {
        val forms = mutableListOf<SignupForm>()
        override suspend fun requestSignup(form: SignupForm) { forms += form; gate?.await(); failure?.let { throw it } }
    }

    private fun filled(service: SignupService) = SignupModel(service).apply {
        name = "  Alex Doe "; email = " alex@example.org "; zip = "78701"; city = " Austin "
    }

    @Test fun signupStaysOffTheWireUntilEveryFieldIsValid() {
        val fake = FakeSignup()
        val m = SignupModel(fake)
        m.submit()
        assertTrue(fake.forms.isEmpty())
        m.name = "Alex"; m.email = "alex@example.org"; m.zip = "78701"
        assertFalse(m.canSubmit) // city missing
        m.city = "Austin"
        assertTrue(m.canSubmit)
        m.email = "nope"; assertFalse(m.canSubmit)
        m.email = "a@b.co"; m.zip = "1234"; assertFalse(m.canSubmit)
        m.zip = "78701"; m.name = "x".repeat(101); assertFalse(m.canSubmit)
    }

    @Test fun signupTrimsAndMovesToSent() {
        val fake = FakeSignup()
        val m = filled(fake)
        m.submit()
        assertEquals(SignupForm("Alex Doe", "alex@example.org", "78701", "Austin"), fake.forms.single())
        assertEquals(SignupPhase.Sent("alex@example.org"), m.phase)
    }

    @Test fun signupFailuresShowCopyAndKeepTheValues() {
        mapOf(
            AccountFailure.InvalidInput to "Check your name, email, city and ZIP code, then try again.",
            AccountFailure.RateLimited to "Too many attempts. Please wait a moment and try again.",
            AccountFailure.Unavailable to "We couldn’t send your sign-in link right now. Please try again in a moment.",
            AccountFailure.InvalidConfiguration to PhoneSignInModel.NOT_CONFIGURED,
        ).forEach { (failure, copy) ->
            val m = filled(FakeSignup(failure))
            m.submit()
            assertEquals(SignupPhase.Failed(copy), m.phase)
            assertEquals("alex@example.org", m.email.trim())
            m.edit()
            assertEquals(SignupPhase.Editing, m.phase)
        }
        val m = filled(FakeSignup(IllegalStateException("boom")))
        m.submit()
        assertEquals(SignupPhase.Failed("Something went wrong. Please try again."), m.phase)
    }

    @Test fun signupIsSingleFlight() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeSignup(gate = gate)
        val m = filled(fake)
        m.submit(); m.submit()
        assertEquals(SignupPhase.Submitting, m.phase)
        assertEquals(1, fake.forms.size)
        gate.complete(Unit)
        assertTrue(m.phase is SignupPhase.Sent)
    }

    // MARK: password sign-in

    private class FakePasswordSignIn(var failure: Exception? = null) : PasswordSignInService {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun signIn(email: String, password: String): Credentials {
            calls += email to password
            failure?.let { throw it }
            return validCredentials("signed-in")
        }
    }

    @Test fun passwordSignInHappyPathEstablishesAndClearsThePassword() {
        val fake = FakePasswordSignIn()
        var established: Credentials? = null
        val m = PasswordSignInModel(fake) { produce -> produce().also { established = it } }
        m.email = " alex@example.org "; m.password = "correct-horse-battery"
        assertTrue(m.canSubmit)
        m.submit()
        assertEquals(PasswordSignInPhase.SignedIn, m.phase)
        assertEquals("signed-in", established?.access)
        assertEquals(listOf("alex@example.org" to "correct-horse-battery"), fake.calls)
        assertEquals("", m.password)
    }

    @Test fun passwordSignInStaysOffTheWireWhenInvalid() {
        val fake = FakePasswordSignIn()
        val m = PasswordSignInModel(fake, passThrough)
        m.email = "nope"; m.password = "pw"
        assertFalse(m.canSubmit); m.submit()
        m.email = "a@b.co"; m.password = ""
        assertFalse(m.canSubmit); m.submit()
        m.password = "x".repeat(73)
        assertFalse(m.canSubmit)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun passwordSignInFailuresClearThePasswordAndShowCopy() {
        mapOf(
            AccountFailure.SignedOut to "The email or password is incorrect.",
            AccountFailure.InvalidInput to "Check your email and password, then try again.",
            AccountFailure.RateLimited to "Too many attempts. Please wait a moment and try again.",
            AccountFailure.Unavailable to "We couldn’t reach DAD. Please try again in a moment.",
            AccountFailure.InvalidConfiguration to PhoneSignInModel.NOT_CONFIGURED,
        ).forEach { (failure, copy) ->
            val m = PasswordSignInModel(FakePasswordSignIn(failure), passThrough)
            m.email = "a@b.co"; m.password = "some-password"
            m.submit()
            assertEquals(PasswordSignInPhase.Failed(copy), m.phase)
            assertEquals("", m.password)
            assertEquals("a@b.co", m.email)
            assertFalse(m.phase.toString().contains("some-password"))
        }
    }

    @Test fun aSignInDiscardedBySessionChangeIsAFailureNotASignedInState() {
        val m = PasswordSignInModel(FakePasswordSignIn()) { throw AccountFailure.SignedOut }
        m.email = "a@b.co"; m.password = "pw"
        m.submit()
        assertTrue(m.phase is PasswordSignInPhase.Failed)
    }

    @Test fun cancellationIsNotShownAsAnError() {
        val m = PasswordSignInModel(FakePasswordSignIn(CancellationException("x"))) { produce -> produce() }
        m.email = "a@b.co"; m.password = "pw"
        runCatching { m.submit() }
        assertFalse(m.phase is PasswordSignInPhase.Failed)
    }

    // MARK: change password

    private class FakePasswords(var failure: Exception? = null) : PasswordSettingsService {
        val calls = mutableListOf<Pair<String?, String>>()
        override suspend fun setPassword(current: String?, new: String) { calls += current to new; failure?.let { throw it } }
    }

    private fun ready(service: PasswordSettingsService) = PasswordChangeModel(service).apply {
        currentPassword = "old-password-1"; newPassword = "brand-new-password"; confirmPassword = "brand-new-password"
    }

    @Test fun changePasswordValidatesBeforeSending() {
        val fake = FakePasswords()
        val m = PasswordChangeModel(fake)
        m.newPassword = "short"; m.confirmPassword = "short"
        assertFalse(m.canSave); assertEquals("Use 12 to 72 characters.", m.newPasswordHint)
        m.newPassword = "brand-new-password"
        assertNull(m.newPasswordHint)
        assertTrue(m.confirmMismatch); assertFalse(m.canSave)
        m.confirmPassword = "brand-new-password"
        assertTrue(m.canSave)
        m.currentPassword = "x".repeat(73); assertFalse(m.canSave)
        m.save()
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun savedClearsEveryFieldAndSendsNullWhenThereIsNoCurrent() {
        val fake = FakePasswords()
        val m = ready(fake)
        m.save()
        assertEquals(listOf<Pair<String?, String>>("old-password-1" to "brand-new-password"), fake.calls)
        assertEquals(PasswordChangePhase.Saved, m.phase)
        assertEquals("", m.newPassword); assertEquals("", m.currentPassword); assertEquals("", m.confirmPassword)
        val first = ready(fake).apply { currentPassword = "" }
        first.save()
        assertNull(fake.calls.last().first)
    }

    @Test fun aWrongCurrentPasswordStaysSignedIn() {
        val m = ready(FakePasswords(AccountFailure.PasswordIncorrect))
        m.save()
        assertEquals(PasswordChangePhase.Failed("Your current password didn’t match. Check it and try again."), m.phase)
        assertFalse(m.signedOut)
        assertEquals("brand-new-password", m.newPassword) // values kept so the donor can fix the typo
    }

    @Test fun onlyARealSignedOutLeavesTheShell() {
        val m = ready(FakePasswords(AccountFailure.SignedOut))
        m.save()
        assertTrue(m.signedOut)
        assertEquals(PasswordChangePhase.Failed("Please sign in again."), m.phase)
    }

    @Test fun changePasswordFailureCopy() {
        mapOf(
            AccountFailure.InvalidInput to "Use a password of 12 to 72 characters.",
            AccountFailure.RateLimited to "Too many attempts. Please wait a moment and try again.",
            AccountFailure.Unavailable to "We couldn’t save your password right now. Please try again in a moment.",
        ).forEach { (failure, copy) ->
            val m = ready(FakePasswords(failure))
            m.save()
            assertEquals(PasswordChangePhase.Failed(copy), m.phase)
            assertFalse(m.signedOut)
        }
    }

    // MARK: pasted link

    private val router = LinkRouter("https://dad.example", """{"account_paths":["/account/dashboard"],"proof_paths":["/account/claim","/account/email-change"],"partner_proof_paths":["/partner/accept"],"claim_return_paths":["/account/dashboard"],"notification_paths":[]}""")
    private val token = "A".repeat(43)

    @Test fun onlyADadClaimLinkIsAcceptedFromAPaste() {
        assertEquals(token, PastedLink.claimToken("  https://dad.example/account/claim#token=$token \n", router))
        assertNull(PastedLink.claimToken("https://evil.example/account/claim#token=$token", router))
        assertNull(PastedLink.claimToken("https://dad.example/account/email-change#token=$token", router))
        assertNull(PastedLink.claimToken("https://dad.example/account/claim#token=short", router))
        assertNull(PastedLink.claimToken("hello", router))
        assertNull(PastedLink.claimToken("", router))
        assertNull(PastedLink.claimToken("https://dad.example/account/claim#token=$token", null))
    }
}
