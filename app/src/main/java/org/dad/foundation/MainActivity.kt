package org.dad.foundation

import android.os.Bundle
import android.content.Intent
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    /** "<name> invited you", after an invite link was opened before sign-in. */
    var invitation: String? by mutableStateOf(null)
        private set
    /** Bumped on every sign-in/sign-out so per-session view models are never reused across sessions. */
    var generation: Int by mutableIntStateOf(0)
        private set
    var signIn: SignInService = UnconfiguredSignIn
        private set
    // Observable: a restored session composes the shell before the services are built, so the shell
    // must recompose when they arrive (otherwise Settings and the donor home never appear).
    var settings: SettingsService? by mutableStateOf(null)
        private set
    var auth: AuthServices = AuthServices(UnconfiguredSignIn, UnconfiguredCredentialAuth, UnconfiguredCredentialAuth, UnconfiguredCredentialAuth, ExternalSignInDeps())
        private set
    var passwordSettings: PasswordSettingsService? by mutableStateOf(null)
        private set
    var dashboard: DashboardService? by mutableStateOf(null)
        private set
    var giving: GivingKit? by mutableStateOf(null)
        private set
    var events: EventsService? by mutableStateOf(null)
        private set
    var engagement: EngagementService? by mutableStateOf(null)
        private set
    var management: AccountManagementService? by mutableStateOf(null)
        private set
    var wallet: WalletPassService? by mutableStateOf(null)
        private set
    var partner: PartnerKit? by mutableStateOf(null)
        private set
    var analytics: AnalyticsUploader? by mutableStateOf(null)
        private set
    var pushTokens: PushTokenProvider? by mutableStateOf(null)
        private set
    /** A screen a tapped notification or link asked for, opened by the donor shell once signed in. */
    var destination: AccountDestination? by mutableStateOf(null)
        private set
    private var pushRegistered = false
    /** A partner session is in use (and no donor one): the root shows the partner portal. */
    var partnerSignedIn: Boolean by mutableStateOf(false)
        private set
    /** A tapped partner sign-in link waiting for the partner sign-in screen to accept it. */
    var pendingPartnerToken: String? by mutableStateOf(null)
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
                .also {
                    services = it; signIn = it.signIn; settings = it.settings; passwordSettings = it.passwordSettings; dashboard = it.dashboard; giving = it.giving; events = it.events; engagement = it.engagement
                    management = it.management; wallet = it.wallet; partner = it.partner; analytics = it.analytics; pushTokens = it.pushTokens
                    auth = AuthServices(it.signIn, it.signup, it.signInLink, it.passwordSignIn, it.external)
                }
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
            // Without a donor session, a partner session still within its 12 hours reopens the portal.
            partnerSignedIn = !hasSession && active?.partner?.session?.isSignedIn == true
            booted = true
            active?.analytics?.emit(AnalyticsEvent.Plain(PlainEvent.APP_OPENED))
            if (hasSession) gate.signedIn()?.let { destination = it }
            if (hasSession) load() else state = if (active == null) AccountState.Unavailable else AccountState.SignedOut
        }
    }

    /** A sign-in completed (the session already holds the credentials): enter the donor shell. */
    fun onSignedIn() {
        notice = null
        gate.signedIn()?.let { destination = it }
        pushRegistered = false
        generation++
        hasSession = true
        load()
    }

    /** Sign-out, account deletion or a revoked session: back to sign-in. */
    fun onSignedOut(message: String? = null) {
        notice = message
        pushRegistered = false
        destination = null
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
                invitation = null
                hasSession = true
                state = AccountState.Ready(profile)
                registerPush(active)
                active.links?.let { links -> viewModelScope.launch(io) { links.bindCompany() } }
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
                    if (claimed) { completedProof = activeProof; gate.signedIn()?.let { destination = it }; generation++ }
                    activeProof = null
                }
                pendingProof?.let { pendingProof = null; load(claim = it) }
            }
        }
    }
    fun onPartnerSignedIn() {
        notice = null
        generation++
        partnerSignedIn = true
    }

    /** Best-effort, once per sign-in and launch: platforms rotate tokens, so the server wants every launch. */
    private fun registerPush(active: AppServices) {
        val push = active.push ?: return
        val tokens = active.pushTokens ?: return
        if (pushRegistered) return
        pushRegistered = true
        viewModelScope.launch(io) {
            try { tokens.token()?.let { push.register(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pushRegistered = false }
        }
    }

    /** A tapped notification: counted (`push_opened`), then its screen is opened, after sign-in if need be. */
    fun openNotification(tap: PushTap, router: LinkRouter?) {
        analytics?.emit(AnalyticsEvent.PushOpened(tap.category))
        val target = tap.path?.let { router?.notification(it) } ?: return
        gate.receive(target, hasSession)?.let { destination = it }
    }

    fun destinationOpened() { destination = null }

    fun onPartnerSignedOut() {
        generation++
        partnerSignedIn = false
    }

    fun partnerTokenConsumed() { pendingPartnerToken = null }

    fun configurationUnavailable() { hasSession = false; notice = PhoneSignInModel.NOT_CONFIGURED; state = AccountState.Unavailable }
    fun receive(value: String, router: LinkRouter): String? {
        return when (val intent = router.route(value)) {
            is LinkIntent.Account -> if (!partnerSignedIn) {
                gate.receive(intent.destination, state is AccountState.Ready)?.let { destination = it }
                if (hasSession) load()
                null
            } else value
            is LinkIntent.Claim -> {
                if (partnerSignedIn || (BuildConfig.DAD_API_ORIGIN.isBlank() && !options.stubBackend)) value
                else {
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(intent.token.toByteArray()).joinToString("") { "%02x".format(it) }
                    if (activeProof == null && completedProof != digest) {
                        activeProof = digest
                        intent.next?.let { gate.receive(it, false) }
                        load(claim = intent.token)
                    }
                    null
                }
            }
            // A partner already in the portal stays put; a donor's app cannot also be a partner's, so the website
            // takes the link.
            is LinkIntent.PartnerSignIn -> when {
                partnerSignedIn -> null
                hasSession -> value
                else -> { pendingPartnerToken = intent.token; null }
            }
            is LinkIntent.Referral -> viewModelScope.launch {
                val links = ensureServices()?.links ?: return@launch
                val name = withContext(io) { links.captureReferral(intent.code, intent.inPerson) }
                if (name != null && state !is AccountState.Ready) invitation = "$name invited you to give with DAD."
            }.let { null }
            is LinkIntent.Company -> viewModelScope.launch {
                val links = ensureServices()?.links ?: return@launch
                withContext(io) {
                    links.captureCompany(intent.code)
                    if (hasSession) links.bindCompany()
                }
            }.let { null }
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
    private var incomingTap: PushTap? = null
    private var tapEvent by mutableIntStateOf(0)
    /** The configured public origin invite links are built from; blank means none (no link is ever invented). */
    private val linkPublicOrigin by lazy {
        BuildConfig.DAD_PUBLIC_ORIGIN.ifBlank { if (BuildConfig.DEBUG && intent.getBooleanExtra("stub_backend", false)) DebugBackend.PUBLIC_ORIGIN else "" }
    }
    private val linkRouter by lazy {
        val publicOrigin = linkPublicOrigin
        if (publicOrigin.isBlank()) null
        else runCatching { LinkRouter(publicOrigin, assets.open("links.json").bufferedReader().use { it.readText() }) }.getOrNull()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingLink = intent.dataString
        linkEvent++
        incomingTap = PushTap.from(intent.getStringExtra("category"), intent.getStringExtra("path"))
        tapEvent++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launch = LaunchOptions(
            unavailableFirst = BuildConfig.DEBUG && intent.getBooleanExtra("preview_unavailable", false),
            signedOut = BuildConfig.DEBUG && intent.getBooleanExtra("preview_signed_out", false),
            stubBackend = BuildConfig.DEBUG && intent.getBooleanExtra("stub_backend", false),
            stubDeleteBlocked = BuildConfig.DEBUG && intent.getBooleanExtra("stub_delete_blocked", false),
            stubSignedIn = BuildConfig.DEBUG && intent.getBooleanExtra("stub_signed_in", false),
            stubDashboard = when {
                !BuildConfig.DEBUG -> StubDashboard.POPULATED
                intent.getBooleanExtra("stub_dashboard_empty", false) -> StubDashboard.EMPTY
                intent.getBooleanExtra("stub_dashboard_fail_first", false) -> StubDashboard.FAIL_FIRST
                intent.getBooleanExtra("stub_dashboard_unranked", false) -> StubDashboard.UNRANKED
                intent.getBooleanExtra("stub_dashboard_slow", false) -> StubDashboard.SLOW
                else -> StubDashboard.POPULATED
            },
            stubGift = when {
                !BuildConfig.DEBUG -> StubGift.NONE
                intent.getBooleanExtra("stub_gift_fail_first", false) -> StubGift.ACTIVE_FAIL_FIRST
                intent.getBooleanExtra("stub_gift_active", false) -> StubGift.ACTIVE
                else -> StubGift.NONE
            },
            stubOpenGift = BuildConfig.DEBUG && intent.getBooleanExtra("stub_open_gift", false),
            stubEvents = when {
                !BuildConfig.DEBUG -> StubEventsScenario.STANDARD
                intent.getBooleanExtra("stub_events_invited", false) -> StubEventsScenario.INVITED
                intent.getBooleanExtra("stub_events_fail_first", false) -> StubEventsScenario.FAIL_FIRST
                intent.getBooleanExtra("stub_events_not_eligible", false) -> StubEventsScenario.NOT_ELIGIBLE
                else -> StubEventsScenario.STANDARD
            },
            stubEngagement = when {
                !BuildConfig.DEBUG -> StubEngagementScenario.STANDARD
                intent.getBooleanExtra("stub_no_public_name", false) -> StubEngagementScenario.NO_PUBLIC_NAME
                intent.getBooleanExtra("stub_rankings_fail_toggle", false) -> StubEngagementScenario.TOGGLE_FAIL
                intent.getBooleanExtra("stub_badges_down", false) -> StubEngagementScenario.BADGES_DOWN
                intent.getBooleanExtra("stub_network_down", false) -> StubEngagementScenario.NETWORK_DOWN
                intent.getBooleanExtra("stub_spend_unpublished", false) -> StubEngagementScenario.SPEND_UNPUBLISHED
                else -> StubEngagementScenario.STANDARD
            },
            stubOpenEngagement = BuildConfig.DEBUG && intent.getBooleanExtra("stub_open_engagement", false),
            stubPartnerSignedIn = BuildConfig.DEBUG && intent.getBooleanExtra("stub_partner_signed_in", false),
            stubAnalyticsUnset = BuildConfig.DEBUG && intent.getBooleanExtra("stub_analytics_unset", false),
            stubPush = BuildConfig.DEBUG && intent.getBooleanExtra("stub_push", false),
        )
        val giving = BuildConfig.DEBUG && intent.getBooleanExtra("preview_giving", false)
        val impact = BuildConfig.DEBUG && (intent.getBooleanExtra("preview_impact", false) || intent.getBooleanExtra("preview_network", false))
        val events = BuildConfig.DEBUG && intent.getBooleanExtra("preview_events", false)
        val settings = BuildConfig.DEBUG && intent.getBooleanExtra("preview_settings", false)
        val partner = BuildConfig.DEBUG && intent.getBooleanExtra("preview_partner", false)
        val partnerViewer = BuildConfig.DEBUG && intent.getBooleanExtra("preview_partner_viewer", false)
        val appContext = applicationContext
        incomingLink = intent.dataString
        incomingTap = PushTap.from(intent.getStringExtra("category"), intent.getStringExtra("path"))
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
                        AppServices(session, PreviewSignIn(), PreviewSessionSettings(session),
                            partner = PartnerKit(PartnerSession(PreviewStore(signedIn = false)), PreviewPartnerSignIn, PreviewPartnerService()))
                    } else {
                        val origin = BuildConfig.DAD_API_ORIGIN
                        lateinit var session: AccountSession
                        val analytics = AnalyticsUploader(PrefsAnalyticsConsent(appContext), WireAnalytics(origin, access = { session.accessToken() }), scope)
                        val http = defaultNativeClient().newBuilder().addInterceptor(AnalyticsObserver(analytics)).build()
                        session = AccountSession(WireAPI(origin, http), KeystoreCredentialStore(appContext), scope)
                        val attribution = PrefsAttributionStore(appContext)
                        val linkTokens = PrefsLinkTokenStore(appContext)
                        val milestones = PrefsMilestoneStore(appContext)
                        val credentialAuth = WireCredentialAuth(origin, android.os.Build.MODEL, http)
                        val external = WireExternalSignIn(origin, android.os.Build.MODEL, http)
                        AppServices(
                            session,
                            WireAuth(origin, android.os.Build.MODEL, http),
                            WireSettings(ProductClient(origin, session, http), onLocalWipe = { attribution.clear(); linkTokens.clear(); milestones.clear() }),
                            signup = credentialAuth,
                            signInLink = credentialAuth,
                            passwordSignIn = credentialAuth,
                            passwordSettings = WirePasswordSettings(ProductClient(origin, session, http)),
                            dashboard = WireDashboard(ProductClient(origin, session, http)),
                            giving = GivingKit(WireGiving(ProductClient(origin, session, http), referral = { linkTokens.load().referral })),
                            events = WireEvents(ProductClient(origin, session, http)),
                            engagement = WireEngagement(ProductClient(origin, session, http)),
                            management = WireAccountManagement(ProductClient(origin, session, http)),
                            wallet = WireWalletPass(ProductClient(origin, session, http)),
                            partner = PartnerSession(KeystoreCredentialStore(appContext, "partner-session", refreshable = false)).let {
                                PartnerKit(it, WirePartnerAuth(origin, it, http), WirePartner(origin, it, http))
                            },
                            analytics = analytics,
                            push = WireDonorPush(ProductClient(origin, session, http)),
                            links = LinkAttribution(WireLinkAttribution(origin, ProductClient(origin, session, http), http), linkTokens, analytics),
                            external = ExternalSignInDeps(
                                // No Credential Manager provider is linked, so a configured ID alone never switches a method on.
                                capabilities = SignInCapabilities.of(SignInConfig(BuildConfig.DAD_GOOGLE_WEB_CLIENT_ID, BuildConfig.DAD_PASSKEY_RP_ID)),
                                oidc = external, passkey = external,
                            ),
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
            LaunchedEffect(tapEvent) {
                incomingTap?.let { model.openNotification(it, linkRouter) }
                incomingTap = null
            }
            val dark = isSystemInDarkTheme()
            // Material primary mirrors the web/DAD primary button: brand fill with an ink label
            // (ink-on-brand = 5.11:1, WCAG AA) in both themes. Text links/icons on the page keep
            // using brand-ink via DadTheme.brandAccent() — a separate role from the button fill.
            val colors = if (dark) darkColorScheme(primary = tokenColor("brand"), onPrimary = tokenColor("ink"), background = tokenColor("ink-deep"))
                else lightColorScheme(primary = tokenColor("brand"), onPrimary = tokenColor("ink"), background = tokenColor("paper"))
            MaterialTheme(
                colorScheme = colors,
                typography = dadTypography(),
                shapes = Shapes(
                    small = RoundedCornerShape(DadTheme.radius("field")),
                    medium = RoundedCornerShape(DadTheme.radius("card")),
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val router = remember { RoleRouter() }
                    var root by remember { mutableStateOf<RoleRouter.Root>(router.root) }
                    // A session-backed shell is the donor journey — the donor landing owns every
                    // account sub-state (loading / ready / unavailable / signed-out) and its own
                    // recovery, so stay on it. Only a genuinely session-less app goes to role-select.
                    LaunchedEffect(model.booted, model.hasSession, model.partnerSignedIn) {
                        if (model.booted) {
                            when {
                                model.hasSession -> router.signedIn(AppRole.DONOR)
                                model.partnerSignedIn -> router.signedIn(AppRole.PARTNER)
                                else -> router.signedOut()
                            }
                        }
                        root = router.root
                    }
                    val partnerKit = model.partner
                    when (val current = root) {
                        is RoleRouter.Root.Authenticated if current.role == AppRole.PARTNER && partnerKit != null && !partner && !partnerViewer ->
                            PartnerPortal(
                                viewModel(key = "partner-${model.generation}", factory = PartnerModel.factory(partnerKit.service)),
                                publicOrigin = linkPublicOrigin,
                                onSignedOut = { model.onPartnerSignedOut() },
                                pushTokens = model.pushTokens,
                            )
                        is RoleRouter.Root.Restoring, is RoleRouter.Root.Authenticated ->
                            if (partnerViewer) {
                                PartnerPortal(viewModel(factory = PartnerModel.previewFactory(role = PartnerRole.VIEWER)), publicOrigin = "https://dad.example")
                            } else if (partner) {
                                PartnerPortal(viewModel(factory = PartnerModel.previewFactory()), publicOrigin = "https://dad.example")
                            } else if (giving) {
                                // Giving preview exercises the signed-in GiftScreen directly. The
                                // donor shell reaches Restoring/Authenticated immediately (hasSession
                                // starts true), so there is no load-delay gap to short-circuit.
                                GiftScreen(viewModel(factory = GivingModel.previewFactory()), stubComplete = { })
                            } else if (impact) {
                                EngagementHome(
                                    viewModel(factory = EngagementModel.previewFactory()),
                                    publicOrigin = "https://dad.example",
                                    sharer = ReferralShareAction(AndroidReferralSharer(this@MainActivity)),
                                )
                            } else if (events) {
                                EventsScreen(viewModel(factory = EventsModel.previewFactory()))
                            } else if (settings) {
                                SettingsHome(viewModel(factory = SettingsModel.previewFactory()))
                            } else {
                                DonorShell(model, publicOrigin = linkPublicOrigin, openGiftAtLaunch = launch.stubOpenGift, openEngagementAtLaunch = launch.stubOpenEngagement)
                            }
                        is RoleRouter.Root.Unauthenticated -> UnauthenticatedStack(
                            notice = model.notice,
                            invitation = model.invitation,
                            auth = model.auth,
                            establish = model.establish(),
                            onPastedLink = { text -> linkRouter?.let { router -> PastedLink.claimToken(text, router)?.let { model.receive(text.trim(), router); true } } ?: false },
                            generation = model.generation,
                            onSignedIn = { model.onSignedIn() },
                            partnerSignIn = model.partner?.signIn,
                            partnerToken = model.pendingPartnerToken,
                            onPartnerTokenConsumed = { model.partnerTokenConsumed() },
                            onPartnerSignedIn = { model.onPartnerSignedIn() },
                            analytics = model.analytics,
                            publicOrigin = linkPublicOrigin,
                        )
                    }
                }
            }
        }
    }
}

/** The initiative whose spend drill-in is open (A-3), saved so the screen survives a configuration change. */
private data class SpendTarget(val id: String, val title: String)

private val SpendTargetSaver = androidx.compose.runtime.saveable.Saver<SpendTarget?, List<String>>(
    save = { target -> target?.let { listOf(it.id, it.title) } ?: emptyList() },
    restore = { saved -> if (saved.size == 2) SpendTarget(saved[0], saved[1]) else null },
)

/** The four bottom-bar destinations. Each keeps its own retained screen state via the holder. */
private enum class DonorTab { HOME, CIRCLE, EVENTS, GIVING }

/**
 * A screen pushed modally over the current tab: one at a time, saved across a configuration change.
 * Settings and the gift-history record are reached from inside a tab; a Spend drill-in carries the
 * initiative it opened. The invite QR stays internal to [EngagementHome] (its own `showQr` state), so
 * it is deliberately NOT a Pushed case here — only its `qr-close` tag moved into that screen.
 */
private sealed interface Pushed {
    data object Settings : Pushed
    data object History : Pushed
    data object Gift : Pushed
    data object Spend : Pushed
}

private val PushedSaver = androidx.compose.runtime.saveable.Saver<Pushed?, String>(
    save = { pushed ->
        when (pushed) {
            Pushed.Settings -> "settings"
            Pushed.History -> "history"
            Pushed.Gift -> "gift"
            Pushed.Spend -> "spend"
            null -> ""
        }
    },
    restore = { saved ->
        when (saved) {
            "settings" -> Pushed.Settings
            "history" -> Pushed.History
            "gift" -> Pushed.Gift
            "spend" -> Pushed.Spend
            else -> null
        }
    },
)

/**
 * The donor shell: a Material 3 [Scaffold] with a bottom [NavigationBar] of four tabs (Home / Circle /
 * Events / Giving) and a per-destination [TopAppBar]. A hand-rolled, saveable tab + pushed-screen stack
 * replaces the former boolean flags — Navigation Compose is NOT on the classpath and is not added. Each
 * tab keeps its own retained state through [rememberSaveableStateHolder], and every per-generation
 * `viewModel(key=…)` key is unchanged so a session's models are never reused across sign-ins.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DonorShell(model: AccountModel, publicOrigin: String, openGiftAtLaunch: Boolean = false, openEngagementAtLaunch: Boolean = false) {
    var tab by rememberSaveable { mutableStateOf(if (openGiftAtLaunch) DonorTab.GIVING else if (openEngagementAtLaunch) DonorTab.CIRCLE else DonorTab.HOME) }
    // The pushed screen over the current tab (or null for the bare tab). Spend also needs the target it
    // opened, kept in its own saveable alongside the Pushed marker (simplest: two small savers rather than
    // widening Pushed to carry data, so the existing SpendTargetSaver is reused verbatim).
    var pushed by rememberSaveable(stateSaver = PushedSaver) { mutableStateOf<Pushed?>(null) }
    var spendTarget by rememberSaveable(stateSaver = SpendTargetSaver) { mutableStateOf<SpendTarget?>(null) }
    val engagementService = model.engagement
    val context = androidx.compose.ui.platform.LocalContext.current
    val service = model.settings
    val dashboard = model.dashboard
    val kit = model.giving
    val eventsService = model.events
    // One giving model for the whole signed-in session: the home card, the gift screen and the
    // cancel-before-delete path in Settings all read the same state, so a change shows everywhere at once.
    val giving: GivingModel? = kit?.let { viewModel(key = "giving-${model.generation}", factory = GivingModel.factory(it)) }
    // Hoisted so the celebration reads the same Founding flag Home shows; the tab passes this instance
    // down (same key), so Home's own load still runs once.
    val dash: DashboardModel? = dashboard?.let { viewModel(key = "home-${model.generation}", factory = DashboardModel.factory(it)) }
    val milestones = remember { PrefsMilestoneStore(context) }
    val shareMilestone: (String) -> Unit = { line ->
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, line)
        }
        try { context.startActivity(android.content.Intent.createChooser(send, "Share")) } catch (_: android.content.ActivityNotFoundException) { }
    }
    val celebrating = giving?.celebrating == true
    // A just-created Founding status has to be fetched; the chip stays absent until the flag is known true.
    LaunchedEffect(celebrating) { if (celebrating) dash?.load() }
    // Screens swap in and out of this one slot; the holder keeps each one's remembered state (the half-typed
    // delete confirmation in Settings) while another screen is on top.
    val holder = rememberSaveableStateHolder()
    val ready = model.state is AccountState.Ready
    LaunchedEffect(model.destination, ready) {
        if (!ready) return@LaunchedEffect
        when (model.destination ?: return@LaunchedEffect) {
            // A tapped /account/gifts notification lands on Giving and opens the gift-history record.
            AccountDestination.GIFTS -> { tab = DonorTab.GIVING; if (dashboard != null) pushed = Pushed.History }
            // Payment-method deep link lands on the Giving tab (GiftScreen hosts the payment method).
            AccountDestination.PAYMENT_METHOD -> if (giving != null) tab = DonorTab.GIVING
            // Profile opens Settings over the current tab, as before.
            AccountDestination.PROFILE -> if (service != null) pushed = Pushed.Settings
            AccountDestination.DASHBOARD -> tab = DonorTab.HOME
        }
        model.destinationOpened()
    }

    // A pushed screen is modal over the tabs: it shows its own back-arrow app bar and hides the
    // NavigationBar, matching the prior "a pushed screen covers the shell" behaviour.
    val onShell = pushed == null
    // The celebration is drawn OVER the shell rather than replacing it: leaving composition would re-run
    // the Gift screen's load on return and wipe its "Your gift is set up." notice. While it shows, the
    // shell underneath is hidden from accessibility (the opaque Surface already blocks touches).
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = if (giving != null && celebrating) Modifier.clearAndSetSemantics {} else Modifier,
        topBar = {
            when (pushed) {
                Pushed.Settings -> DadBackBar("settings-back", "Back") { pushed = null }
                Pushed.History -> DadBackBar("history-close", "Back") { pushed = null }
                // Gift pushed from Settings' cancel-gift: back returns to Settings (carries `gift-close`).
                Pushed.Gift -> DadBackBar("gift-close", "Back") { pushed = Pushed.Settings }
                Pushed.Spend -> Unit // InitiativeSpendScreen owns its app bar
                null -> when (tab) {
                    DonorTab.HOME -> HomeTopBar(model, onSettings = if (service != null) ({ pushed = Pushed.Settings }) else null)
                    // The close affordance on a tab returns to Home, preserving the prior close→Home contract.
                    DonorTab.CIRCLE -> if (engagementService != null) DadBackBar("engagement-close", "Back") { tab = DonorTab.HOME }
                    DonorTab.EVENTS -> if (eventsService != null) DadBackBar("events-back", "Back") { tab = DonorTab.HOME }
                    DonorTab.GIVING -> if (giving != null) DadBackBar("gift-close", "Back") { tab = DonorTab.HOME }
                }
            }
        },
        floatingActionButton = {
            // Invite is Home's single filled primary: brand fill, ink content. Shown only on the bare
            // Home tab (no pushed screen), it opens the existing share flow — which lives on the Circle
            // tab (EngagementHome hosts the share sheet, QR, and the public-name step when no name is
            // set yet) — so there is no second share implementation and no new analytics event.
            if (onShell && tab == DonorTab.HOME && ready && engagementService != null) {
                ExtendedFloatingActionButton(
                    onClick = { tab = DonorTab.CIRCLE },
                    containerColor = DadTheme.color("brand"),
                    contentColor = DadTheme.color("ink"),
                    modifier = Modifier.testTag("invite-fab"),
                ) { Text("Invite", style = MaterialTheme.typography.labelLarge) }
            }
        },
        bottomBar = {
            if (onShell) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == DonorTab.HOME,
                        onClick = { tab = DonorTab.HOME },
                        icon = { Text("\u2302") },
                        label = { Text("Home") },
                        modifier = Modifier.testTag("tab-home"),
                    )
                    NavigationBarItem(
                        selected = tab == DonorTab.CIRCLE,
                        onClick = { tab = DonorTab.CIRCLE },
                        icon = { Text("\u25CE") },
                        label = { Text("Circle") },
                        enabled = engagementService != null,
                        modifier = Modifier.testTag("tab-circle"),
                    )
                    NavigationBarItem(
                        selected = tab == DonorTab.EVENTS,
                        onClick = { tab = DonorTab.EVENTS },
                        icon = { Text("\u2605") },
                        label = { Text("Events") },
                        enabled = eventsService != null,
                        modifier = Modifier.testTag("tab-events"),
                    )
                    NavigationBarItem(
                        selected = tab == DonorTab.GIVING,
                        onClick = { tab = DonorTab.GIVING },
                        icon = { Text("\u2661") },
                        label = { Text("Giving") },
                        enabled = giving != null,
                        modifier = Modifier.testTag("tab-giving"),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (pushed) {
                Pushed.Settings -> if (service != null) {
                    BackHandler { pushed = null }
                    holder.SaveableStateProvider("settings") {
                        SettingsHome(
                            viewModel(key = "settings-${model.generation}", factory = SettingsModel.factory(service)),
                            onBack = { pushed = null },
                            onSignedOut = { model.onSignedOut() },
                            password = model.passwordSettings?.let { passwordService ->
                                viewModel<PasswordChangeModel>(key = "password-${model.generation}", factory = PasswordChangeModel.factory(passwordService))
                            },
                            // Cancel-gift from Settings pushes the Gift screen OVER Settings, so its close
                            // returns here (not Home) with the half-typed delete confirm intact.
                            onCancelGift = if (giving != null) ({ pushed = Pushed.Gift }) else null,
                            management = model.management,
                            generation = model.generation,
                            analytics = model.analytics,
                        )
                    }
                }
                Pushed.History -> if (dashboard != null) {
                    BackHandler { pushed = null }
                    holder.SaveableStateProvider("history") {
                        GiftHistoryScreen(
                            viewModel(key = "history-${model.generation}", factory = GiftHistoryModel.factory(dashboard)),
                            onClose = { pushed = null },
                            onSignedOut = { pushed = null; model.onSignedOut() },
                        )
                    }
                }
                Pushed.Gift -> if (giving != null) {
                    // Pushed from Settings' cancel-gift: back returns to Settings.
                    BackHandler { pushed = Pushed.Settings }
                    holder.SaveableStateProvider("gift") {
                        GiftScreen(giving, stubComplete = kit.stubComplete, onClose = { pushed = Pushed.Settings }, onSignedOut = { pushed = null; model.onSignedOut() })
                    }
                }
                Pushed.Spend -> {
                    val target = spendTarget
                    if (target != null && engagementService != null) {
                        BackHandler { pushed = null; spendTarget = null }
                        holder.SaveableStateProvider("spend") {
                            InitiativeSpendScreen(
                                viewModel(key = "spend-${model.generation}-${target.id}", factory = InitiativeSpendModel.factory(engagementService, target.id)),
                                title = target.title,
                                onClose = { pushed = null; spendTarget = null },
                                onSignedOut = { pushed = null; spendTarget = null; model.onSignedOut() },
                            )
                        }
                    }
                }
                null -> DonorShellTab(
                    tab, model, holder, publicOrigin, context,
                    service, dashboard, dash, giving, kit, eventsService, engagementService,
                    milestones, shareMilestone,
                    onSelectTab = { tab = it },
                    onPush = { pushed = it },
                    onOpenSpend = { id, title -> spendTarget = SpendTarget(id, title); pushed = Pushed.Spend },
                )
            }
        }
    }
    if (giving != null && celebrating) {
        BackHandler { giving.acknowledgeCelebration() }
        val amount = giving.selection
        FirstGiftCelebration(
            amountMonthly = formatUsdMinor(amount.monthlyMinor) + "/month",
            amountDaily = "$${amount.dailyDollars} a day",
            founding = dash?.foundingDonor == true,
            onInvite = { giving.acknowledgeCelebration(); pushed = null; tab = DonorTab.CIRCLE },
            onDone = { giving.acknowledgeCelebration() },
        )
    }
    }

    // The analytics question is asked ONCE, after the first sign-in (moved off the Welcome screen) — the
    // same copy, options and tags, now in a ModalBottomSheet. Shown while the donor has not yet chosen.
    val analytics = model.analytics
    if (ready && analytics != null && analytics.choice == null) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { /* a choice is required; dismissing re-shows next event */ }, sheetState = sheetState) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                AnalyticsChoiceBody(analytics)
            }
        }
    }
}

/** The bare tab body (no pushed screen). Split out to keep [DonorShell] readable. */
@Composable
private fun DonorShellTab(
    tab: DonorTab,
    model: AccountModel,
    holder: androidx.compose.runtime.saveable.SaveableStateHolder,
    publicOrigin: String,
    context: android.content.Context,
    service: SettingsService?,
    dashboard: DashboardService?,
    dash: DashboardModel?,
    giving: GivingModel?,
    kit: GivingKit?,
    eventsService: EventsService?,
    engagementService: EngagementService?,
    milestones: MilestoneStore,
    onShareMilestone: (String) -> Unit,
    onSelectTab: (DonorTab) -> Unit,
    onPush: (Pushed) -> Unit,
    onOpenSpend: (String, String) -> Unit,
) {
    when (tab) {
        DonorTab.HOME -> holder.SaveableStateProvider("home") {
            DonorLanding(model) {
                if (dash != null && model.state is AccountState.Ready) {
                    DonorHome(
                        dash,
                        giving = giving,
                        onOpenGift = { onSelectTab(DonorTab.GIVING) },
                        onOpenEvents = if (eventsService != null) ({ onSelectTab(DonorTab.EVENTS) }) else null,
                        onOpenEngagement = if (engagementService != null) ({ onSelectTab(DonorTab.CIRCLE) }) else null,
                        onOpenSpend = if (engagementService != null) ({ id, title -> onOpenSpend(id, title) }) else null,
                        onOpenHistory = { onPush(Pushed.History) },
                        onSignedOut = { model.onSignedOut() },
                        milestones = milestones,
                        onShareMilestone = onShareMilestone,
                    )
                }
            }
        }
        DonorTab.CIRCLE -> if (engagementService != null) holder.SaveableStateProvider("engagement") {
            EngagementHome(
                viewModel(key = "engagement-${model.generation}", factory = EngagementModel.factory(engagementService)),
                publicOrigin = publicOrigin,
                sharer = remember { ReferralShareAction(AndroidReferralSharer(context), model.analytics) },
                onSignedOut = { onSelectTab(DonorTab.HOME); model.onSignedOut() },
                wallet = model.wallet?.let { viewModel<WalletPassModel>(key = "wallet-${model.generation}", factory = WalletPassModel.factory(it)) },

                inviterName = (model.state as? AccountState.Ready)?.profile?.publicName,
            )
        }
        DonorTab.EVENTS -> if (eventsService != null) holder.SaveableStateProvider("events") {
            EventsScreen(
                viewModel(key = "events-${model.generation}", factory = EventsModel.factory(eventsService)),
                onSignedOut = { onSelectTab(DonorTab.HOME); model.onSignedOut() },
            )
        }
        DonorTab.GIVING -> if (giving != null && kit != null) holder.SaveableStateProvider("gift") {
            GiftScreen(giving, stubComplete = kit.stubComplete, onSignedOut = { onSelectTab(DonorTab.HOME); model.onSignedOut() }, onOpenHistory = if (dashboard != null) ({ onPush(Pushed.History) }) else null)
        }
    }
}

/** The DAD brand mark as a small rounded tile (brand fill, ink "D"). A shape + letter, not colour alone;
 *  carries a description so TalkBack names the mark. No icon dependency is added. */
@Composable
private fun DadBrandTile(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 32.dp)
            .background(DadTheme.color("brand"), RoundedCornerShape(DadTheme.radius("field")))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = "DAD" },
        contentAlignment = Alignment.Center,
    ) { Text("D", style = MaterialTheme.typography.titleMedium, color = DadTheme.color("ink")) }
}

/** The Home top app bar: the title and the account avatar (opens Settings) in the actions slot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(model: AccountModel, onSettings: (() -> Unit)?) {
    val publicName = (model.state as? AccountState.Ready)?.profile?.publicName?.trim()?.takeIf { it.isNotEmpty() }
    TopAppBar(
        // The brand tile is the leading element (the old "DAD" wordmark lived in the body header,
        // removed in this phase); it carries a description so the mark is not colour alone.
        navigationIcon = { DadBrandTile(Modifier.padding(start = 16.dp)) },
        title = { Text(if (publicName != null) "Hi, $publicName" else "Welcome back", modifier = Modifier.dadHeading()) },
        actions = {
            if (onSettings != null) {
                val initial = ((model.state as? AccountState.Ready)?.profile?.publicName?.trim()?.firstOrNull()
                    ?: 'A').uppercaseChar().toString()
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Account and settings" }.testTag("open-settings"),
                ) { Text(initial, style = MaterialTheme.typography.titleMedium) }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/** The signed-in donor landing. Carries the exact copy the app has shown since the preview
 * shell, so the role-aware root is a structural change, not a visible one. */
@Composable
private fun DonorLanding(model: AccountModel, home: @Composable () -> Unit = {}) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        // The old "DAD / Good things start together. / Welcome, … / Your account has a home here."
        // header is gone: the greeting now lives in the Home TopAppBar ("Hi, {publicName}"). Only the
        // account sub-states (loading / unavailable / signed-out) keep an on-screen answer here.
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (model.state) {
                AccountState.Loading -> { CircularProgressIndicator(); Text("Loading your account…") }
                is AccountState.Ready -> Unit // the Ready greeting moved to the TopAppBar title
                AccountState.Unavailable -> {
                    Text("Your account is unavailable right now.", style = MaterialTheme.typography.titleLarge)
                    Text("Please try again in a moment.")
                    DadPrimaryButton("Try again") { model.load() }
                }
                AccountState.SignedOut -> Text("You’re signed out.")
            }
        }
        // Settings (sign-out and the store-mandated in-app account deletion) is reachable from the
        // account avatar in the Home top app bar, so the donor can open it from any account sub-state.
        Spacer(Modifier.height(20.dp))
        home()
        Spacer(Modifier.height(28.dp))
        if (BuildConfig.DAD_API_ORIGIN.isBlank()) Text("Preview · Sample account", style = MaterialTheme.typography.labelLarge)
    }
}

// Account & settings (PRD §6.11/§6.12): profile header, notification + privacy toggles, volunteer
// opt-in, sign out, and the store-mandated delete-account flow with a typed-word confirm gate.
// Bound to SettingsModel; all inline (no OS dialogs), per the UITest-determinism lesson.
@Composable
private fun SettingsHome(
    model: SettingsModel, onBack: (() -> Unit)? = null, onSignedOut: (() -> Unit)? = null, password: PasswordChangeModel? = null,
    onCancelGift: (() -> Unit)? = null, management: AccountManagementService? = null, generation: Int = 0,
    analytics: AnalyticsUploader? = null,
) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut?.invoke() }
    LaunchedEffect(password?.signedOut) { if (password?.signedOut == true) onSignedOut?.invoke() }
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
                    onConfirmingChange = { confirmingDelete = it }, onTypedChange = { deleteTyped = it }, password = password,
                    onCancelGift = onCancelGift, management = management, generation = generation, onSignedOut = onSignedOut,
                    analytics = analytics)
            }
        }
        model.actionError?.let { Text(it, modifier = Modifier.testTag("settings-action-error")) }
    }
}

@Composable
private fun SettingsContent(
    model: SettingsModel, snapshot: SettingsSnapshot, confirmingDelete: Boolean, deleteTyped: String,
    onConfirmingChange: (Boolean) -> Unit, onTypedChange: (String) -> Unit, password: PasswordChangeModel? = null,
    onCancelGift: (() -> Unit)? = null, management: AccountManagementService? = null, generation: Int = 0, onSignedOut: (() -> Unit)? = null,
    analytics: AnalyticsUploader? = null,
) {
    // Sections group the account the way a donor thinks of it; Sign out and Delete sit apart at the bottom so a
    // routine toggle is never next to a destructive action.
    SettingsHeading("Profile")
    // Role is immutable (D-1), ZIP drives streak day rollover. A plain Row, not a ListItem: ListItem always merges
    // its descendants, which would fold the tagged email text into the row.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DadSymbol("person", "", Modifier.clearAndSetSemantics {})
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(snapshot.profile.email, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("settings-email"))
            if (management == null) Text("ZIP: ${snapshot.profile.zipLabel}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (management != null) {
        EmailChangeSection(viewModel(key = "email-change-$generation", factory = EmailChangeModel.factory(snapshot.profile.email, management)))
        ProfileDetailsSection(viewModel(key = "details-$generation", factory = ProfileDetailsModel.factory(snapshot.profile, management)))
    }
    SettingsHeading("Notifications")
    // Payment-failure email is absent by design (non-toggleable server-side).
    SettingsToggleSection("Emails", snapshot.preferences.emailToggles, model)
    SettingsToggleSection("Notifications", snapshot.preferences.pushToggles, model)
    SettingsHeading("Privacy")
    Column {
        ToggleRow("Show my ranking", snapshot.preferences.showRanking, "ranking-toggle", "star") {
            model.toggle("rankings_opted_out", !it) // inverted: showing = not opted out
        }
        ToggleRow("Network visible", snapshot.preferences.networkVisible, "network-toggle", "person") {
            model.toggle("network_visible", it)
        }
        ToggleRow("Volunteer opt-in", snapshot.preferences.volunteer, "volunteer-toggle", "calendar") {
            model.toggle("volunteer", it)
        }
        if (analytics != null) {
            ToggleRow("Usage analytics", analytics.choice == true, "analytics-toggle", "shield") { analytics.choose(it) }
        }
    }
    if (analytics != null) {
        Text("First-party only: a temporary journey ID measures signup, sharing and giving. No names, email addresses or " +
            "payment details are sent.", style = MaterialTheme.typography.bodySmall)
    }
    if (password != null || management != null) SettingsHeading("Security")
    password?.let { PasswordSection(it) }
    if (management != null) {
        SessionsSection(viewModel(key = "sessions-$generation", factory = DeviceSessionsModel.factory(management))) { onSignedOut?.invoke() }
        SettingsHeading("Account")
        EmployerSection(viewModel(key = "employer-$generation", factory = EmployerModel.factory(management)))
    }
    HorizontalDivider()
    DadSecondaryButton("Sign out", modifier = Modifier.testTag("settings-signout")) { model.signOut() }
    // Delete account (store-mandated, §6.12): a typed-word confirm gate, inline.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Delete account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (!confirmingDelete) {
            DadDestructiveTextButton("Delete my account", modifier = Modifier.testTag("delete-start")) { onConfirmingChange(true) }
        } else {
            Text(DeleteAccountGate(deleteTyped).helpText, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = deleteTyped, onValueChange = onTypedChange,
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("delete-input"),
            )
            DadDestructiveTextButton(
                "Permanently delete",
                modifier = Modifier.testTag("delete-confirm"),
                enabled = DeleteAccountGate(deleteTyped).isConfirmed,
            ) { model.deleteAccount(DeleteAccountGate(deleteTyped)) }
            // The server refuses deletion while a monthly gift is set to renew (or a checkout is open): rather than
            // a dead end, offer the real cancel-gift flow, then the donor comes back here and deletes.
            if (model.deletionBlocked && onCancelGift != null) {
                DadSecondaryButton("Cancel my monthly gift", modifier = Modifier.testTag("delete-cancel-gift")) { onCancelGift() }
            }
            // The safe default is the one filled primary on this screen.
            DadPrimaryButton("Keep my account") { onConfirmingChange(false); onTypedChange("") }
        }
    }
}

@Composable
private fun SettingsToggleSection(title: String, toggles: List<Preferences.Toggle>, model: SettingsModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        toggles.forEach { t -> ToggleRow(t.label, t.value, "pref-${t.key}", "bell") { model.toggle(t.key, it) } }
    }
}

@Composable
private fun SettingsHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.dadHeading())
}

@Composable
private fun ToggleRow(label: String, value: Boolean, tag: String, icon: String, onChange: (Boolean) -> Unit) {
    // Tag and toggle state share one node (the ListItem); the Switch itself is display-only.
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { DadSymbol(icon, "", Modifier.clearAndSetSemantics {}) },
        trailingContent = { Switch(checked = value, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange),
    )
}
