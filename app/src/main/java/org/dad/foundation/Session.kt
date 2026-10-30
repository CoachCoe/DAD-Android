package org.dad.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class AccountFailure : java.io.IOException() {
    data object SignedOut : AccountFailure()
    data object Unavailable : AccountFailure()
    data object InvalidResponse : AccountFailure()
    /** 429: the server is throttling this caller; retry later. */
    data object RateLimited : AccountFailure()
    /** 422: the server rejected a value as malformed (e.g. a phone number). */
    data object InvalidInput : AccountFailure()
    /** 409 on account deletion: a monthly gift is still set to renew or a checkout is open. */
    data object DeletionBlocked : AccountFailure()
    /** The build has no usable API origin; nothing is ever served from a synthetic fallback. */
    data object InvalidConfiguration : AccountFailure()
}

data class Credentials(val access: String, val refresh: String, val expiresAtMillis: Long) {
    override fun toString() = "Credentials([redacted])"
}
data class AccountProfile(val email: String, val publicName: String?)
interface AccountAPI {
    suspend fun profile(access: String): AccountProfile
    suspend fun refresh(token: String): Credentials
    suspend fun claim(token: String): Credentials
    suspend fun logout(access: String)
}
interface CredentialStore {
    fun load(): Credentials?
    fun save(value: Credentials)
    fun clear()
}

class AccountSession(private val api: AccountAPI, private val store: CredentialStore, scope: CoroutineScope) {
    private val refreshScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutex = Mutex()
    private var credentials = store.load()
    private var epoch = 0L
    private var sequence = 0L
    private data class Flight(val id: Long, val result: Deferred<Credentials>)
    private var flight: Flight? = null

    suspend fun claim(token: String) { establish { api.claim(token) } }

    /**
     * Install credentials produced by [produce]. The session owns the await: the epoch captured
     * before it guards the install, so a sign-in that resolves after a sign-out (or after another
     * sign-in) is discarded with [AccountFailure.SignedOut] instead of clobbering state.
     */
    suspend fun establish(produce: suspend () -> Credentials): Credentials {
        val before = mutex.withLock { epoch }
        val result = produce()
        mutex.withLock {
            if (epoch != before) throw AccountFailure.SignedOut
            store.save(result)
            credentials = result
            epoch++
            flight = null
        }
        return result
    }

    /** Whether stored credentials existed when the session was created (decides restore vs sign-in). */
    val restoredCredentials: Boolean = credentials != null

    /** True while this session holds credentials (the server may still reject them later). */
    suspend fun isSignedIn(): Boolean = mutex.withLock { credentials != null }

    /**
     * Drop local credentials without contacting the server (after a confirmed server-side account
     * deletion, where the remote session no longer exists). Bumps the epoch so any in-flight call
     * or sign-in started before it is discarded.
     */
    suspend fun discardLocal() { mutex.withLock { clearLocal() } }

    /**
     * A valid bearer access token for a product API call, refreshed via the same single-flight
     * path [profile] uses. Throws [AccountFailure.SignedOut] when there is no usable session.
     */
    suspend fun accessToken(): String {
        currentCoroutineContext().ensureActive()
        return usableCredentials().access
    }

    suspend fun profile(): AccountProfile = authorized { api.profile(it) }

    /**
     * Run one authenticated call with a currently-valid access token: refresh through the single
     * flight if expired; on a 401 (the call throws [AccountFailure.SignedOut]) force exactly one
     * refresh and retry once; a second 401 ends the session locally. A sign-out or sign-in while the
     * call is in flight (epoch change) or caller cancellation discards the result. Only for requests
     * the server has not processed when it answers 401 (it rejects before handling).
     */
    suspend fun <T> authorized(call: suspend (String) -> T): T {
        currentCoroutineContext().ensureActive()
        val before = mutex.withLock { epoch }
        val current = usableCredentials()
        try { return checked(call, current, before) }
        catch (_: AccountFailure.SignedOut) {
            mutex.withLock { if (epoch != before) throw AccountFailure.SignedOut }
            val next = usableCredentials(current.access)
            try { return checked(call, next, before) }
            catch (_: AccountFailure.SignedOut) {
                mutex.withLock { if (epoch == before) clearLocal() }
                throw AccountFailure.SignedOut
            }
        }
    }

    private suspend fun <T> checked(call: suspend (String) -> T, value: Credentials, before: Long): T {
        val result = call(value.access)
        mutex.withLock { if (before != epoch) throw AccountFailure.SignedOut }
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun usableCredentials(forceFor: String? = null): Credentials {
        var cached: Credentials? = null
        var before = 0L
        val pending = mutex.withLock {
            val current = credentials ?: throw AccountFailure.SignedOut
            before = epoch
            if (flight == null && current.expiresAtMillis > System.currentTimeMillis() + 30_000 && forceFor != current.access) {
                cached = current
                null
            } else flight ?: run {
                val id = ++sequence
                val startedAt = epoch
                val task = refreshScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val refreshed = api.refresh(current.refresh)
                        mutex.withLock {
                            if (epoch == startedAt) {
                                try { store.save(refreshed) }
                                catch (failure: Exception) { clearLocal(); throw failure }
                                credentials = refreshed
                            }
                        }
                        refreshed
                    } catch (failure: AccountFailure.SignedOut) {
                        mutex.withLock { if (epoch == startedAt) clearLocal() }
                        throw failure
                    } finally {
                        mutex.withLock { if (flight?.id == id) flight = null }
                    }
                }
                Flight(id, task).also { flight = it }
            }
        }
        cached?.let { return it }
        checkNotNull(pending).result.await()
        return mutex.withLock {
            if (epoch != before) throw AccountFailure.SignedOut
            credentials ?: throw AccountFailure.SignedOut
        }
    }

    private fun clearLocal() {
        epoch++
        credentials = null
        flight = null
        store.clear()
    }

    suspend fun logout() {
        val (old, pending) = mutex.withLock {
            val result = credentials to flight?.result
            clearLocal()
            result
        }
        var access = old?.access
        if (pending != null) {
            try { access = pending.await().access }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); if (access == null) throw failure }
        }
        access?.let { api.logout(it) }
    }
}
