package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleRouterTest {
    @Test fun startsRestoringThenResolvesToUnauthenticated() {
        val router = RoleRouter()
        assertTrue(router.root is RoleRouter.Root.Restoring)
        router.restoredSignedOut()
        assertEquals(RoleRouter.Root.Unauthenticated(), router.root)
    }

    @Test fun selectRoleThenSignInAuthenticatesThatRole() {
        val router = RoleRouter()
        router.selectRole(AppRole.PARTNER)
        assertEquals(RoleRouter.Root.Unauthenticated(AppRole.PARTNER), router.root)
        router.signedIn(AppRole.PARTNER)
        assertEquals(RoleRouter.Root.Authenticated(AppRole.PARTNER), router.root)
        assertEquals(AppRole.PARTNER, router.currentRole)
    }

    @Test fun restoreDoesNotClobberAnActiveSession() {
        val router = RoleRouter()
        router.signedIn(AppRole.DONOR)
        router.restoredSignedOut()
        assertEquals(RoleRouter.Root.Authenticated(AppRole.DONOR), router.root)
    }

    @Test fun selectRoleIgnoredWhileAuthenticated() {
        val router = RoleRouter()
        router.signedIn(AppRole.DONOR)
        router.selectRole(AppRole.PARTNER)
        assertEquals(RoleRouter.Root.Authenticated(AppRole.DONOR), router.root)
    }

    @Test fun deepLinkDefersUntilSignedInThenFlushes() {
        val router = RoleRouter()
        router.restoredSignedOut()
        assertNull(router.receive(AccountDestination.PROFILE))
        assertEquals(AccountDestination.PROFILE, router.pendingDestination)
        router.signedIn(AppRole.DONOR)
        assertEquals(AccountDestination.PROFILE, router.flushPendingDestination())
        assertNull(router.pendingDestination)
    }

    @Test fun deepLinkPassesThroughWhenAlreadySignedIn() {
        val router = RoleRouter()
        router.signedIn(AppRole.DONOR)
        assertEquals(AccountDestination.GIFTS, router.receive(AccountDestination.GIFTS))
        assertNull(router.pendingDestination)
    }

    @Test fun signOutClearsRoleAndPendingDestination() {
        val router = RoleRouter()
        router.restoredSignedOut()
        router.receive(AccountDestination.DASHBOARD)
        router.signedIn(AppRole.DONOR)
        router.signedOut()
        assertEquals(RoleRouter.Root.Unauthenticated(), router.root)
        assertNull(router.currentRole)
        assertNull(router.pendingDestination)
    }
}
