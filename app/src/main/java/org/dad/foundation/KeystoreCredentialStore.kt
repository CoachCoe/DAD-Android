package org.dad.foundation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "donor-session.enc"))
    private val alias = context.packageName + ".donor-session"
    private val gson = Gson()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun load(): Credentials? {
        if (!file.baseFile.exists()) return null
        // A session that cannot be decrypted or parsed (key invalidated, file corrupted) is
        // unrecoverable; treating it as signed out lets the donor sign in again instead of
        // failing every launch.
        return try { decrypt(file.readFully()) }
        catch (_: GeneralSecurityException) { clear(); null }
        catch (_: IllegalArgumentException) { clear(); null }
        catch (_: JsonParseException) { clear(); null }
    }
    private fun decrypt(raw: ByteArray): Credentials {
        require(raw.size in 29..16384) { "Invalid encrypted session" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        cipher.updateAAD(alias.toByteArray())
        val value = gson.fromJson(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8), Credentials::class.java)
        require(value != null && !value.access.isNullOrBlank() && !value.refresh.isNullOrBlank()) { "Invalid stored session" }
        return value
    }
    @Synchronized override fun save(value: Credentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(alias.toByteArray())
        val raw = cipher.iv + cipher.doFinal(gson.toJson(value).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(raw); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
    @Synchronized override fun clear() {
        file.delete()
        check(!file.baseFile.exists()) { "Session cleanup failed" }
    }
}
