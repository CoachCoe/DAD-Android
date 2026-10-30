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

private enum class AuthStep { RoleSelect, Methods, Phone, Partner }

/**
 * The unauthenticated stack: role select -> sign-in methods -> the chosen method's flow. Verification
 * runs through [establish] so the session (not this screen) owns the network await and its epoch guard.
 */
@Composable
fun UnauthenticatedStack(
    notice: String?,
    signIn: SignInService,
    establish: Establish,
    generation: Int,
    onSignedIn: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(AuthStep.RoleSelect) }
    BackHandler(enabled = step != AuthStep.RoleSelect) {
        step = if (step == AuthStep.Phone) AuthStep.Methods else AuthStep.RoleSelect
    }
    when (step) {
        AuthStep.RoleSelect -> RoleSelect(notice) { role -> step = if (role == AppRole.DONOR) AuthStep.Methods else AuthStep.Partner }
        AuthStep.Methods -> SignInMethods(onPhone = { step = AuthStep.Phone }, onBack = { step = AuthStep.RoleSelect })
        AuthStep.Phone -> PhoneSignIn(
            // Keyed by session generation so a model that ended in SignedIn is never reused for the next sign-in.
            viewModel(key = "phone-sign-in-$generation", factory = PhoneSignInModel.factory(signIn, establish)), onSignedIn)
        AuthStep.Partner -> PartnerSignInNotice(onBack = { step = AuthStep.RoleSelect })
    }
}

@Composable
private fun AuthColumn(tag: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** Unauthenticated splash: pick a journey before signing in. Shows [notice] (a failed link, a revoked
 *  session, or an unconfigured build) so the reason for being here is never silent. */
@Composable
fun RoleSelect(notice: String?, onSelect: (AppRole) -> Unit) {
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
    }
}

@Composable
private fun SignInMethods(onPhone: () -> Unit, onBack: () -> Unit) {
    AuthColumn("sign-in-methods") {
        Text("Sign in", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(20.dp))
        DadPrimaryButton("Sign in with your phone", Modifier.testTag("method-phone"), onPhone)
        Spacer(Modifier.height(12.dp))
        Text("Other sign-in methods aren’t available in the app yet. Use your DAD sign-in link from email to continue.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

/** Partner sign-in is an emailed-link flow that lands with the partner wiring phase; say so rather
 *  than offering a donor phone sign-in under a partner label. */
@Composable
private fun PartnerSignInNotice(onBack: () -> Unit) {
    AuthColumn("partner-sign-in-notice") {
        Text("Partner sign-in", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(16.dp))
        Text("Partner sign-in isn’t available in the app yet. Use the DAD website to sign in as a partner.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack, modifier = Modifier.testTag("partner-back")) { Text("Back") }
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
