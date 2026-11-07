package org.dad.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler

private enum class AuthStep { RoleSelect, Methods, Phone, EmailLink, Password, Signup, Partner }

/**
 * The unauthenticated stack: role select -> sign-in methods -> the chosen method's flow. Verification
 * runs through [establish] so the session (not this screen) owns the network await and its epoch guard.
 */
@Composable
fun UnauthenticatedStack(
    notice: String?,
    /** "<name> invited you", after an invite link was opened before sign-in. */
    invitation: String? = null,
    auth: AuthServices,
    establish: Establish,
    generation: Int,
    onSignedIn: () -> Unit,
    /** Claims a sign-in link pasted by the donor; false when the text is not a DAD sign-in link. */
    onPastedLink: (String) -> Boolean = { false },
    partnerSignIn: PartnerSignInService? = null,
    applicationStatus: ApplicationStatusService? = null,
    applicationSubmit: PartnerApplicationService? = null,
    /** A tapped partner sign-in link's token, accepted on the partner sign-in screen. */
    partnerToken: String? = null,
    onPartnerTokenConsumed: () -> Unit = {},
    onPartnerSignedIn: () -> Unit = {},
    analytics: AnalyticsUploader? = null,
    /** The configured public site; blank where none is configured. */
    publicOrigin: String = "",
) {
    var step by rememberSaveable { mutableStateOf(AuthStep.RoleSelect) }
    LaunchedEffect(partnerToken) { if (partnerToken != null) step = AuthStep.Partner }
    BackHandler(enabled = step != AuthStep.RoleSelect) {
        step = if (step == AuthStep.Phone || step == AuthStep.EmailLink || step == AuthStep.Password || step == AuthStep.Signup) AuthStep.Methods
        else AuthStep.RoleSelect
    }
    when (step) {
        AuthStep.RoleSelect -> RoleSelect(notice, invitation) { role ->
            analytics?.emit(AnalyticsEvent.Plain(PlainEvent.ROLE_SELECTED))
            step = if (role == AppRole.DONOR) AuthStep.Methods else AuthStep.Partner
        }
        AuthStep.Methods -> SignInMethods(
            auth.external, establish, generation, onSignedIn,
            onPhone = { step = AuthStep.Phone }, onEmailLink = { step = AuthStep.EmailLink }, onPassword = { step = AuthStep.Password },
            onSignup = { step = AuthStep.Signup },
            onBack = { step = AuthStep.RoleSelect })
        AuthStep.Phone -> PhoneSignIn(
            // Keyed by session generation so a model that ended in SignedIn is never reused for the next sign-in.
            viewModel(key = "phone-sign-in-$generation", factory = PhoneSignInModel.factory(auth.phone, establish)), onSignedIn)
        AuthStep.EmailLink -> EmailLinkFlow(
            viewModel(key = "email-link-$generation", factory = SignInLinkModel.factory(auth.link)),
            onPastedLink, notice, onBack = { step = AuthStep.Methods })
        AuthStep.Password -> PasswordSignInFlow(
            viewModel(key = "password-sign-in-$generation", factory = PasswordSignInModel.factory(auth.password, establish)),
            onSignedIn, onBack = { step = AuthStep.Methods })
        AuthStep.Signup -> SignupFlow(
            viewModel(key = "signup-$generation", factory = SignupModel.factory(auth.signup)),
            onPastedLink, notice, onBack = { step = AuthStep.Methods })
        AuthStep.Partner -> if (partnerSignIn != null) PartnerSignInFlow(
            viewModel(key = "partner-sign-in-$generation", factory = PartnerSignInModel.factory(partnerSignIn)),
            incomingToken = partnerToken, onTokenConsumed = onPartnerTokenConsumed,
            onBack = { step = AuthStep.RoleSelect }, onSignedIn = onPartnerSignedIn,
            applyUrl = publicOrigin.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/apply" },
            applicationStatus = applicationStatus,
            applicationSubmit = applicationSubmit,
        ) else RoleSelect(PhoneSignInModel.NOT_CONFIGURED) { role -> if (role == AppRole.DONOR) step = AuthStep.Methods }
    }
}

@Composable
internal fun AuthColumn(tag: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** The DAD brand mark as a small rounded tile (brand fill, ink "D"): a shape + letter, not colour alone,
 *  with a description so TalkBack names the mark. No icon dependency is added. */
@Composable
private fun BrandTile() {
    Box(
        modifier = Modifier
            .background(DadTheme.color("brand"), RoundedCornerShape(DadTheme.radius("field")))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "DAD" },
        contentAlignment = Alignment.Center,
    ) { Text("D", style = MaterialTheme.typography.headlineMedium, color = DadTheme.color("ink")) }
}

/**
 * Days remaining until the Founding-Donor deadline (end of day 2026-12-31 America/New_York), or null
 * once that instant has passed so the line is dropped. One helper so the deadline is computed — and the
 * line rendered — at most once per screen.
 */
internal fun foundingDaysRemaining(now: java.time.Instant = java.time.Instant.now()): Long? {
    val zone = java.time.ZoneId.of("America/New_York")
    val deadline = java.time.LocalDate.of(2026, 12, 31).atTime(23, 59, 59).atZone(zone).toInstant()
    if (!now.isBefore(deadline)) return null
    val days = java.time.Duration.between(now, deadline).toDays()
    return days + 1 // count the current partial day as a full day remaining
}

/** Unauthenticated splash: pick a journey before signing in. Shows [notice] (a failed link, a revoked
 *  session, or an unconfigured build) so the reason for being here is never silent. */
@Composable
fun RoleSelect(notice: String?, invitation: String? = null, onSelect: (AppRole) -> Unit) {
    // Welcome sits on the ink surface. Foreground tokens are AA on ink (asserted in ContrastTest):
    // paper for the title, dkmut for the eyebrow/sub/founding line, brand for the primary fill.
    Box(Modifier.fillMaxSize().background(DadTheme.color("ink"))) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("role-select"),
            verticalArrangement = Arrangement.Center,
        ) {
            BrandTile()
            Spacer(Modifier.height(20.dp))
            Text("Aid, delivered.", style = MaterialTheme.typography.labelLarge, color = DadTheme.color("dkmut"))
            Spacer(Modifier.height(8.dp))
            Text("A dollar a day. Every dollar on the record.", style = MaterialTheme.typography.headlineMedium,
                color = DadTheme.color("paper"), modifier = Modifier.dadHeading())
            Spacer(Modifier.height(12.dp))
            Text("Give monthly, invite the people you know, and watch your circle grow.",
                style = MaterialTheme.typography.bodyLarge, color = DadTheme.color("dkmut"))
            // Founding-Donor line, computed days remaining; hidden after end-of-2026 New York time.
            foundingDaysRemaining()?.let { days ->
                Spacer(Modifier.height(12.dp))
                Text("Founding Donor status closes in $days ${if (days == 1L) "day" else "days"}.",
                    style = MaterialTheme.typography.bodyMedium, color = DadTheme.color("dkmut"),
                    modifier = Modifier.testTag("founding-deadline"))
            }
            // KEEP the notice + invitation above the actions.
            if (notice != null) {
                Spacer(Modifier.height(16.dp))
                Text(notice, style = MaterialTheme.typography.bodyMedium, color = DadTheme.color("paper"),
                    modifier = Modifier.testTag("auth-notice").semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (invitation != null) {
                Spacer(Modifier.height(16.dp))
                Text(invitation, style = MaterialTheme.typography.bodyLarge, color = DadTheme.color("paper"),
                    modifier = Modifier.testTag("auth-invitation"))
            }
            Spacer(Modifier.height(28.dp))
            // The screen's single filled primary. The DONOR branch leads to the sign-in methods (and
            // from there to the signup flow retitled "Step 1 of 2 · About you").
            DadPrimaryButton("Give $1 a day", Modifier.testTag("role-donor")) { onSelect(AppRole.DONOR) }
            Spacer(Modifier.height(12.dp))
            DadSecondaryButton("I already give — sign in", Modifier.fillMaxWidth()) { onSelect(AppRole.DONOR) }
            Spacer(Modifier.height(8.dp))
            DadTextButton("Partner organization? Sign in here", Modifier.testTag("role-partner")) { onSelect(AppRole.PARTNER) }
        }
    }
}

/** The analytics question, worded as on the website; the answer can be changed in Settings. Shown once
 *  after the first sign-in (see the DonorShell bottom sheet), not on Welcome. The body is shared so the
 *  copy, options and tags are reused verbatim wherever the question appears. */
@Composable
internal fun AnalyticsChoiceBody(analytics: AnalyticsUploader) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("analytics-choice")) {
        Text(ANALYTICS_EXPLAINER, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { analytics.choose(true) }, modifier = Modifier.fillMaxWidth().testTag("analytics-allow")) { Text("Allow usage analytics") }
        OutlinedButton(onClick = { analytics.choose(false) }, modifier = Modifier.fillMaxWidth().testTag("analytics-decline")) { Text("Decline usage analytics") }
    }
}

internal const val ANALYTICS_EXPLAINER = "Allow first-party usage analytics? DAD uses a temporary journey ID to measure signup, sharing and " +
    "giving. No names, email addresses or payment details are sent. Observations are kept for 13 months. You can change this " +
    "choice in Settings."

@Composable
private fun SignInMethods(
    external: ExternalSignInDeps, establish: Establish, generation: Int, onSignedIn: () -> Unit,
    onPhone: () -> Unit, onEmailLink: () -> Unit, onPassword: () -> Unit, onSignup: () -> Unit, onBack: () -> Unit,
) {
    // "More options" holds the lower-emphasis methods; a configuration change keeps it open.
    var moreOptions by rememberSaveable { mutableStateOf(false) }
    AuthColumn("sign-in-methods") {
        Text("Welcome back", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(20.dp))
        // The screen's single filled primary.
        DadPrimaryButton("Continue with phone", Modifier.testTag("method-phone"), onPhone)
        Spacer(Modifier.height(12.dp))
        DadSecondaryButton("Email me a sign-in link", Modifier.fillMaxWidth().testTag("method-email-link"), onClick = onEmailLink)
        Spacer(Modifier.height(12.dp))
        DadTextButton(if (moreOptions) "Fewer options" else "More options", Modifier.testTag("more-options")) { moreOptions = !moreOptions }
        if (moreOptions) {
            Spacer(Modifier.height(8.dp))
            DadSecondaryButton("Sign in with email and password", Modifier.fillMaxWidth().testTag("method-password"), onClick = onPassword)
            Spacer(Modifier.height(12.dp))
            // Google/passkey render only when available; the "not available yet" hint
            // (external-unavailable) lives here under More options, not on the bare screen.
            ExternalMethods(external, establish, generation, onSignedIn)
        }
        Spacer(Modifier.height(24.dp))
        // "New to DAD?" is a small bottom card with a secondary "Get started".
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = DadTheme.color("card"),
            border = androidx.compose.foundation.BorderStroke(1.dp, DadTheme.color("line")),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New to DAD?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
                DadSecondaryButton("Get started", Modifier.fillMaxWidth().testTag("method-signup"), onClick = onSignup)
            }
        }
        Spacer(Modifier.height(12.dp))
        DadTextButton("Back", onClick = onBack)
    }
}


/** Phone + SMS one-time-code journey, bound to [PhoneSignInModel]. */
@Composable
private fun PhoneSignIn(model: PhoneSignInModel, onSignedIn: () -> Unit) {
    LaunchedEffect(model.phase) { if (model.phase == PhoneSignInPhase.SignedIn) onSignedIn() }
    val busy = model.phase is PhoneSignInPhase.RequestingCode || model.phase is PhoneSignInPhase.Verifying
    // A complete 6-digit code submits by itself (SMS-OTP autofill delivers all six at once); the
    // explicit Verify button stays for accessibility and manual entry.
    LaunchedEffect(model.code) { if (model.canVerify && !busy && model.isAwaitingCode) model.verify() }
    AuthColumn("phone-sign-in") {
        Text("Sign in with your phone", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(16.dp))
        if (model.isAwaitingCode) {
            Text("Enter the 6-digit code we texted you.")
            OutlinedTextField(
                value = model.code, onValueChange = { model.code = it.filter(Char::isDigit).take(6) },
                label = { Text("Verification code") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().testTag("code-input").semantics { contentType = ContentType.SmsOtpCode },
            )
            Spacer(Modifier.height(12.dp))
            DadPrimaryButton("Verify", Modifier.testTag("verify-code")) { if (model.canVerify && !busy) model.verify() }
        } else {
            OutlinedTextField(
                value = model.phone, onValueChange = { model.phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(18) },
                label = { Text("Phone number") }, singleLine = true, enabled = !busy,
                supportingText = { Text("Include your country code.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().testTag("phone-input").semantics { contentType = ContentType.PhoneNumber },
            )
            Spacer(Modifier.height(12.dp))
            DadPrimaryButton("Send code", Modifier.testTag("send-code")) { if (model.canRequestCode && !busy) model.requestCode() }
        }
        if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(modifier = Modifier.testTag("sign-in-working")) }
        (model.phase as? PhoneSignInPhase.Failed)?.let {
            Spacer(Modifier.height(12.dp))
            Text(it.message, modifier = Modifier.testTag("sign-in-error").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
