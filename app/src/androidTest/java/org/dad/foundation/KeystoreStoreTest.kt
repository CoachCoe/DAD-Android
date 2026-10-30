package org.dad.foundation

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreStoreTest {
    @Test fun credentialsRoundTripEncryptedOutsideBackupAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreCredentialStore(context)
        try {
            val value = Credentials("synthetic-access-secret", "synthetic-refresh-secret", 1234)
            store.save(value)
            assertEquals(value, store.load())
            val bytes = File(context.noBackupFilesDir, "donor-session.enc").readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("synthetic"))
            store.clear()
            assertNull(store.load())
        } finally { store.clear() }
    }

    @Test fun corruptedSessionFileLoadsAsSignedOutAndIsRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreCredentialStore(context)
        val file = File(context.noBackupFilesDir, "donor-session.enc")
        try {
            store.save(Credentials("synthetic-access-secret", "synthetic-refresh-secret", 1234))
            file.writeBytes(ByteArray(64) { it.toByte() })
            assertNull(store.load())
            assertFalse(file.exists())
        } finally { store.clear() }
    }
}
