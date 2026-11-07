package org.dad.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding

private val SENSITIVE_TEXT = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)

@Composable
private fun FieldError(message: String, tag: String) {
    Spacer(Modifier.height(12.dp))
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

/** A password field: masked, never spell-checked or suggested, with the matching autofill hint. */
@Composable
internal fun PasswordField(value: String, onChange: (String) -> Unit, label: String, tag: String, enabled: Boolean, newPassword: Boolean = false, support: String? = null, isError: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(128)) }, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(), isError = isError,
        supportingText = support?.let { { Text(it) } },
        keyboardOptions = SENSITIVE_TEXT,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentType = if (newPassword) ContentType.NewPassword else ContentType.Password },
    )
}

/** New-donor signup: the form, then the "Check your email" screen once the link was sent. */
@Composable
internal fun SignupFlow(model: SignupModel, onPastedLink: (String) -> Boolean, notice: String?, onBack: () -> Unit) {
    val sent = model.phase as? SignupPhase.Sent
    if (sent != null) {
        CheckEmail(sent.email, notice, onPastedLink, onChangeEmail = model::edit, onResend = { model.edit(); model.submit() })
        return
    }
    val busy = model.phase is SignupPhase.Submitting
    AuthColumn("signup") {
        Text("Step 1 of 2 · About you", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(8.dp))
        Text("We’ll email you a single-use link to finish signing in.")
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = model.name, onValueChange = { model.name = it.take(100) }, label = { Text("Full name") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("signup-name").semantics { contentType = ContentType.PersonFullName },
            )
            OutlinedTextField(
                value = model.email, onValueChange = { model.email = it.take(254) }, label = { Text("Email") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("signup-email").semantics { contentType = ContentType.EmailAddress },
            )
            OutlinedTextField(
                value = model.zip, onValueChange = { model.zip = it.filter(Char::isDigit).take(5) }, label = { Text("ZIP code") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("signup-zip").semantics { contentType = ContentType.PostalCode },
            )
            OutlinedTextField(
                value = model.city, onValueChange = { model.city = it.take(100) }, label = { Text("City") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("signup-city").semantics { contentType = ContentType.AddressLocality },
            )
        }
        Spacer(Modifier.height(16.dp))
        DadPrimaryButton("Email me a sign-in link", Modifier.testTag("signup-submit")) { if (model.canSubmit) model.submit() }
        if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(modifier = Modifier.testTag("signup-working")) }
        (model.phase as? SignupPhase.Failed)?.let { FieldError(it.message, "signup-error") }
        Spacer(Modifier.height(12.dp))
        DadTextButton("Back", Modifier.testTag("signup-back"), onClick = onBack)
    }
}

/** An existing donor asks for a single-use sign-in link, then lands on "Check your email". */
@Composable
internal fun EmailLinkFlow(model: SignInLinkModel, onPastedLink: (String) -> Boolean, notice: String?, onBack: () -> Unit) {
    val sent = model.phase as? SignInLinkPhase.Sent
    if (sent != null) {
        // The server never says whether the address has an account, so neither does the app.
        CheckEmail(sent.email, notice, onPastedLink, onChangeEmail = model::edit, onResend = { model.edit(); model.submit() })
        return
    }
    val busy = model.phase is SignInLinkPhase.Submitting
    AuthColumn("email-link-sign-in") {
        Text("Sign in with an email link", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = model.email, onValueChange = { model.email = it.take(254) }, label = { Text("Email") }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("email-link-email").semantics { contentType = ContentType.EmailAddress },
        )
        Spacer(Modifier.height(16.dp))
        DadPrimaryButton("Email me a link", Modifier.testTag("email-link-submit")) { if (model.canSubmit) model.submit() }
        if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(modifier = Modifier.testTag("email-link-working")) }
        (model.phase as? SignInLinkPhase.Failed)?.let { FieldError(it.message, "email-link-error") }
        Spacer(Modifier.height(12.dp))
        DadTextButton("Back", Modifier.testTag("email-link-back"), onClick = onBack)
    }
}

/** "Check your inbox": the link normally opens the app by itself; a pasted link is the fallback.
 *  Resend is disabled for [RESEND_COOLDOWN_SECONDS] with a static (non-animated) countdown, then enables. */
@Composable
private fun CheckEmail(email: String, notice: String?, onPastedLink: (String) -> Boolean, onChangeEmail: () -> Unit, onResend: () -> Unit) {
    var pasted by rememberSaveable { mutableStateOf("") }
    var unrecognised by rememberSaveable { mutableStateOf(false) }
    // Static countdown: a saveable deadline (survives rotation) and a 1s tick that recomputes the
    // remaining whole seconds as plain text. Numbers are never animated.
    val deadline = rememberSaveable { System.currentTimeMillis() + RESEND_COOLDOWN_SECONDS * 1000L }
    var remaining by rememberSaveable { mutableIntStateOf(RESEND_COOLDOWN_SECONDS) }
    LaunchedEffect(deadline) {
        while (remaining > 0) {
            remaining = (((deadline - System.currentTimeMillis()) + 999) / 1000).toInt().coerceAtLeast(0)
            if (remaining > 0) kotlinx.coroutines.delay(1000)
        }
    }
    AuthColumn("check-email") {
        Text("Check your inbox", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(12.dp))
        // The address, in bold, so the donor can confirm where the link went.
        Text(email, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.testTag("check-email-address"))
        Spacer(Modifier.height(12.dp))
        Text("The link works once and expires soon. If it doesn’t open the app, paste it here.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pasted, onValueChange = { pasted = it.take(4096); unrecognised = false }, label = { Text("Sign-in link") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("paste-input"),
        )
        Spacer(Modifier.height(12.dp))
        DadPrimaryButton("Continue", Modifier.testTag("paste-submit")) {
            if (pasted.isNotBlank()) { unrecognised = !onPastedLink(pasted); if (!unrecognised) pasted = "" }
        }
        if (unrecognised) FieldError("That doesn’t look like a DAD sign-in link. Copy the whole link from the email.", "paste-error")
        else if (notice != null) FieldError(notice, "check-email-notice")
        Spacer(Modifier.height(12.dp))
        DadSecondaryButton(
            text = if (remaining > 0) "Resend in ${remaining}s" else "Resend the link",
            modifier = Modifier.fillMaxWidth().testTag("check-email-resend"),
            enabled = remaining == 0,
        ) { onResend() }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onChangeEmail, modifier = Modifier.testTag("check-email-change")) { Text("Use a different email") }
    }
}

/** Resend is held for half a minute so a slow email delivery is not re-triggered on top of itself. */
private const val RESEND_COOLDOWN_SECONDS = 30

@Composable
internal fun PasswordSignInFlow(model: PasswordSignInModel, onSignedIn: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(model.phase) { if (model.phase == PasswordSignInPhase.SignedIn) onSignedIn() }
    val busy = model.phase is PasswordSignInPhase.Submitting
    AuthColumn("password-sign-in") {
        Text("Sign in with your password", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = model.email, onValueChange = { model.email = it.take(254) }, label = { Text("Email") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("password-email").semantics { contentType = ContentType.Username + ContentType.EmailAddress },
            )
            PasswordField(model.password, { model.password = it }, "Password", "password-input", enabled = !busy)
        }
        Spacer(Modifier.height(16.dp))
        DadPrimaryButton("Sign in", Modifier.testTag("password-submit")) { if (model.canSubmit) model.submit() }
        if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(modifier = Modifier.testTag("password-working")) }
        (model.phase as? PasswordSignInPhase.Failed)?.let { FieldError(it.message, "password-error") }
        Spacer(Modifier.height(12.dp))
        DadTextButton("Back", Modifier.testTag("password-back"), onClick = onBack)
    }
}

/** One-tap Google and passkey buttons when this build can run them; otherwise the "Not available yet" hint. */
@Composable
internal fun ExternalMethods(external: ExternalSignInDeps, establish: Establish, generation: Int, onSignedIn: () -> Unit) {
    val tokens = external.googleTokens
    if (external.capabilities.google && tokens != null) {
        val model = androidx.lifecycle.viewmodel.compose.viewModel<GoogleSignInModel>(
            key = "google-sign-in-$generation", factory = GoogleSignInModel.factory(tokens, external.oidc, establish))
        LaunchedEffect(model.phase) { if (model.phase == ExternalSignInPhase.SignedIn) onSignedIn() }
        DadPrimaryButton("Continue with Google", Modifier.testTag("method-google")) { model.start() }
        (model.phase as? ExternalSignInPhase.Failed)?.let { FieldError(it.message, "google-error") }
        Spacer(Modifier.height(12.dp))
    }
    val authenticator = external.authenticator
    if (external.capabilities.passkey && authenticator != null) {
        val model = androidx.lifecycle.viewmodel.compose.viewModel<PasskeySignInModel>(
            key = "passkey-sign-in-$generation", factory = PasskeySignInModel.factory(authenticator, external.passkey, establish))
        LaunchedEffect(model.phase) { if (model.phase == ExternalSignInPhase.SignedIn) onSignedIn() }
        DadPrimaryButton("Sign in with a passkey", Modifier.testTag("method-passkey")) { model.start() }
        (model.phase as? ExternalSignInPhase.Failed)?.let { FieldError(it.message, "passkey-error") }
        Spacer(Modifier.height(12.dp))
    }
    if (external.capabilities.hasUnavailableMethod) {
        Text("Google and passkey sign-in: not available yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("external-unavailable"))
    }
}

/** Set or change the password from Settings. Inline, no OS dialogs. */
@Composable
internal fun PasswordSection(model: PasswordChangeModel) {
    val busy = model.phase is PasswordChangePhase.Saving
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("password-section")) {
        Text("Password", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text("Set a password to sign in with your email. Leave “Current password” empty if you haven’t set one.", style = MaterialTheme.typography.bodySmall)
        PasswordField(model.currentPassword, { model.currentPassword = it; model.edit() }, "Current password", "pw-current", enabled = !busy)
        PasswordField(model.newPassword, { model.newPassword = it; model.edit() }, "New password", "pw-new", enabled = !busy, newPassword = true,
            support = model.newPasswordHint ?: "12 to 72 characters.", isError = model.newPasswordHint != null)
        PasswordField(model.confirmPassword, { model.confirmPassword = it; model.edit() }, "Confirm new password", "pw-confirm", enabled = !busy, newPassword = true,
            support = if (model.confirmMismatch) "Passwords don’t match." else null, isError = model.confirmMismatch)
        Button(onClick = { model.save() }, enabled = model.canSave, modifier = Modifier.testTag("pw-save").padding(top = 4.dp)) { Text("Save password") }
        if (busy) CircularProgressIndicator(modifier = Modifier.testTag("pw-working"))
        when (val phase = model.phase) {
            is PasswordChangePhase.Saved -> Text("Password saved.", modifier = Modifier.testTag("pw-saved").semantics { liveRegion = LiveRegionMode.Polite })
            is PasswordChangePhase.Failed -> Text(phase.message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("pw-error").semantics { liveRegion = LiveRegionMode.Polite })
            else -> Unit
        }
    }
}
