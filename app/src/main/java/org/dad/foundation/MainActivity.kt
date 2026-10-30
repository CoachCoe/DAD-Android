package org.dad.foundation

import android.os.Bundle
import android.content.Intent
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.util.Date
import org.dad.foundation.generated.DADTokens

sealed interface AccountState {
    data object Loading : AccountState
    data class Ready(val profile: AccountProfile) : AccountState
    data object Unavailable : AccountState
    data object SignedOut : AccountState
}

private class PreviewStore(signedIn: Boolean = true) : CredentialStore {
    private var value: Credentials? = if (signedIn) Credentials("preview", "preview-refresh", Long.MAX_VALUE) else null
    override fun load() = value
    override fun save(value: Credentials) { this.value = value }
    override fun clear() { value = null }
}
private class PreviewAPI(private var failFirst: Boolean) : AccountAPI {
    override suspend fun profile(access: String): AccountProfile {
        delay(250)
        if (failFirst) { failFirst = false; throw AccountFailure.Unavailable }
        return AccountProfile("alex@example.org", "Alex")
    }
    override suspend fun refresh(token: String): Credentials = throw AccountFailure.SignedOut
    override suspend fun claim(token: String): Credentials = throw AccountFailure.Unavailable
    override suspend fun logout(access: String) = Unit
}
/** DEBUG preview sign-in (`preview_signed_out`): accepts code 123456, never touches a network. */
private class PreviewSignIn : SignInService {
    override suspend fun requestPhoneCode(phone: String): String = phone
    override suspend fun verifyPhone(handle: String, code: String): Credentials =
        if (code == "123456") Credentials("preview", "preview-refresh", Long.MAX_VALUE) else throw AccountFailure.SignedOut
}
/** Preview settings tied to the live (preview) session, so signing out or deleting from the donor
 *  shell really ends the session and routes back to sign-in, like a release build. */
private class PreviewSessionSettings(private val session: AccountSession) : SettingsService {
    private val inner = PreviewSettingsService()
    override suspend fun load() = inner.load()
    override suspend fun savePreferences(preferences: Preferences) = inner.savePreferences(preferences)
    override suspend fun deleteAccount() { session.discardLocal() }
    override suspend fun signOut() { session.logout() }
}

class AccountModel(
    private val factory: (CoroutineScope, LaunchOptions) -> AppServices,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    var state: AccountState by mutableStateOf(AccountState.Loading)
        private set
    private var services: AppServices? = null
    private val bootLock = Mutex()
    private var configurationFailed = false
    /** True once the services exist and the launch session (restored or none) is known. */
    var booted: Boolean by mutableStateOf(false)
        private set
    /**
     * Whether a session exists (stored credentials, or a sign-in/claim just installed one). A
     * transient Unavailable on an existing session is a sub-state of the donor shell (recoverable via
     * "Try again"), NOT a reason to leave for sign-in; only a session-less app goes there.
     */
    var hasSession: Boolean by mutableStateOf(false)
        private set
    /** A line for the unauthenticated screen: a failed sign-in link, an ended session, or an unconfigured build. */
    var notice: String? by mutableStateOf(null)
        private set
    /** Bumped on every sign-in/sign-out so per-session view models are never reused across sessions. */
    var generation: Int by mutableIntStateOf(0)
        private set
    var signIn: SignInService = UnconfiguredSignIn
        private set
    var settings: SettingsService? = null
        private set
    private var loading = false
    private var pendingProof: String? = null
    private var completedProof: String? = null
    private var activeProof: String? = null
    private val gate = NavigationGate()
    private var options = LaunchOptions()

    /** Runs sign-in verification INSIDE the session so it owns the await and the epoch guard. */
    fun establish(): Establish = { produce ->
        val active = services ?: throw AccountFailure.InvalidConfiguration
        active.session.establish(produce)
    }

    /** Build the services off the main thread; a build that cannot (blank release origin) is unconfigured. */
    private suspend fun ensureServices(): AppServices? = bootLock.withLock {
        services?.let { return it }
        if (configurationFailed) return null
        try {
            withContext(io) { factory(CoroutineScope(viewModelScope.coroutineContext + io), options) }
                .also { services = it; signIn = it.signIn; settings = it.settings }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            configurationFailed = true
            notice = PhoneSignInModel.NOT_CONFIGURED
            null
        }
    }

    /** Launch: restore the stored session, or land on sign-in. */
    fun start(launch: LaunchOptions = LaunchOptions()) {
        if (booted || loading) return
        options = launch
        viewModelScope.launch {
            val active = ensureServices()
            hasSession = active != null && active.session.isSignedIn()
            booted = true
            if (hasSession) load() else state = if (active == null) AccountState.Unavailable else AccountState.SignedOut
        }
    }

    /** A sign-in completed (the session already holds the credentials): enter the donor shell. */
    fun onSignedIn() {
        notice = null
        gate.signedIn()
        generation++
        hasSession = true
        load()
    }

    /** Sign-out, account deletion or a revoked session: back to sign-in. */
    fun onSignedOut(message: String? = null) {
        notice = message
        generation++
        hasSession = false
        state = AccountState.SignedOut
    }

    fun load(claim: String? = null) {
        if (loading) { if (claim != null) pendingProof = claim; return }
        loading = true
        state = AccountState.Loading
        viewModelScope.launch {
            var claimed = false
            var active: AppServices? = null
            try {
                active = ensureServices()
                if (active == null) { hasSession = false; state = AccountState.Unavailable; return@launch }
                val profile = withContext(io) {
                    if (claim != null) { active.session.claim(claim); claimed = true }
                    active.session.profile()
                }
                notice = null
                hasSession = true
                state = AccountState.Ready(profile)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AccountFailure.SignedOut) {
                // Revoked or expired for good: the session cleared itself, so return to sign-in.
                hasSession = false
                notice = if (claim != null && !claimed) LINK_FAILED else SESSION_ENDED
                state = AccountState.SignedOut
            }
            catch (_: Exception) {
                state = AccountState.Unavailable
                if (active?.session?.isSignedIn() == false) { hasSession = false; if (claim != null) notice = LINK_FAILED }
            }
            finally {
                loading = false
                if (claim != null) {
                    if (claimed) { completedProof = activeProof; gate.signedIn(); generation++ }
                    activeProof = null
                }
                pendingProof?.let { pendingProof = null; load(claim = it) }
            }
        }
    }
    fun configurationUnavailable() { hasSession = false; notice = PhoneSignInModel.NOT_CONFIGURED; state = AccountState.Unavailable }
    fun receive(value: String, router: LinkRouter): String? {
        return when (val intent = router.route(value)) {
            is LinkIntent.Account -> if (intent.destination == AccountDestination.PROFILE) {
                gate.receive(intent.destination, state is AccountState.Ready)
                if (hasSession) load()
                null
            } else value
            is LinkIntent.Claim -> {
                if (intent.next != null || BuildConfig.DAD_API_ORIGIN.isBlank()) value
                else {
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(intent.token.toByteArray()).joinToString("") { "%02x".format(it) }
                    if (activeProof == null && completedProof != digest) { activeProof = digest; load(claim = intent.token) }
                    null
                }
            }
            is LinkIntent.EmailChange, is LinkIntent.Web -> value
            null -> null
        }
    }

    companion object {
        const val LINK_FAILED = "That sign-in link didn’t work. It may have expired — request a new one."
        const val SESSION_ENDED = "Your session has ended. Please sign in again."
    }
}

private fun tokenColor(name: String) = Color(DADTokens.color.getValue(name).toColorInt())

class MainActivity : ComponentActivity() {
    private var incomingLink: String? = null
    private var linkEvent by mutableIntStateOf(0)
    private val linkRouter by lazy {
        if (BuildConfig.DAD_PUBLIC_ORIGIN.isBlank()) null
        else runCatching { LinkRouter(BuildConfig.DAD_PUBLIC_ORIGIN, assets.open("links.json").bufferedReader().use { it.readText() }) }.getOrNull()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingLink = intent.dataString
        linkEvent++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launch = LaunchOptions(
            unavailableFirst = BuildConfig.DEBUG && intent.getBooleanExtra("preview_unavailable", false),
            signedOut = BuildConfig.DEBUG && intent.getBooleanExtra("preview_signed_out", false),
            stubBackend = BuildConfig.DEBUG && intent.getBooleanExtra("stub_backend", false),
            stubDeleteBlocked = BuildConfig.DEBUG && intent.getBooleanExtra("stub_delete_blocked", false),
        )
        val giving = BuildConfig.DEBUG && intent.getBooleanExtra("preview_giving", false)
        val impact = BuildConfig.DEBUG && intent.getBooleanExtra("preview_impact", false)
        val network = BuildConfig.DEBUG && intent.getBooleanExtra("preview_network", false)
        val events = BuildConfig.DEBUG && intent.getBooleanExtra("preview_events", false)
        val settings = BuildConfig.DEBUG && intent.getBooleanExtra("preview_settings", false)
        val partner = BuildConfig.DEBUG && intent.getBooleanExtra("preview_partner", false)
        val partnerViewer = BuildConfig.DEBUG && intent.getBooleanExtra("preview_partner_viewer", false)
        val partnerPending = BuildConfig.DEBUG && intent.getBooleanExtra("preview_partner_pending", false)
        val appContext = applicationContext
        incomingLink = intent.dataString
        val factory = viewModelFactory {
            initializer {
                AccountModel(factory = factory@{ scope, options ->
                    if (BuildConfig.DEBUG) DebugBackend.stub(appContext, scope, options)?.let { return@factory it }
                    if (BuildConfig.DAD_API_ORIGIN.isBlank()) {
                        // The synthetic (PreviewAPI/PreviewStore) path must NEVER ship in a release
                        // build: a blank origin there is a misconfiguration, not a demo mode, and
                        // falling back would serve synthetic data as if real. Fail fast in release
                        // (the model then shows a "not configured" notice); only debug builds get
                        // the synthetic fallback.
                        check(BuildConfig.DEBUG) { "DAD_API_ORIGIN must be set for a release build" }
                        val session = AccountSession(PreviewAPI(options.unavailableFirst), PreviewStore(!options.signedOut), scope)
                        AppServices(session, PreviewSignIn(), PreviewSessionSettings(session))
                    } else {
                        val session = AccountSession(WireAPI(BuildConfig.DAD_API_ORIGIN), KeystoreCredentialStore(appContext), scope)
                        val attribution = PrefsAttributionStore(appContext)
                        AppServices(
                            session,
                            WireAuth(BuildConfig.DAD_API_ORIGIN, android.os.Build.MODEL),
                            WireSettings(ProductClient(BuildConfig.DAD_API_ORIGIN, session), onLocalWipe = { attribution.clear() }),
                        )
                    }
                })
            }
        }
        setContent {
            val model: AccountModel = viewModel(factory = factory)
            LaunchedEffect(Unit) { model.start(launch) }
            LaunchedEffect(linkEvent) {
                val router = linkRouter ?: run {
                    if (BuildConfig.DAD_PUBLIC_ORIGIN.isNotBlank()) model.configurationUnavailable()
                    return@LaunchedEffect
                }
                incomingLink?.let { link ->
                    model.receive(link, router)?.let {
                        try { CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, it.toUri()) }
                        catch (_: android.content.ActivityNotFoundException) { model.configurationUnavailable() }
                    }
                }
            }
            val dark = isSystemInDarkTheme()
            val primary = DadTheme.brandAccent(dark)
            val colors = if (dark) darkColorScheme(primary = primary, onPrimary = tokenColor("ink-deep"), background = tokenColor("ink-deep"))
                else lightColorScheme(primary = primary, onPrimary = tokenColor("paper"), background = tokenColor("paper"))
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val router = remember { RoleRouter() }
                    var root by remember { mutableStateOf<RoleRouter.Root>(router.root) }
                    // A session-backed shell is the donor journey — the donor landing owns every
                    // account sub-state (loading / ready / unavailable / signed-out) and its own
                    // recovery, so stay on it. Only a genuinely session-less app goes to role-select.
                    LaunchedEffect(model.booted, model.hasSession) {
                        if (model.booted) { if (model.hasSession) router.signedIn(AppRole.DONOR) else router.signedOut() }
                        root = router.root
                    }
                    when (val current = root) {
                        is RoleRouter.Root.Restoring, is RoleRouter.Root.Authenticated ->
                            if (partnerPending) {
                                PartnerPortal(viewModel(factory = PartnerModel.previewFactory(pending = true)))
                            } else if (partnerViewer) {
                                PartnerPortal(viewModel(factory = PartnerModel.previewFactory(role = PartnerRole.VIEWER)))
                            } else if (partner) {
                                PartnerPortal(viewModel(factory = PartnerModel.previewFactory()))
                            } else if (giving) {
                                // Giving preview exercises the signed-in GiftScreen directly. The
                                // donor shell reaches Restoring/Authenticated immediately (hasSession
                                // starts true), so there is no load-delay gap to short-circuit.
                                GiftScreen(viewModel(factory = GivingModel.previewFactory()))
                            } else if (impact) {
                                ImpactHome(viewModel(factory = EngagementModel.previewFactory()))
                            } else if (network) {
                                NetworkBadgesHome(viewModel(factory = NetworkBadgesModel.previewFactory()))
                            } else if (events) {
                                EventsHome(viewModel(factory = EventsModel.previewFactory()))
                            } else if (settings) {
                                SettingsHome(viewModel(factory = SettingsModel.previewFactory()))
                            } else {
                                DonorShell(model)
                            }
                        is RoleRouter.Root.Unauthenticated -> UnauthenticatedStack(
                            notice = model.notice,
                            signIn = model.signIn,
                            establish = model.establish(),
                            generation = model.generation,
                            onSignedIn = { model.onSignedIn() },
                        )
                    }
                }
            }
        }
    }
}

/** The donor shell: the landing, with Settings (sign-out and account deletion) one tap away. */
@Composable
private fun DonorShell(model: AccountModel) {
    var inSettings by rememberSaveable { mutableStateOf(false) }
    val service = model.settings
    if (inSettings && service != null) {
        BackHandler { inSettings = false }
        SettingsHome(
            viewModel(key = "settings-${model.generation}", factory = SettingsModel.factory(service)),
            onBack = { inSettings = false },
            onSignedOut = { model.onSignedOut() },
        )
    } else {
        DonorLanding(model, onSettings = if (service != null) ({ inSettings = true }) else null)
    }
}

/** The signed-in donor landing. Carries the exact copy the app has shown since the preview
 * shell, so the role-aware root is a structural change, not a visible one. */
@Composable
private fun DonorLanding(model: AccountModel, onSettings: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("DAD", style = MaterialTheme.typography.displayMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(16.dp))
        Text("Good things start together.", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(28.dp))
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (val state = model.state) {
                AccountState.Loading -> { CircularProgressIndicator(); Text("Loading your account…") }
                is AccountState.Ready -> {
                    Text("Welcome, ${state.profile.publicName ?: "friend"}", style = MaterialTheme.typography.titleLarge)
                    Text("Your account has a home here.")
                }
                AccountState.Unavailable -> {
                    Text("Your account is unavailable right now.", style = MaterialTheme.typography.titleLarge)
                    Text("Please try again in a moment.")
                    Button(onClick = { model.load() }) { Text("Try again") }
                }
                AccountState.SignedOut -> Text("You’re signed out.")
            }
        }
        // Settings hosts sign-out and the store-mandated in-app account deletion, so it is reachable
        // whenever the donor is signed in, including while the profile read is retrying.
        if (onSettings != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onSettings, modifier = Modifier.testTag("open-settings")) { Text("Settings") }
        }
        Spacer(Modifier.height(28.dp))
        if (BuildConfig.DAD_API_ORIGIN.isBlank()) Text("Preview · Sample account", style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Set-your-gift screen (G-1/G-2/G-7/G-8/G-9). Whole-dollar stepper $1…$30/day with the
 * monthly ×30 echo and the streak bar; the cancel confirmation is rendered INLINE (never an
 * OS dialog/bottom sheet) so it is deterministic in UI automation — the lesson the iOS side
 * learned across three CI rounds.
 */
@Composable
private fun GiftScreen(model: GivingModel) {
    LaunchedEffect(Unit) { model.load() }
    var confirmingCancel by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("gift-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set your gift", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Text(
            model.selection.dailyLabel,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.testTag("gift-daily"),
        )
        Text(
            "${model.selection.monthlyLabel} · charged monthly",
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { model.decrement() }, modifier = Modifier.testTag("gift-decrease")) { Text("Decrease gift") }
            Button(onClick = { model.increment() }, modifier = Modifier.testTag("gift-increase")) { Text("Increase gift") }
        }
        DadStreakBar(filled = model.selection.dailyDollars.coerceAtMost(30))
        DadPrimaryButton("Save gift") { model.applySelection() }
        if (model.phase == GivingPhase.Working) CircularProgressIndicator(modifier = Modifier.testTag("gift-working"))

        if (model.recurring is RecurringState.Active) {
            if (confirmingCancel) {
                val periodEnd = (model.recurring as RecurringState.Active).periodEnd
                Column(
                    modifier = Modifier.testTag("cancel-confirm"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Cancel your gift?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
                    Text(CancelCopy.confirmation(periodEnd))
                    Button(onClick = { model.cancel(); confirmingCancel = false }) { Text("End my gift") }
                    Button(onClick = { confirmingCancel = false }) { Text("Keep giving") }
                }
            } else {
                Button(onClick = { confirmingCancel = true }, modifier = Modifier.testTag("gift-cancel")) { Text("Cancel gift") }
            }
        } else if (model.recurring is RecurringState.Ending) {
            // Post-cancel acknowledgement (G-8/G-9): the gift is cancelled but keeps running until
            // the paid period ends, and the streak continues until then. Without this the whole
            // cancel block vanished after a successful cancel with no feedback.
            val ending = model.recurring as RecurringState.Ending
            Text(
                CancelCopy.confirmation(ending.periodEnd),
                modifier = Modifier.testTag("gift-ending"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (val phase = model.phase) {
            is GivingPhase.Failed -> Text(phase.message, modifier = Modifier.testTag("gift-error"))
            else -> {}
        }
    }
}

/**
 * Phase 4 impact home (A-*, S-*, I-*, K-*): today's initiative rotation card, lifetime impact
 * totals, the 30-day streak bar + milestone progress, the rankings card with an INLINE opt-out
 * toggle (no dialog, per the Phase 3 UITest lesson), and the spend-by-category totals.
 */
@Composable
private fun ImpactHome(model: EngagementModel) {
    LaunchedEffect(Unit) { model.load() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("impact-home"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Your impact", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            is EngagementPhase.Loading ->
                CircularProgressIndicator(modifier = Modifier.testTag("impact-loading"))
            is EngagementPhase.Failed ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(phase.message, modifier = Modifier.testTag("impact-error"))
                    DadPrimaryButton("Try again") { model.load() }
                }
            is EngagementPhase.Ready -> model.snapshot?.let { ImpactContent(model, it) }
        }
        model.actionError?.let { Text(it, modifier = Modifier.testTag("impact-action-error")) }
    }
}

@Composable
private fun ImpactContent(model: EngagementModel, snapshot: EngagementSnapshot) {
    // Today's initiative rotation card (A-1/A-2).
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(snapshot.today.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("today-title"))
        Text(snapshot.today.summary, style = MaterialTheme.typography.bodyMedium)
        snapshot.today.headlineMetrics.forEach { metric ->
            Text("${metric.label}: ${metric.value}", style = MaterialTheme.typography.bodySmall)
        }
    }

    // Lifetime impact total — "--" when unknown, never $0.
    Text("Lifetime given", style = MaterialTheme.typography.labelMedium)
    Text(snapshot.impact.givenLabel, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("impact-given"))

    // Streak (S-1/S-2/S-3): count, 30-day bar (color + shape), milestone progress.
    snapshot.streak?.let { streak ->
        Text(streak.countLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("streak-count"))
        DadStreakBar(filled = streak.barFilled)
        Text(streak.progressLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("streak-progress"))
        if (streak.atRisk) {
            Text("Give today to keep your streak.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("streak-atrisk"))
        }
    }

    // Rankings (K-2): opt-out hides the position entirely — inline toggle, never a name list.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Rankings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (snapshot.ranking.isOptedOut) {
            Text("You're not shown in rankings.", modifier = Modifier.testTag("ranking-optedout"))
            Button(onClick = { model.setRankingOptIn(true) }, modifier = Modifier.testTag("ranking-optin")) { Text("Show my ranking") }
        } else {
            snapshot.ranking.nationalLabel?.let {
                Text(it, modifier = Modifier.testTag("ranking-national"))
            }
            snapshot.ranking.cityLabel?.let { Text(it, modifier = Modifier.testTag("ranking-city")) }
            if (snapshot.ranking.nationalLabel == null && snapshot.ranking.cityLabel == null) {
                Text("Not ranked yet.", modifier = Modifier.testTag("ranking-unranked"))
            }
            Button(onClick = { model.setRankingOptIn(false) }, modifier = Modifier.testTag("ranking-optout")) { Text("Hide my ranking") }
        }
    }

    // Spend-by-category totals (A-3) — totals only, never a line-item ledger.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Where it went", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text("Total: ${snapshot.spend.totalLabel}", modifier = Modifier.testTag("spend-total"))
        snapshot.spend.categories.forEach { category ->
            Text("${category.category}: ${category.amountLabel}", style = MaterialTheme.typography.bodySmall)
        }
    }
}


// Network page + 24-badge grid (PRD N-1..N-3, B-1..B-4). Badge state is conveyed in TEXT (the
// earned/locked a11y label + rule/perk detail line), never by colour alone, and anonymous
// referrals are shown only as a count — never named.
@Composable
private fun NetworkBadgesHome(model: NetworkBadgesModel) {
    LaunchedEffect(Unit) { model.load() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("network-home"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Your network & badges", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            is NetworkBadgesPhase.Loading ->
                CircularProgressIndicator(modifier = Modifier.testTag("network-loading"))
            is NetworkBadgesPhase.Failed ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(phase.message, modifier = Modifier.testTag("network-error"))
                    DadPrimaryButton("Try again") { model.load() }
                }
            is NetworkBadgesPhase.Ready -> model.snapshot?.let { NetworkBadgesContent(model, it) }
        }
        model.actionError?.let { Text(it, modifier = Modifier.testTag("network-action-error")) }
    }
}

@Composable
private fun NetworkBadgesContent(model: NetworkBadgesModel, snapshot: NetworkBadgesSnapshot) {
    // Network summary (N-1/N-2): active count + combined annualized + next milestone.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Your network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text(snapshot.network.activeLabel, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("network-active"))
        Text(snapshot.network.annualizedLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("network-annualized"))
        snapshot.network.nextMilestone?.let {
            Text(it.progressLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("network-milestone"))
        }
    }

    // Named invitees (N-3), then the anonymous count — anonymous referrals are counted, never named.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        snapshot.network.namedInvitees.forEach { invitee ->
            val suffix = if (invitee.active) "giving" else "paused"
            Text("${invitee.name} — $suffix", style = MaterialTheme.typography.bodySmall)
        }
        snapshot.network.anonymousLabel?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("network-anonymous"))
        }
    }

    // Sharing visibility (opt-in): off = anonymous, no links; choosing a public name mints links.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Visibility", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        val sharing = snapshot.sharing
        Text(sharing.visibilityLabel, modifier = Modifier.testTag("sharing-visibility"))
        if (sharing.isPublic) {
            sharing.shareLink?.let { Text("Share: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sharing-link")) }
            sharing.inPersonLink?.let { Text("In person: $it", style = MaterialTheme.typography.bodySmall) }
        } else {
            var publicName by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = publicName, onValueChange = { publicName = it.take(MAX_PUBLIC_NAME) },
                label = { Text("First name and last initial") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("sharing-name"),
            )
            Button(
                onClick = { model.choosePublicName(publicName) },
                enabled = publicName.isNotBlank(),
                modifier = Modifier.testTag("sharing-optin"),
            ) { Text("Show my name & get a referral link") }
        }
    }

    // 24-badge grid (B-1..B-4): earned-in-colour / greyed-with-rule, state in TEXT for a11y.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Badges", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text(snapshot.grid.summaryLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("badge-summary"))
        snapshot.grid.badges.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { badge -> BadgeTile(badge, Modifier.weight(1f)) }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BadgeTile(badge: Badge, modifier: Modifier = Modifier) {
    // Earned badges render in the brand colour; locked badges greyed. State is ALSO in the a11y
    // label + the detail line text (never colour alone) — a colour-blind or screen-reader user
    // gets earned/locked from text (B-1, WCAG).
    val dark = isSystemInDarkTheme()
    val color = if (badge.earned) DadTheme.brandAccent(dark) else DadTheme.color(if (dark) "dkmut" else "muted")
    Column(
        modifier = modifier
            .testTag(if (badge.earned) "badge-earned-${badge.code}" else "badge-locked-${badge.code}")
            .semantics { contentDescription = badge.accessibilityLabel },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(badge.title, style = MaterialTheme.typography.labelLarge, color = color)
        Text(if (badge.earned) "Earned" else "Locked", style = MaterialTheme.typography.labelSmall, color = color)
        Text(badge.detailLine, style = MaterialTheme.typography.bodySmall)
    }
}


// Events home (PRD E-1..E-10): the events list (soonest-first, invite-only marked) with an
// inline reserve/waitlist action per row and inline confirmations — never an OS dialog, per the
// UITest-determinism lesson. Bound to EventsModel. The camera QR check-in (E-8) is a device seam
// (CameraPermission in PlatformServices) validated structurally; its logic is unit-tested.
@Composable
private fun EventsHome(model: EventsModel) {
    LaunchedEffect(Unit) { model.load() }
    var confirmingCancel by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("events-home"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Events & volunteering", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            is EventsPhase.Loading ->
                CircularProgressIndicator(modifier = Modifier.testTag("events-loading"))
            is EventsPhase.Failed ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(phase.message, modifier = Modifier.testTag("events-error"))
                    DadPrimaryButton("Try again") { model.load() }
                }
            is EventsPhase.Ready -> {
                model.actionError?.let { Text(it, modifier = Modifier.testTag("events-action-error")) }
                // Reserve confirmation (E-4) — inline, after the last reserve action.
                model.lastOutcome?.let { outcome ->
                    Column(
                        modifier = Modifier.testTag("reserve-confirm"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(outcome.confirmationTitle, style = MaterialTheme.typography.titleMedium)
                        Text(outcome.confirmationDetail, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { model.clearOutcome() }) { Text("Done") }
                    }
                }
                model.events.forEach { event -> EventRow(model, event, confirmingCancel) { confirmingCancel = it } }
            }
        }
    }
}

@Composable
private fun EventRow(model: EventsModel, event: DonorEvent, confirmingCancel: String?, setConfirming: (String?) -> Unit) {
    Column(
        modifier = Modifier.testTag("event-${event.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(event.title, style = MaterialTheme.typography.titleMedium)
        val starts = remember(event.startsAt) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(event.startsAt) }
        Text(starts, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("event-start-${event.id}"))
        Text("${event.venue} · ${event.spotsLabel}", style = MaterialTheme.typography.bodySmall)
        if (event.isInviteOnly) {
            // Invite-only marker (E-5): state in text, not colour alone.
            Text("Invite only", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("invite-only-${event.id}"))
        }
        when (event.availability(Date())) {
            is EventAvailability.Reservable ->
                Button(onClick = { model.reserve(event.id) }, modifier = Modifier.testTag("reserve-${event.id}")) { Text("Reserve") }
            EventAvailability.Waitlistable ->
                Button(onClick = { model.reserve(event.id) }, modifier = Modifier.testTag("waitlist-${event.id}")) { Text("Join waitlist") }
            EventAvailability.Reserved, EventAvailability.Waitlisted -> {
                if (confirmingCancel == event.id) {
                    // Inline cancel confirmation (no OS dialog).
                    Column(modifier = Modifier.testTag("cancel-confirm-${event.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Cancel your spot?", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { model.cancel(event.id); setConfirming(null) }) { Text("Give up my spot") }
                        Button(onClick = { setConfirming(null) }) { Text("Keep it") }
                    }
                } else {
                    Button(onClick = { setConfirming(event.id) }, modifier = Modifier.testTag("cancel-${event.id}")) { Text("Cancel") }
                }
            }
            EventAvailability.CheckedIn -> Text("Checked in", modifier = Modifier.testTag("checkedin-${event.id}"))
            is EventAvailability.NotYetOpen -> Text("Booking not open yet", style = MaterialTheme.typography.labelSmall)
            EventAvailability.Cancelled -> Text("Cancelled", modifier = Modifier.testTag("cancelled-${event.id}"))
        }
    }
}


// Account & settings (PRD §6.11/§6.12): profile header, notification + privacy toggles, volunteer
// opt-in, sign out, and the store-mandated delete-account flow with a typed-word confirm gate.
// Bound to SettingsModel; all inline (no OS dialogs), per the UITest-determinism lesson.
@Composable
private fun SettingsHome(model: SettingsModel, onBack: (() -> Unit)? = null, onSignedOut: (() -> Unit)? = null) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut?.invoke() }
    var deleteTyped by rememberSaveable { mutableStateOf("") }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("settings-home"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        if (onBack != null && !model.signedOut) Button(onClick = onBack, modifier = Modifier.testTag("settings-back")) { Text("Back") }
        if (model.signedOut) {
            Text("You're signed out.", modifier = Modifier.testTag("settings-signed-out"))
            return@Column
        }
        when (val phase = model.phase) {
            is SettingsPhase.Loading ->
                CircularProgressIndicator(modifier = Modifier.testTag("settings-loading"))
            is SettingsPhase.Failed ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(phase.message, modifier = Modifier.testTag("settings-error"))
                    DadPrimaryButton("Try again") { model.load() }
                }
            is SettingsPhase.Ready -> model.snapshot?.let { snapshot ->
                SettingsContent(model, snapshot, confirmingDelete, deleteTyped,
                    onConfirmingChange = { confirmingDelete = it }, onTypedChange = { deleteTyped = it })
            }
        }
        model.actionError?.let { Text(it, modifier = Modifier.testTag("settings-action-error")) }
    }
}

@Composable
private fun SettingsContent(
    model: SettingsModel, snapshot: SettingsSnapshot, confirmingDelete: Boolean, deleteTyped: String,
    onConfirmingChange: (Boolean) -> Unit, onTypedChange: (String) -> Unit,
) {
    // Profile header — role is immutable (D-1), ZIP drives streak day rollover.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(snapshot.profile.email, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("settings-email"))
        Text("ZIP: ${snapshot.profile.zipLabel}", style = MaterialTheme.typography.bodySmall)
    }

    // Notification toggles (§6.11) — email then push categories. Payment-failure email is absent
    // by design (non-toggleable server-side).
    SettingsToggleSection("Emails", snapshot.preferences.emailToggles, model)
    SettingsToggleSection("Notifications", snapshot.preferences.pushToggles, model)

    // Privacy / participation switches.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Privacy", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        ToggleRow("Show my ranking", snapshot.preferences.showRanking, "ranking-toggle") {
            model.toggle("rankings_opted_out", !it) // inverted: showing = not opted out
        }
        ToggleRow("Network visible", snapshot.preferences.networkVisible, "network-toggle") {
            model.toggle("network_visible", it)
        }
        ToggleRow("Volunteer opt-in", snapshot.preferences.volunteer, "volunteer-toggle") {
            model.toggle("volunteer", it)
        }
    }

    // Sign out.
    Button(onClick = { model.signOut() }, modifier = Modifier.testTag("settings-signout")) { Text("Sign out") }

    // Delete account (store-mandated, §6.12): a typed-word confirm gate, inline.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Delete account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (!confirmingDelete) {
            Button(onClick = { onConfirmingChange(true) }, modifier = Modifier.testTag("delete-start")) { Text("Delete my account") }
        } else {
            Text(DeleteAccountGate(deleteTyped).helpText, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = deleteTyped, onValueChange = onTypedChange,
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("delete-input"),
            )
            Button(
                onClick = { model.deleteAccount(DeleteAccountGate(deleteTyped)) },
                enabled = DeleteAccountGate(deleteTyped).isConfirmed,
                modifier = Modifier.testTag("delete-confirm"),
            ) { Text("Permanently delete") }
            Button(onClick = { onConfirmingChange(false); onTypedChange("") }) { Text("Keep my account") }
        }
    }
}

@Composable
private fun SettingsToggleSection(title: String, toggles: List<Preferences.Toggle>, model: SettingsModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        toggles.forEach { t -> ToggleRow(t.label, t.value, "pref-${t.key}") { model.toggle(t.key, it) } }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}


// Partner portal (PRD P-*, EM-*, §7). The pending-approval gate shows nothing else (P-2); the
// loaded portal renders verified-or-hidden Overview tiles (§7.3), spend totals, the Our People
// rollup with <5 suppression (EM-3), and admin-gated user management (§7.2) — all inline.
@Composable
private fun PartnerPortal(model: PartnerModel) {
    LaunchedEffect(Unit) { model.load() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("partner-portal"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Partner portal", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            is PartnerPhase.Loading ->
                CircularProgressIndicator(modifier = Modifier.testTag("partner-loading"))
            is PartnerPhase.Pending ->
                // P-2: pending approval shows nothing else.
                Column(modifier = Modifier.testTag("partner-pending"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Awaiting approval", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
                    Text("Your organization’s account is pending review. We’ll email you when it’s approved.")
                }
            is PartnerPhase.Failed ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(phase.message, modifier = Modifier.testTag("partner-error"))
                    DadPrimaryButton("Try again") { model.load() }
                }
            is PartnerPhase.Ready -> model.snapshot?.let { PartnerContent(model, it) }
        }
        model.actionError?.let { Text(it, modifier = Modifier.testTag("partner-action-error")) }
    }
}

@Composable
private fun PartnerContent(model: PartnerModel, snapshot: PartnerSnapshot) {
    val overview = snapshot.overview
    // Overview tiles — a figure renders ONLY when verified (§7.3); unverified is omitted.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(overview.organization, style = MaterialTheme.typography.titleMedium)
        overview.verifiedLine?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("partner-verified-line"))
        }
        overview.committed.label?.let {
            Text("Committed: $it", modifier = Modifier.testTag("partner-committed"))
        }
        // deployed is unverified in the preview -> intentionally absent (never estimated)
        overview.peopleReached.label?.let {
            Text("People reached: $it", modifier = Modifier.testTag("partner-reached"))
        }
        overview.enrolledLabel?.let {
            Text("Enrolled: $it", modifier = Modifier.testTag("partner-enrolled"))
        }
    }

    // Our People rollup — figures or the suppression notice (EM-3).
    snapshot.people?.let { people ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Our people", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            val notice = people.suppressionNotice
            if (notice != null) {
                Text(notice, modifier = Modifier.testTag("partner-suppressed"))
            } else {
                people.givenLabel?.let { Text("Given: $it", modifier = Modifier.testTag("partner-given")) }
            }
        }
    }

    // Spend totals (P-12).
    if (snapshot.spend.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Spend", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            snapshot.spend.forEach { Text("${it.category}: ${it.amountLabel}", style = MaterialTheme.typography.bodySmall) }
        }
    }

    // Users — admin can invite/remove inline; a viewer sees the list read-only (§7.2).
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Team", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        var confirmingRemoval by rememberSaveable { mutableStateOf<String?>(null) }
        snapshot.users.forEach { user ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isSelf = user.id == snapshot.selfId
                Text("${user.name}${if (isSelf) " (you)" else ""} · ${user.role}", modifier = Modifier.weight(1f))
                if (model.canRemove(user)) {
                    if (confirmingRemoval == user.id) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { model.removeUser(user.id); confirmingRemoval = null },
                                modifier = Modifier.testTag("partner-remove-confirm-${user.id}")
                                    .semantics { contentDescription = "Confirm remove ${user.name}" },
                            ) { Text("Yes, remove") }
                            Button(
                                onClick = { confirmingRemoval = null },
                                modifier = Modifier.testTag("partner-remove-keep-${user.id}"),
                            ) { Text("Keep") }
                        }
                    } else {
                        Button(
                            onClick = { confirmingRemoval = user.id },
                            modifier = Modifier.testTag("partner-remove-${user.id}")
                                .semantics { contentDescription = "Remove ${user.name}" },
                        ) { Text("Remove") }
                    }
                }
            }
        }
        if (model.role.canManageUsers) {
            var inviteEmail by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = inviteEmail, onValueChange = { inviteEmail = it },
                label = { Text("Invite a viewer by email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().testTag("partner-invite-email"),
            )
            Button(
                onClick = { model.inviteUser(inviteEmail.trim(), PartnerRole.VIEWER); inviteEmail = "" },
                enabled = isPlausibleEmail(inviteEmail),
                modifier = Modifier.testTag("partner-invite"),
            ) { Text("Invite user") }
        } else {
            Text("Read-only access", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("partner-readonly"))
        }
    }
}
