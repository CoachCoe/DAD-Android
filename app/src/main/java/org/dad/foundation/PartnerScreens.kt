package org.dad.foundation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Partner sign-in and portal (PRD P-*, EM-*, §7). Approved partner users sign in with an emailed link; the portal
// shows verified-or-hidden figures (§7.3), the workforce rollup with <5 suppression (EM-3), and the organization
// tools gated to admins (§7.2): profile, logo, users, company code and report exports.

@Composable
private fun ErrorLine(message: String, tag: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

/** Email → "check your email" → the tapped (or pasted) link signs the partner in. */
@Composable
fun PartnerSignInFlow(
    model: PartnerSignInModel,
    incomingToken: String?,
    onTokenConsumed: () -> Unit,
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
) {
    LaunchedEffect(incomingToken) {
        val token = incomingToken ?: return@LaunchedEffect
        onTokenConsumed()
        model.accept(token)
    }
    LaunchedEffect(model.phase) { if (model.phase is PartnerSignInPhase.SignedIn) onSignedIn() }
    AuthColumn("partner-sign-in") {
        Text("Partner sign-in", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        val phase = model.phase
        val sentTo = model.sentTo
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
            when {
                sentTo != null && phase !is PartnerSignInPhase.SignedIn -> PartnerCheckEmail(model, sentTo)
                phase is PartnerSignInPhase.Accepting || phase is PartnerSignInPhase.SignedIn ->
                    CircularProgressIndicator(modifier = Modifier.testTag("partner-signing-in"))
                else -> {
                    Text("We’ll email you a link to sign in to your organization’s portal.")
                    OutlinedTextField(
                        value = model.email, onValueChange = { model.email = it }, label = { Text("Work email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("partner-sign-in-email"),
                    )
                    if (phase is PartnerSignInPhase.Failed) ErrorLine(phase.message, "partner-sign-in-error")
                    Button(onClick = model::submit, enabled = model.canSubmit, modifier = Modifier.fillMaxWidth().testTag("partner-sign-in-submit")) {
                        Text(if (phase is PartnerSignInPhase.Submitting) "Sending…" else "Email me a sign-in link")
                    }
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("partner-back")) { Text("Back") }
        }
    }
}

@Composable
private fun PartnerCheckEmail(model: PartnerSignInModel, email: String) {
    var pasted by rememberSaveable { mutableStateOf("") }
    val phase = model.phase
    Text("Check your email", style = MaterialTheme.typography.titleLarge, modifier = Modifier.dadHeading())
    Text("We sent a sign-in link to $email. Open it on this phone to sign in.", modifier = Modifier.testTag("partner-check-email"))
    Text("Opened the email somewhere else? Paste the link here.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = pasted, onValueChange = { pasted = it }, label = { Text("Sign-in link") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("partner-paste-link"),
    )
    if (phase is PartnerSignInPhase.Failed) ErrorLine(phase.message, "partner-sign-in-error")
    val accepting = phase is PartnerSignInPhase.Accepting
    Button(
        onClick = { model.acceptPasted(pasted) }, enabled = pasted.isNotBlank() && !accepting,
        modifier = Modifier.fillMaxWidth().testTag("partner-paste-submit"),
    ) { Text(if (accepting) "Signing in…" else "Sign in with this link") }
    OutlinedButton(onClick = model::edit, modifier = Modifier.fillMaxWidth()) { Text("Use a different email") }
}

private enum class PartnerSection(val label: String) { OVERVIEW("Overview"), INITIATIVES("Initiatives"), PEOPLE("People"), ORGANIZATION("Organization") }

/** The signed-in partner portal. [publicOrigin] builds the company sign-up link; blank hides it. */
@Composable
fun PartnerPortal(model: PartnerModel, publicOrigin: String, onSignedOut: () -> Unit = {}, pushTokens: PushTokenProvider? = null) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(pushTokens) { runCatching { pushTokens?.token() }.getOrNull()?.let(model::registerPush) }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    PartnerReportShare(model)
    var section by rememberSaveable { mutableStateOf(PartnerSection.OVERVIEW) }
    val snapshot = model.snapshot
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("partner-portal"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val logo = remember(snapshot?.logo) { snapshot?.logo?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            if (logo != null) {
                Image(logo.asImageBitmap(), contentDescription = "Organization logo", modifier = Modifier.size(44.dp).testTag("partner-logo"))
            }
            Column {
                Text(snapshot?.overview?.organization ?: "Partner portal", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.dadHeading().testTag("partner-organization"))
                snapshot?.let { Text(if (it.overview.role == PartnerRole.ADMIN) "Admin" else "Viewer", style = MaterialTheme.typography.bodySmall) }
            }
        }
        val phase = model.phase
        when {
            snapshot == null && phase is PartnerPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase.message, modifier = Modifier.testTag("partner-error"))
                DadPrimaryButton("Try again") { model.load() }
            }
            snapshot == null -> CircularProgressIndicator(modifier = Modifier.testTag("partner-loading"))
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PartnerSection.entries.forEach {
                        FilterChip(selected = section == it, onClick = { section = it }, label = { Text(it.label) },
                            modifier = Modifier.testTag("partner-tab-${it.name.lowercase()}"))
                    }
                }
                if (snapshot.sectionsUnavailable) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("partner-partial")) {
                        Text("Some details couldn’t be loaded right now.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = model::load) { Text("Try again") }
                    }
                }
                model.notice?.let {
                    Text(it, modifier = Modifier.testTag("partner-notice").semantics { liveRegion = LiveRegionMode.Polite })
                }
                model.actionError?.let { ErrorLine(it, "partner-action-error") }
                when (section) {
                    PartnerSection.OVERVIEW -> PartnerOverviewSection(snapshot)
                    PartnerSection.INITIATIVES -> PartnerInitiativesSection(snapshot.initiatives)
                    PartnerSection.PEOPLE -> PartnerPeopleSection(snapshot)
                    PartnerSection.ORGANIZATION -> PartnerOrganizationSection(model, snapshot, publicOrigin)
                }
            }
        }
    }
}

/** Headline figures render ONLY when verified (§7.3); an unverified figure is left out, never estimated. */
@Composable
private fun PartnerOverviewSection(snapshot: PartnerSnapshot) {
    val overview = snapshot.overview
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        overview.verifiedLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("partner-verified-line")) }
        overview.committed.label?.let { Text("Committed: $it", modifier = Modifier.testTag("partner-committed")) }
        overview.deployed.label?.let { Text("Deployed: $it (${overview.deployedPercent}%)", modifier = Modifier.testTag("partner-deployed")) }
        overview.peopleReached.label?.let { Text("People reached: $it", modifier = Modifier.testTag("partner-reached")) }
        overview.enrolledLabel?.let { Text("Enrolled: $it", modifier = Modifier.testTag("partner-enrolled")) }
    }
    if (snapshot.spend.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("partner-spend")) {
            Text("Where it went", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            snapshot.spend.forEach { Text("${it.category}: ${it.amountLabel}") }
        }
    }
}

@Composable
private fun PartnerInitiativesSection(initiatives: List<PartnerInitiative>) {
    if (initiatives.isEmpty()) Text("No initiatives yet.", modifier = Modifier.testTag("partner-initiatives-empty"))
    initiatives.forEach { initiative ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("partner-initiative-${initiative.id}")) {
            Text(initiative.title, style = MaterialTheme.typography.titleMedium)
            Text("${initiative.status.label} · ${initiative.region}", style = MaterialTheme.typography.bodySmall)
            Text(initiative.summary)
            Text("Committed ${initiative.committedLabel} · Deployed ${initiative.deployedLabel}", style = MaterialTheme.typography.bodySmall)
            initiative.headlineMetrics.forEach { Text("${it.label}: ${it.valueLabel}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PartnerPeopleSection(snapshot: PartnerSnapshot) {
    snapshot.people?.let { people ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Our people", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            val notice = people.suppressionNotice
            if (notice != null) {
                Text(notice, modifier = Modifier.testTag("partner-suppressed"))
            } else {
                Text("Enrolled: ${people.enrolled} of ${people.workforceSize}")
                people.givenLabel?.let { Text("Given: $it", modifier = Modifier.testTag("partner-given")) }
                people.matchedLabel?.let { Text("Matched: $it") }
                Text("Average streak: ${people.avgStreakDays} days")
                Text("Volunteered: ${people.volunteerMinutes / 60} hours")
            }
        }
    }
    if (snapshot.teams.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Teams", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            Text("Teams with fewer than 5 people are left out.", style = MaterialTheme.typography.bodySmall)
            snapshot.teams.forEachIndexed { index, team ->
                Text("${index + 1}. ${team.name} · ${team.activeGivers} givers · ${formatMinorUsd(team.annualizedMinor)}/yr",
                    modifier = Modifier.testTag("partner-team-${team.id}"))
            }
        }
    }
}

@Composable
private fun PartnerOrganizationSection(model: PartnerModel, snapshot: PartnerSnapshot, publicOrigin: String) {
    PartnerProfileEditor(model, snapshot)
    PartnerCompanyCode(snapshot.overview.companyCode, publicOrigin)
    PartnerUsersSection(model, snapshot)
    if (model.role.canExport) PartnerReportsSection(model, snapshot)
    OutlinedButton(onClick = model::signOut, modifier = Modifier.fillMaxWidth().testTag("partner-sign-out")) { Text("Sign out") }
}

@Composable
private fun PartnerProfileEditor(model: PartnerModel, snapshot: PartnerSnapshot) {
    val overview = snapshot.overview
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Organization", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (!model.role.canEditProfile) {
            Text(overview.organization, style = MaterialTheme.typography.titleSmall)
            if (overview.description.isNotEmpty()) Text(overview.description)
            Text("Employees: ${overview.workforceSize}", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        var organization by rememberSaveable { mutableStateOf(overview.organization) }
        var about by rememberSaveable { mutableStateOf(overview.description) }
        var workforce by rememberSaveable { mutableStateOf(overview.workforceSize.toString()) }
        OutlinedTextField(value = organization, onValueChange = { organization = it }, label = { Text("Organization name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("partner-profile-name"))
        OutlinedTextField(value = about, onValueChange = { about = it }, label = { Text("Description") }, minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("partner-profile-description"))
        Button(onClick = { model.saveProfile(organization, about) }, enabled = !model.isBusy,
            modifier = Modifier.fillMaxWidth().testTag("partner-profile-save")) { Text("Save profile") }
        OutlinedTextField(value = workforce, onValueChange = { workforce = it }, label = { Text("Number of employees") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("partner-workforce"))
        Text("Enrollment percentages are measured against this headcount.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { model.saveWorkforce(workforce) }, enabled = !model.isBusy,
            modifier = Modifier.fillMaxWidth().testTag("partner-workforce-save")) { Text("Save workforce size") }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch {
                val png = withContext(Dispatchers.IO) { LogoImage.png(context, uri) }
                if (png == null) model.rejectLogo() else model.saveLogo(png)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !model.isBusy, modifier = Modifier.testTag("partner-logo-pick")) { Text(if (snapshot.logo == null) "Add logo" else "Change logo") }
            if (snapshot.logo != null) {
                OutlinedButton(onClick = { model.saveLogo(null) }, enabled = !model.isBusy, modifier = Modifier.testTag("partner-logo-remove")) { Text("Remove logo") }
            }
        }
    }
}

/** Scales a picked image to the server's logo limits: a PNG no larger than 512 by 512 pixels and 256 KiB. */
internal object LogoImage {
    fun png(context: Context, uri: Uri): ByteArray? {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        var side = 512
        while (side >= 64) {
            val scale = minOf(1f, side.toFloat() / maxOf(image.width, image.height))
            val scaled = image.scale(maxOf(1, (image.width * scale).toInt()), maxOf(1, (image.height * scale).toInt()))
            val png = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            if (png.size <= 256 * 1024) return png
            side /= 2
        }
        return null
    }
}

/** The company code employees enroll with (EM-1), as a sign-up link and its QR code. */
@Composable
private fun PartnerCompanyCode(code: String, publicOrigin: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Company code", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text(code, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.testTag("partner-company-code"))
        if (publicOrigin.isBlank()) return@Column
        val link = publicOrigin.trimEnd('/') + "/c/" + code
        val qr = remember(link) { runCatching { qrBitmap(link) }.getOrNull() }
        if (qr != null) {
            Image(qr.asImageBitmap(), contentDescription = null, filterQuality = FilterQuality.None,
                modifier = Modifier.size(160.dp).testTag("partner-company-qr").semantics { contentDescription = "QR code for $link" })
        }
        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
                try { context.startActivity(Intent.createChooser(send, "Share sign-up link")) } catch (_: ActivityNotFoundException) { }
            },
            modifier = Modifier.testTag("partner-company-share"),
        ) { Text("Share sign-up link") }
    }
}

/** Users: an admin invites and removes inline (never themselves or the last admin); a viewer reads the list. */
@Composable
private fun PartnerUsersSection(model: PartnerModel, snapshot: PartnerSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Users", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        snapshot.users.forEach { user ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isSelf = user.id == snapshot.selfId
                Text("${user.name}${if (isSelf) " (you)" else ""} · ${if (user.role == PartnerRole.ADMIN) "Admin" else "Viewer"}", modifier = Modifier.weight(1f))
                if (model.canRemove(user)) {
                    if (model.pendingRemoval?.id == user.id) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = model::confirmRemoval, modifier = Modifier.testTag("partner-remove-confirm-${user.id}")
                                .semantics { contentDescription = "Confirm remove ${user.name}" }) { Text("Yes, remove") }
                            OutlinedButton(onClick = model::cancelRemoval, modifier = Modifier.testTag("partner-remove-keep-${user.id}")) { Text("Keep") }
                        }
                    } else {
                        OutlinedButton(onClick = { model.requestRemoval(user.id) }, modifier = Modifier.testTag("partner-remove-${user.id}")
                            .semantics { contentDescription = "Remove ${user.name}" }) { Text("Remove") }
                    }
                }
            }
        }
        if (!model.role.canManageUsers) {
            Text("Read-only access", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("partner-readonly"))
            return@Column
        }
        var inviteName by rememberSaveable { mutableStateOf("") }
        var inviteEmail by rememberSaveable { mutableStateOf("") }
        var inviteAdmin by rememberSaveable { mutableStateOf(false) }
        OutlinedTextField(value = inviteName, onValueChange = { inviteName = it }, label = { Text("Their name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("partner-invite-name"))
        OutlinedTextField(value = inviteEmail, onValueChange = { inviteEmail = it }, label = { Text("Their work email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("partner-invite-email"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !inviteAdmin, onClick = { inviteAdmin = false }, label = { Text("Viewer") }, modifier = Modifier.testTag("partner-invite-viewer"))
            FilterChip(selected = inviteAdmin, onClick = { inviteAdmin = true }, label = { Text("Admin") }, modifier = Modifier.testTag("partner-invite-admin"))
        }
        Button(
            onClick = {
                model.inviteUser(inviteEmail, inviteName, if (inviteAdmin) PartnerRole.ADMIN else PartnerRole.VIEWER)
                inviteEmail = ""
                inviteName = ""
            },
            enabled = isPlausibleEmail(inviteEmail) && inviteName.isNotBlank() && !model.isBusy,
            modifier = Modifier.fillMaxWidth().testTag("partner-invite"),
        ) { Text("Invite user") }
    }
}

/** Admin-only PDF impact reports (P-14): a new export, and earlier ones while they are retained. */
@Composable
private fun PartnerReportsSection(model: PartnerModel, snapshot: PartnerSnapshot) {
    val dates = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Reports", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Button(onClick = model::exportReport, enabled = !model.isBusy, modifier = Modifier.fillMaxWidth().testTag("partner-export")) {
            Text(if (model.isBusy) "Preparing…" else "Export impact report")
        }
        snapshot.exports.forEach { export ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(dates.format(export.createdAt))
                    Text("By ${export.exportedBy} · Committed ${formatMinorUsd(export.committedMinor)}", style = MaterialTheme.typography.bodySmall)
                }
                if (export.downloadable) {
                    OutlinedButton(onClick = { model.download(export) }, enabled = !model.isBusy,
                        modifier = Modifier.testTag("partner-export-${export.id}")) { Text("Download") }
                } else {
                    Text("No longer kept", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Offers a fetched report through the share sheet via a FileProvider URI, then forgets the bytes. */
@Composable
private fun PartnerReportShare(model: PartnerModel) {
    val context = LocalContext.current
    val report = model.report
    LaunchedEffect(report?.id) {
        if (report == null) return@LaunchedEffect
        val file = withContext(Dispatchers.IO) { sharedFile(context, "reports", report.name).also { it.writeBytes(report.pdf) } }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, fileAuthority(context), file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.startActivity(Intent.createChooser(send, "Share report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: ActivityNotFoundException) { }
        model.reportShared()
    }
}
