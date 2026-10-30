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
        CheckEmail(sent.email, notice, onPastedLink, onChangeEmail = model::edit)
        return
    }
    val busy = model.phase is SignupPhase.Submitting
    AuthColumn("signup") {
        Text("Create your account", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
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
        Button(onClick = onBack, modifier = Modifier.testTag("signup-back")) { Text("Back") }
    }
}

/** "Check your email": the link normally opens the app by itself; a pasted link is the fallback. */
@Composable
private fun CheckEmail(email: String, notice: String?, onPastedLink: (String) -> Boolean, onChangeEmail: () -> Unit) {
    var pasted by rememberSaveable { mutableStateOf("") }
    var unrecognised by rememberSaveable { mutableStateOf(false) }
    AuthColumn("check-email") {
        Text("Check your email", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(12.dp))
        Text("We sent a sign-in link to $email. Open it on this phone to continue.", modifier = Modifier.testTag("check-email-address"))
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
        Button(onClick = onChangeEmail, modifier = Modifier.testTag("check-email-change")) { Text("Use a different email") }
    }
}

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
        Button(onClick = onBack, modifier = Modifier.testTag("password-back")) { Text("Back") }
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
