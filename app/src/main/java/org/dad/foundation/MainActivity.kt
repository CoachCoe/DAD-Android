package org.dad.foundation

import android.os.Bundle
import android.content.Intent
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import org.dad.foundation.generated.DADTokens

sealed interface AccountState {
    data object Loading : AccountState
    data class Ready(val profile: AccountProfile) : AccountState
    data object Unavailable : AccountState
    data object SignedOut : AccountState
}

private class PreviewStore : CredentialStore {
    private var value: Credentials? = Credentials("preview", "preview-refresh", Long.MAX_VALUE)
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

class PreviewAccountModel(private val factory: (CoroutineScope, Boolean) -> AccountSession) : ViewModel() {
    var state: AccountState by mutableStateOf(AccountState.Loading)
        private set
    private var session: AccountSession? = null
    /**
     * Whether a session object has been (or will be) created. In preview and the real app a
     * session always exists, so a transient Unavailable/SignedOut is a sub-state of the donor
     * shell (recoverable via "Try again"), NOT a reason to leave for the role-select splash.
     * Only a config failure (blank public origin with no API) leaves this false.
     */
    var hasSession: Boolean by mutableStateOf(true)
        private set
    private var loading = false
    private var pendingProof: String? = null
    private var completedProof: String? = null
    private var activeProof: String? = null
    private val gate = NavigationGate()
    fun load(unavailableFirst: Boolean = false, claim: String? = null) {
        if (loading) { if (claim != null) pendingProof = claim; return }
        loading = true
        state = AccountState.Loading
        viewModelScope.launch {
            var claimed = false
            try {
                val profile = withContext(Dispatchers.IO) {
                    val active = session ?: factory(CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO), unavailableFirst).also { session = it }
                    if (claim != null) { active.claim(claim); claimed = true }
                    active.profile()
                }
                state = AccountState.Ready(profile)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AccountFailure.SignedOut) { state = AccountState.SignedOut }
            catch (_: Exception) { state = AccountState.Unavailable }
            finally {
                loading = false
                if (claim != null) {
                    if (claimed) { completedProof = activeProof; gate.signedIn() }
                    activeProof = null
                }
                pendingProof?.let { pendingProof = null; load(claim = it) }
            }
        }
    }
    fun configurationUnavailable() { hasSession = false; state = AccountState.Unavailable }
    fun receive(value: String, router: LinkRouter): String? {
        return when (val intent = router.route(value)) {
            is LinkIntent.Account -> if (intent.destination == AccountDestination.PROFILE) {
                gate.receive(intent.destination, state is AccountState.Ready)
                load(); null
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
        val unavailable = BuildConfig.DEBUG && intent.getBooleanExtra("preview_unavailable", false)
        val appContext = applicationContext
        incomingLink = intent.dataString
        val factory = viewModelFactory {
            initializer {
                PreviewAccountModel { scope, failFirst ->
                    if (BuildConfig.DAD_API_ORIGIN.isBlank()) AccountSession(PreviewAPI(failFirst), PreviewStore(), scope)
                    else AccountSession(WireAPI(BuildConfig.DAD_API_ORIGIN), KeystoreCredentialStore(appContext), scope)
                }
            }
        }
        setContent {
            val model: PreviewAccountModel = viewModel(factory = factory)
            LaunchedEffect(Unit) { model.load(unavailable) }
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
            val primary = tokenColor("brand-ink")
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = primary, background = tokenColor("ink-deep"))
                else lightColorScheme(primary = primary, background = tokenColor("paper"))
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val router = androidx.compose.runtime.remember { RoleRouter() }
                    var root by androidx.compose.runtime.remember { mutableStateOf<RoleRouter.Root>(router.root) }
                    // A session-backed shell is the donor journey — the donor landing owns every
                    // account sub-state (loading / ready / unavailable / signed-out) and its own
                    // recovery, so stay on it. Only a genuinely session-less app goes to role-select.
                    LaunchedEffect(model.hasSession) {
                        if (model.hasSession) router.signedIn(AppRole.DONOR) else router.restoredSignedOut()
                        root = router.root
                    }
                    when (val current = root) {
                        is RoleRouter.Root.Restoring, is RoleRouter.Root.Authenticated ->
                            if (current is RoleRouter.Root.Authenticated && current.role == AppRole.PARTNER) {
                                PartnerLanding()
                            } else {
                                DonorLanding(model)
                            }
                        is RoleRouter.Root.Unauthenticated -> RoleSelect { role ->
                            router.selectRole(role); router.signedIn(role); root = router.root
                        }
                    }
                }
            }
        }
    }
}

/** The signed-in donor landing. Carries the exact copy the app has shown since the preview
 * shell, so the role-aware root is a structural change, not a visible one. */
@Composable
private fun DonorLanding(model: PreviewAccountModel) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("DAD", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("Good things start together.", style = MaterialTheme.typography.headlineMedium)
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
                AccountState.SignedOut -> Text("You’re signed out. Use your DAD sign-in link to continue.")
            }
        }
        Spacer(Modifier.height(28.dp))
        if (BuildConfig.DAD_API_ORIGIN.isBlank()) Text("Preview · Sample account", style = MaterialTheme.typography.labelLarge)
    }
}

/** Minimal partner stack placeholder — fleshed out in Phase 8. Present now so the role-aware
 * root compiles and routes both journeys. */
@Composable
private fun PartnerLanding() {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Partner portal", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("Your organization’s giving home.", style = MaterialTheme.typography.headlineMedium)
    }
}

/** Unauthenticated splash — pick a journey before signing in. The preview store starts
 * signed-in, so this path is exercised only when there is genuinely no session. */
@Composable
private fun RoleSelect(onSelect: (AppRole) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("DAD", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("Good things start together.", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(28.dp))
        DadPrimaryButton("I’m a donor") { onSelect(AppRole.DONOR) }
        Spacer(Modifier.height(12.dp))
        DadPrimaryButton("I’m a partner") { onSelect(AppRole.PARTNER) }
    }
}
