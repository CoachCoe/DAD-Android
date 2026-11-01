package org.dad.foundation

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Engagement (PRD A-3, I-*, K-*, N-*, R-*, B-*): impact totals, the 24-badge grid, the rankings choice, the
// referral network and the invite link / IRL code, plus the initiative spend drill-in. Bound to [EngagementModel] and
// [InitiativeSpendModel]. State is always carried in text as well as colour, a section the server did not answer says
// so instead of drawing a zero, and nothing here logs.

private val shortDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** The unavailable-section line: one wording everywhere so a missing figure is never mistaken for none. */
@Composable
private fun UnavailableLine(what: String, tag: String) {
    Text("$what can’t be shown right now.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(tag))
}

/**
 * "Your impact": lifetime totals, badges, the rankings card with an inline toggle, the network and the invite.
 * [publicOrigin] is the configured public origin the invite link is built from (blank = no link, never an invented one).
 */
@Composable
fun EngagementHome(
    model: EngagementModel,
    publicOrigin: String,
    sharer: ReferralShareAction,
    onClose: (() -> Unit)? = null,
    onSignedOut: () -> Unit = {},
    /** Adds the referral code to Google Wallet; null where Wallet is not wired (previews). */
    wallet: WalletPassModel? = null,
) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    var showQr by rememberSaveable { mutableStateOf(false) }
    val referral = model.snapshot?.referral
    val irlLink = referral?.irlLink(publicOrigin)
    if (showQr && irlLink != null) {
        BackHandler { showQr = false }
        InviteQrScreen(irlLink, onClose = { showQr = false })
        return
    }
    if (onClose != null) BackHandler(onBack = onClose)
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("engagement-home"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onClose != null) OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("engagement-close")) { Text("Close") }
        Text("Your impact", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            EngagementPhase.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("engagement-loading"))
            is EngagementPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase.message, modifier = Modifier.testTag("engagement-error"))
                DadPrimaryButton("Try again", Modifier.testTag("engagement-retry")) { model.load() }
            }
            EngagementPhase.Ready -> {
                model.refreshNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("engagement-notice")) }
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    model.actionError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("engagement-action-error")) }
                }
                model.snapshot?.let { Content(model, it, publicOrigin, sharer, wallet, onShowQr = { showQr = true; sharer.codeShown() }) }
            }
        }
    }
}

@Composable
private fun Content(
    model: EngagementModel, snapshot: EngagementSnapshot, publicOrigin: String, sharer: ReferralShareAction, wallet: WalletPassModel?, onShowQr: () -> Unit,
) {
    ImpactCard(snapshot.impact)
    BadgesSection(snapshot.badges)
    RankingsCard(model)
    NetworkCard(snapshot.network)
    InviteCard(model, snapshot, publicOrigin, sharer, wallet, onShowQr)
}

// MARK: impact (I-1, I-3)

@Composable
private fun ImpactCard(impact: ImpactTotals?) {
    HomeCard("impact-section") {
        Text("Lifetime given", style = MaterialTheme.typography.labelLarge)
        if (impact != null) {
            Text(impact.givenLabel, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("impact-given"))
            val funded = impact.initiativesFunded
            val events = impact.eventsVolunteered
            Text(
                "$funded ${if (funded == 1) "initiative" else "initiatives"} · $events ${if (events == 1) "event" else "events"}",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("impact-counts"),
            )
        } else {
            Text("—", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("impact-given").semantics { contentDescription = "Not available" })
            UnavailableLine("Your impact", "impact-unavailable")
        }
    }
}

// MARK: badges (B-1..B-4), earned/locked in text, never colour alone

@Composable
private fun BadgesSection(grid: BadgeGrid?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Badges", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (grid == null) {
            UnavailableLine("Your badges", "badges-unavailable")
            return
        }
        Text(grid.summaryLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("badge-summary"))
        if (grid.hasInviteOnlyAccess) {
            Text("You have invite-only event access.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("badge-invite-access"))
        }
        grid.badges.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { badge -> BadgeTile(badge, Modifier.weight(1f)) }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BadgeTile(badge: Badge, modifier: Modifier = Modifier) {
    // Earned badges render in the brand colour; locked ones greyed. State is ALSO in the label and the detail line,
    // so a colour-blind or screen-reader user gets earned/locked from text (B-1, WCAG).
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val color = if (badge.earned) DadTheme.brandAccent(dark) else DadTheme.color(if (dark) "dkmut" else "muted")
    Column(
        modifier = modifier
            .testTag(if (badge.earned) "badge-earned-${badge.code}" else "badge-locked-${badge.code}")
            .semantics(mergeDescendants = true) {
                contentDescription = if (badge.perkActive) "${badge.accessibilityLabel}. Perk: ${badge.perk}" else badge.accessibilityLabel
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(badge.shownTitle, style = MaterialTheme.typography.labelLarge, color = color)
        Text(if (badge.earned) badge.earnedAt?.let { "Earned ${it.format(shortDate)}" } ?: "Earned" else "Locked", style = MaterialTheme.typography.labelSmall, color = color)
        if (!badge.earned) Text(badge.rule, style = MaterialTheme.typography.bodySmall)
        if (badge.perkActive) Text("Perk: ${badge.perk}", style = MaterialTheme.typography.bodySmall)
    }
}

// MARK: rankings (K-1..K-3): opt-out hides the position entirely, never a name list

@Composable
private fun RankingsCard(model: EngagementModel) {
    HomeCard("rankings-section") {
        Text("Rankings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        val ranking = model.displayedRanking
        if (ranking == null) {
            UnavailableLine("Your ranking", "ranking-unavailable")
            return@HomeCard
        }
        if (ranking.isOptedOut) {
            Text("You’re not shown in rankings.", modifier = Modifier.testTag("ranking-optedout"))
        } else {
            val national = ranking.nationalLabel
            val city = ranking.cityLabel
            national?.let { Text(it, modifier = Modifier.testTag("ranking-national")) }
            city?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ranking-city")) }
            if (national == null && city == null) Text("Not ranked yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ranking-unranked"))
        }
        OutlinedButton(
            onClick = { model.setRankingOptIn(ranking.isOptedOut) },
            enabled = model.pendingOptIn == null,
            modifier = Modifier.heightIn(min = 48.dp).testTag("ranking-toggle"),
        ) { Text(if (ranking.isOptedOut) "Show my ranking" else "Hide my ranking") }
        Text("Only you see your position. Rankings never list other donors.", style = MaterialTheme.typography.bodySmall)
    }
}

// MARK: network (N-1..N-3)

@Composable
private fun NetworkCard(network: NetworkView?) {
    HomeCard("network-section") {
        Text("Your network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (network == null) {
            UnavailableLine("Your network", "network-unavailable")
            return@HomeCard
        }
        Text(network.activeLabel, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("network-active"))
        Text(network.annualizedLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("network-annualized"))
        network.nextMilestone?.let { Text(it.progressLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("network-milestone")) }
        network.invitees.forEachIndexed { index, invitee ->
            Text("${invitee.name} — ${invitee.statusLabel}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("network-invitee-$index"))
        }
        network.anonymousStayingLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("network-anonymous")) }
    }
}

// MARK: invite link + IRL code (R-1..R-4)

@Composable
private fun InviteCard(
    model: EngagementModel, snapshot: EngagementSnapshot, publicOrigin: String, sharer: ReferralShareAction, wallet: WalletPassModel?, onShowQr: () -> Unit,
) {
    HomeCard("invite-section") {
        Text("Invite friends", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        val referral = snapshot.referral
        when {
            referral != null -> Invite(referral, publicOrigin, sharer, wallet, onShowQr)
            snapshot.needsPublicName -> NamePrompt(model)
            else -> UnavailableLine("Your invite link", "invite-unavailable")
        }
    }
}

@Composable
private fun Invite(referral: ReferralInfo, publicOrigin: String, sharer: ReferralShareAction, wallet: WalletPassModel?, onShowQr: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Text("Your code: ${referral.code}", modifier = Modifier.testTag("referral-code"))
    referral.totalConverted?.let { total ->
        Text("$total ${if (total == 1L) "person has" else "people have"} joined through you", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("referral-total"))
    } ?: UnavailableLine("Your referral count", "referral-total-unavailable")
    val link = referral.link(publicOrigin)
    if (link != null) {
        DadPrimaryButton("Share invite", Modifier.testTag("share-invite")) { sharer.share(referral.shareMessage(publicOrigin)) }
        OutlinedButton(
            onClick = { sharer.copy(link); copied = true },
            modifier = Modifier.heightIn(min = 48.dp).testTag("copy-invite"),
        ) { Text(if (copied) "Copied" else "Copy link") }
        OutlinedButton(onClick = onShowQr, modifier = Modifier.heightIn(min = 48.dp).testTag("show-qr")) { Text("Show QR") }
        wallet?.let { WalletPassButton(it) }
        Text(link, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("invite-link"))
    } else {
        Text("Invite links aren’t available in this build.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("invite-unconfigured"))
    }
    Conversions(referral)
}

@Composable
private fun Conversions(referral: ReferralInfo) {
    val list = referral.conversions
    if (list == null) {
        UnavailableLine("Your recent first gifts", "conversions-unavailable")
        return
    }
    if (list.isEmpty()) {
        Text("No one has given through your link yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("conversions-empty"))
        return
    }
    Text("Recent first gifts", style = MaterialTheme.typography.titleSmall)
    list.take(5).forEachIndexed { index, conversion ->
        Text("${conversion.convertedAt.format(shortDate)} · ${conversion.kindLabel}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("conversion-$index"))
    }
    if (list.size > 5 || referral.hasMoreConversions) Text("Older first gifts aren’t listed here.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun NamePrompt(model: EngagementModel) {
    var draft by rememberSaveable { mutableStateOf("") }
    Text("Choose the name your invite link goes out under. Friends see it when they open your link.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = draft, onValueChange = { draft = it.take(MAX_PUBLIC_NAME) },
        label = { Text("Public name") }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth().testTag("name-field"),
    )
    model.nameError?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("name-error")) }
    Button(
        onClick = { model.choosePublicName(draft) },
        enabled = !model.isSavingName && draft.isNotBlank(),
        modifier = Modifier.heightIn(min = 48.dp).testTag("save-name"),
    ) { Text("Create my invite link") }
}

/**
 * The full-screen IRL code (R-1, R-2): a QR of the in-person link, drawn locally from ZXing's matrix so no image is
 * fetched and no untrusted file is parsed. Meant to be scanned from a phone screen face to face.
 */
@Composable
private fun InviteQrScreen(link: String, onClose: () -> Unit) {
    val bitmap = remember(link) { runCatching { qrBitmap(link) }.getOrNull() }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("qr-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("qr-close")) { Text("Close") }
        Text("Show this code", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = "QR code for your invite link", filterQuality = FilterQuality.None,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().background(Color.White).padding(16.dp).testTag("qr-code"),
            )
        } else {
            Text("The code couldn’t be drawn. Share your link instead.", modifier = Modifier.testTag("qr-failed"))
        }
        Text("A friend scans it with their phone camera to open your invite.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Dark modules black on a white bitmap (a scanner needs the contrast whatever the app theme). */
internal fun qrBitmap(contents: String): Bitmap {
    val matrix = Referral.qrMatrix(contents, size = 256)
    val size = matrix.size
    val pixels = IntArray(size * size) { i -> if (matrix[i / size][i % size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** "See every dollar this initiative has spent" (A-3): spend by category, totals only (PRD §5.2). */
@Composable
fun InitiativeSpendScreen(model: InitiativeSpendModel, title: String, onClose: () -> Unit, onSignedOut: () -> Unit = {}) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    BackHandler(onBack = onClose)
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("spend-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("spend-close")) { Text("Close") }
        Text("Where the money went", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Text(title, style = MaterialTheme.typography.titleMedium)
        when (val phase = model.phase) {
            InitiativeSpendModel.Phase.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("spend-loading"))
            InitiativeSpendModel.Phase.Unpublished -> Text("This initiative’s spending hasn’t been published yet.", modifier = Modifier.testTag("spend-unpublished"))
            is InitiativeSpendModel.Phase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase.message, modifier = Modifier.testTag("spend-error"))
                DadPrimaryButton("Try again", Modifier.testTag("spend-retry")) { model.load() }
            }
            is InitiativeSpendModel.Phase.Ready -> {
                HomeCard("spend-summary") {
                    Text("Total spent", style = MaterialTheme.typography.labelLarge)
                    Text(phase.spend.totalLabel, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("spend-total"))
                }
                if (phase.spend.categories.isEmpty()) Text("No spending by category yet.", modifier = Modifier.testTag("spend-empty"))
                phase.spend.categories.forEachIndexed { index, category ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("spend-row-$index"), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(category.category, modifier = Modifier.weight(1f))
                        Text(category.amountLabel)
                    }
                }
                Text("Totals by category, not individual payments.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
