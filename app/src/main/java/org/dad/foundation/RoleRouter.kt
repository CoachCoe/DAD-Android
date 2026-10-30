package org.dad.foundation

/**
 * Which product surface the signed-in person is using. Donor and partner are distinct
 * journeys (PRD §6 vs §7) with separate navigation graphs; the admin console is web-only
 * and intentionally absent here.
 */
enum class AppRole { DONOR, PARTNER }

/**
 * Top-level navigation state for the app shell. Drives which root graph is shown: an
 * unauthenticated stack (role select + auth), or the donor/partner stack once a session
 * exists. Plain state + explicit transitions so it is unit-testable without Compose.
 *
 * A transient account sub-state (loading / unavailable / signed-out) of a session that
 * EXISTS stays on the authenticated landing — it is not a reason to return to role select.
 * Only a genuinely session-less app goes to [Root.Unauthenticated]. (Lesson carried over
 * from the iOS Phase A role-routing fix.)
 */
class RoleRouter(root: Root = Root.Restoring) {
    sealed interface Root {
        /** Session state is being restored on launch. */
        data object Restoring : Root
        /** No session yet; the person has not chosen (or we have not restored) a role. */
        data class Unauthenticated(val selectedRole: AppRole? = null) : Root
        /** A session exists for this role; show its graph. */
        data class Authenticated(val role: AppRole) : Root
    }

    var root: Root = root
        private set

    private val gate = NavigationGate()

    /** The person picked a role on the unauthenticated splash before signing in. */
    fun selectRole(role: AppRole) {
        if (root is Root.Authenticated) return
        root = Root.Unauthenticated(role)
    }

    /** A session was established for [role] (sign-in, claim link, or restored on launch). */
    fun signedIn(role: AppRole) {
        root = Root.Authenticated(role)
    }

    /** The session ended — drop any deferred destination and return to the splash. */
    fun signedOut() {
        gate.signedOut()
        root = Root.Unauthenticated()
    }

    /** Restore finished with no usable session. */
    fun restoredSignedOut() {
        if (root is Root.Authenticated) return
        root = Root.Unauthenticated()
    }

    /** Receive a deep-link account destination, deferring it until signed in. */
    fun receive(destination: AccountDestination): AccountDestination? =
        gate.receive(destination, root is Root.Authenticated)

    /** Flush a deferred destination once signed in (call right after [signedIn]). */
    fun flushPendingDestination(): AccountDestination? = gate.signedIn()

    val currentRole: AppRole?
        get() = (root as? Root.Authenticated)?.role

    val pendingDestination: AccountDestination?
        get() = gate.pending
}
