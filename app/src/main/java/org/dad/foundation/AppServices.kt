package org.dad.foundation

/** What the composition root hands the UI: the session plus the real (or, in DEBUG only, preview) services on it. */
class AppServices(val session: AccountSession, val signIn: SignInService, val settings: SettingsService?)

/** DEBUG launch switches. Always all-false in a release build. */
data class LaunchOptions(
    val unavailableFirst: Boolean = false,
    val signedOut: Boolean = false,
    val stubBackend: Boolean = false,
    val stubDeleteBlocked: Boolean = false,
)
