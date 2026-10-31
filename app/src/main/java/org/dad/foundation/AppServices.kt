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
    val password: PasswordSignInService,
    val external: ExternalSignInDeps,
)

/** What the composition root hands the UI: the session plus the real (or, in DEBUG only, preview) services on it. */
class AppServices(
    val session: AccountSession,
    val signIn: SignInService,
    val settings: SettingsService?,
    val signup: SignupService = UnconfiguredCredentialAuth,
    val passwordSignIn: PasswordSignInService = UnconfiguredCredentialAuth,
    val passwordSettings: PasswordSettingsService? = null,
    val external: ExternalSignInDeps = ExternalSignInDeps(),
    val dashboard: DashboardService? = null,
    val giving: GivingKit? = null,
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
)

/** What the DEBUG stub backend answers for the donor-home reads. */
enum class StubDashboard { POPULATED, EMPTY, FAIL_FIRST, UNRANKED, SLOW }

/** The monthly gift the DEBUG stub backend starts with. */
enum class StubGift { NONE, ACTIVE, ACTIVE_FAIL_FIRST }
