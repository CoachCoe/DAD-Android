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

    suspend fun claim(token: String) {
        val before = mutex.withLock { epoch }
        val result = api.claim(token)
        mutex.withLock {
            if (epoch != before) throw AccountFailure.SignedOut
            store.save(result)
            credentials = result
            epoch++
            flight = null
        }
    }

    /**
     * A valid bearer access token for a product API call, refreshed via the same single-flight
     * path [profile] uses. Throws [AccountFailure.SignedOut] when there is no usable session.
     */
    suspend fun accessToken(): String {
        currentCoroutineContext().ensureActive()
        return usableCredentials().access
    }

    suspend fun profile(): AccountProfile {        currentCoroutineContext().ensureActive()
        val before = mutex.withLock { epoch }
        val current = usableCredentials()
        try { return checkedProfile(current, before) }
        catch (_: AccountFailure.SignedOut) {
            mutex.withLock { if (epoch != before) throw AccountFailure.SignedOut }
            val next = usableCredentials(current.access)
            try { return checkedProfile(next, before) }
            catch (_: AccountFailure.SignedOut) {
                mutex.withLock { if (epoch == before) clearLocal() }
                throw AccountFailure.SignedOut
            }
        }
    }

    private suspend fun checkedProfile(value: Credentials, before: Long): AccountProfile {
        val result = api.profile(value.access)
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
