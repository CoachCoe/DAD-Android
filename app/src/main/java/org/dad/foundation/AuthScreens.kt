package org.dad.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
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
    auth: AuthServices,
    establish: Establish,
    generation: Int,
    onSignedIn: () -> Unit,
    /** Claims a sign-in link pasted by the donor; false when the text is not a DAD sign-in link. */
    onPastedLink: (String) -> Boolean = { false },
    partnerSignIn: PartnerSignInService? = null,
    /** A tapped partner sign-in link's token, accepted on the partner sign-in screen. */
    partnerToken: String? = null,
    onPartnerTokenConsumed: () -> Unit = {},
    onPartnerSignedIn: () -> Unit = {},
    analytics: AnalyticsUploader? = null,
) {
    var step by rememberSaveable { mutableStateOf(AuthStep.RoleSelect) }
    LaunchedEffect(partnerToken) { if (partnerToken != null) step = AuthStep.Partner }
    BackHandler(enabled = step != AuthStep.RoleSelect) {
        step = if (step == AuthStep.Phone || step == AuthStep.EmailLink || step == AuthStep.Password || step == AuthStep.Signup) AuthStep.Methods
        else AuthStep.RoleSelect
    }
    when (step) {
        AuthStep.RoleSelect -> RoleSelect(notice, analytics) { role ->
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

/** Unauthenticated splash: pick a journey before signing in. Shows [notice] (a failed link, a revoked
 *  session, or an unconfigured build) so the reason for being here is never silent. */
@Composable
fun RoleSelect(notice: String?, analytics: AnalyticsUploader? = null, onSelect: (AppRole) -> Unit) {
    AuthColumn("role-select") {
        Text("DAD", style = MaterialTheme.typography.displayMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(16.dp))
        Text("Good things start together.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        if (notice != null) {
            Spacer(Modifier.height(16.dp))
            Text(notice, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("auth-notice").semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(28.dp))
        DadPrimaryButton("I’m a donor", Modifier.testTag("role-donor")) { onSelect(AppRole.DONOR) }
        Spacer(Modifier.height(12.dp))
        DadPrimaryButton("I’m a partner", Modifier.testTag("role-partner")) { onSelect(AppRole.PARTNER) }
        if (analytics != null && analytics.choice == null) {
            Spacer(Modifier.height(28.dp))
            AnalyticsChoice(analytics)
        }
    }
}

/** The first-launch analytics question, worded as on the website; the answer can be changed in Settings. */
@Composable
private fun AnalyticsChoice(analytics: AnalyticsUploader) {
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
    AuthColumn("sign-in-methods") {
        Text("Sign in", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(20.dp))
        DadPrimaryButton("Sign in with your phone", Modifier.testTag("method-phone"), onPhone)
        Spacer(Modifier.height(12.dp))
        DadPrimaryButton("Email me a sign-in link", Modifier.testTag("method-email-link"), onEmailLink)
        Spacer(Modifier.height(12.dp))
        DadPrimaryButton("Sign in with email and password", Modifier.testTag("method-password"), onPassword)
        Spacer(Modifier.height(12.dp))
        ExternalMethods(external, establish, generation, onSignedIn)
        Spacer(Modifier.height(20.dp))
        Text("New to DAD?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(8.dp))
        DadPrimaryButton("Create an account", Modifier.testTag("method-signup"), onSignup)
        Spacer(Modifier.height(12.dp))
        Text("Already have a DAD sign-in link in your email? Open it on this phone to continue.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}


/** Phone + SMS one-time-code journey, bound to [PhoneSignInModel]. */
@Composable
private fun PhoneSignIn(model: PhoneSignInModel, onSignedIn: () -> Unit) {
    LaunchedEffect(model.phase) { if (model.phase == PhoneSignInPhase.SignedIn) onSignedIn() }
    AuthColumn("phone-sign-in") {
        Text("Sign in with your phone", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(16.dp))
        val busy = model.phase is PhoneSignInPhase.RequestingCode || model.phase is PhoneSignInPhase.Verifying
        if (model.isAwaitingCode) {
            Text("Enter the 6-digit code we texted you.")
            OutlinedTextField(
                value = model.code, onValueChange = { model.code = it.filter(Char::isDigit).take(6) },
                label = { Text("Verification code") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().testTag("code-input"),
            )
            Spacer(Modifier.height(12.dp))
            DadPrimaryButton("Verify", Modifier.testTag("verify-code")) { if (model.canVerify && !busy) model.verify() }
        } else {
            OutlinedTextField(
                value = model.phone, onValueChange = { model.phone = it.filter { c -> c.isDigit() || c == '+' }.take(16) },
                label = { Text("Phone number") }, singleLine = true, enabled = !busy,
                supportingText = { Text("Include your country code, like +14155551234.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().testTag("phone-input"),
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
