package org.dad.foundation

/**
 * Optional sign-in methods that need owner-supplied configuration and a platform provider. Both
 * providers are null until a Credential Manager implementation is linked, so [capabilities] is
 * [SignInCapabilities.NONE] and the sign-in screen says "Not available yet".
 */
class ExternalSignInDeps(
    val capabilities: SignInCapabilities = SignInCapabilities.NONE,
    val oidc: OidcSignInService = UnconfiguredExternalSignIn,
    val passkey: PasskeySignInService = UnconfiguredExternalSignIn,
    val googleTokens: GoogleIdTokenProvider? = null,
    val authenticator: PasskeyAuthenticator? = null,
)

/** The unauthenticated flows the sign-in stack drives. */
class AuthServices(
    val phone: SignInService,
    val signup: SignupService,
    val link: SignInLinkService,
    val password: PasswordSignInService,
    val external: ExternalSignInDeps,
)

/** The partner journey: its own 12-hour session, the emailed-link sign-in and the portal. */
class PartnerKit(val session: PartnerSession, val signIn: PartnerSignInService, val service: PartnerService)

/** What the composition root hands the UI: the session plus the real (or, in DEBUG only, preview) services on it. */
class AppServices(
    val session: AccountSession,
    val signIn: SignInService,
    val settings: SettingsService?,
    val signup: SignupService = UnconfiguredCredentialAuth,
    val signInLink: SignInLinkService = UnconfiguredCredentialAuth,
    val passwordSignIn: PasswordSignInService = UnconfiguredCredentialAuth,
    val passwordSettings: PasswordSettingsService? = null,
    val external: ExternalSignInDeps = ExternalSignInDeps(),
    val dashboard: DashboardService? = null,
    val giving: GivingKit? = null,
    val events: EventsService? = null,
    val engagement: EngagementService? = null,
    val management: AccountManagementService? = null,
    val wallet: WalletPassService? = null,
    val partner: PartnerKit? = null,
    /** Opt-in usage analytics; null where nothing is ever sent (the synthetic preview). */
    val analytics: AnalyticsUploader? = null,
    val push: PushRegistration? = null,
    /** Null until a push provider is linked; then the donor and partner register at every launch. */
    val pushTokens: PushTokenProvider? = null,
)

/** DEBUG launch switches. Always all-false in a release build. */
data class LaunchOptions(
    val unavailableFirst: Boolean = false,
    val signedOut: Boolean = false,
    val stubBackend: Boolean = false,
    val stubDeleteBlocked: Boolean = false,
    /** Stub backend only: start with a stored session so the signed-in donor home renders at once. */
    val stubSignedIn: Boolean = false,
    /** Stub backend only: what the scripted donor-home reads answer. */
    val stubDashboard: StubDashboard = StubDashboard.POPULATED,
    /** Stub backend only: the donor's monthly gift at launch. */
    val stubGift: StubGift = StubGift.NONE,
    /** Stub backend only (screenshot and UI-test aid): open the gift screen straight from the donor home. */
    val stubOpenGift: Boolean = false,
    /** Stub backend only: what the scripted events routes answer. */
    val stubEvents: StubEventsScenario = StubEventsScenario.STANDARD,
    /** Stub backend only: what the scripted engagement routes answer. */
    val stubEngagement: StubEngagementScenario = StubEngagementScenario.STANDARD,
    /** Stub backend only (screenshot and UI-test aid): open the engagement screen straight from the donor home. */
    val stubOpenEngagement: Boolean = false,
    /** Stub backend only: start with a stored partner session so the portal opens at once. */
    val stubPartnerSignedIn: Boolean = false,
    /** Stub backend only: the analytics question has not been answered yet (otherwise it starts declined). */
    val stubAnalyticsUnset: Boolean = false,
    /** Stub backend only: a push provider is linked, so the donor registers a token. */
    val stubPush: Boolean = false,
)

/** What the DEBUG stub backend answers for the donor-home reads. */
enum class StubDashboard { POPULATED, EMPTY, FAIL_FIRST, UNRANKED, SLOW }

/** The monthly gift the DEBUG stub backend starts with. */
enum class StubGift { NONE, ACTIVE, ACTIVE_FAIL_FIRST }

/** What the DEBUG stub backend does for the events routes. */
enum class StubEventsScenario { STANDARD, INVITED, FAIL_FIRST, NOT_ELIGIBLE }

/** What the DEBUG stub backend does for the engagement routes. */
enum class StubEngagementScenario { STANDARD, NO_PUBLIC_NAME, TOGGLE_FAIL, BADGES_DOWN, NETWORK_DOWN, SPEND_UNPUBLISHED }
