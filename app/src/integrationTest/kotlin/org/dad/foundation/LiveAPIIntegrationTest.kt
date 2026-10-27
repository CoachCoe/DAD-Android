package org.dad.foundation

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

private class NativeTestStore(private var value: Credentials?) : CredentialStore {
    @Synchronized override fun load() = value
    @Synchronized override fun save(value: Credentials) { this.value = value }
    @Synchronized override fun clear() { value = null }
}

class LiveAPIIntegrationTest {
    private suspend fun signedOut(operation: suspend () -> Unit) {
        val error = runCatching { operation() }.exceptionOrNull()
        assertTrue("Revoked credential must return signed out", error === AccountFailure.SignedOut)
    }
    @Test fun realHTTPSAccountLifecycle() = runTest {
        try {
            val path = requireNotNull(System.getProperty("dad.integration.fixture"))
            val fixture = JsonParser.parseString(File(path).readText()).asJsonObject
            val origin = fixture["origin"].asString
            require(java.net.URI(origin).host == "127.0.0.1")
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(fixture["certificate_pem"].asString.toByteArray()))
            val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("local-test", cert) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            val http = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).build()
            val api = WireAPI(origin, http)
            try {
                val proof = fixture["claim_token"].asString
                val original = api.claim(proof)
                assertEquals(fixture["email"].asString, api.profile(original.access).email)
                signedOut { api.claim(proof) }
                val store = NativeTestStore(original.copy(expiresAtMillis = 0))
                val session = AccountSession(api, store, backgroundScope)
                assertEquals(fixture["email"].asString, session.profile().email)
                val rotated = requireNotNull(store.load())
                assertTrue("Refresh must replace access", rotated.access != original.access)
                assertTrue("Refresh must rotate refresh", rotated.refresh != original.refresh)
                signedOut { api.profile(original.access) }
                api.logout(rotated.access)
                signedOut { session.profile() }
                assertNull(store.load())
                session.claim(fixture["logout_claim_token"].asString)
                val last = requireNotNull(store.load())
                session.logout()
                assertNull(store.load())
                signedOut { api.profile(last.access) }
                signedOut { api.refresh(last.refresh) }
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        } catch (error: Throwable) {
            if (error is AssertionError) throw error
            fail("Real API lifecycle failed: ${error.javaClass.simpleName}")
        }
    }
}
