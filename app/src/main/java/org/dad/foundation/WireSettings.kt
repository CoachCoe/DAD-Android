package org.dad.foundation

import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.api.DonorApi
import org.dad.foundation.generated.model.Profile
import org.dad.foundation.generated.model.SavePreferencesRequest

/**
 * Real account & settings service (PRD §6.11, §6.12) over the generated client: `profile`,
 * `savePreferences`, `deleteAccount`, `logout`, all authorized through the session.
 *
 * Account deletion (Play account-deletion policy) is `POST /donor/me/delete` -> on a 200, wipe local
 * state (Keystore credentials via [AccountSession.discardLocal], plus [onLocalWipe]) -> the model
 * then leaves the shell. A 409 (monthly gift still renewing or a checkout open) is
 * [AccountFailure.DeletionBlocked] and leaves the session untouched.
 */
class WireSettings(private val product: ProductClient, private val onLocalWipe: () -> Unit = {}) : SettingsService {
    override suspend fun load(): SettingsSnapshot =
        snapshot(product.perform { it.create(DonorApi::class.java).profile().requireBody() })

    override suspend fun savePreferences(preferences: Preferences): SettingsSnapshot {
        val body = preferencesBody(preferences)
        // A stale `version` (409) means the caller must re-read; surfaced as unavailable so the model shows retry.
        return snapshot(product.perform { it.create(DonorApi::class.java).savePreferences(body).requireBody() })
    }

    /**
     * Saves the whole block like [savePreferences], but a stale `version` (409) stays [AccountFailure.Conflict] and
     * every other status keeps its own failure ([failureForStatus]), so a caller can re-read and retry once.
     */
    internal suspend fun savePreferencesKeepingConflict(preferences: Preferences) {
        val body = preferencesBody(preferences)
        product.perform { it.create(DonorApi::class.java).savePreferences(body).successBody() }
    }

    private fun preferencesBody(preferences: Preferences) = SavePreferencesRequest(
        version = preferences.version, referralEmails = preferences.referralEmails, marketingEmails = preferences.marketingEmails,
        pushReferralConverted = preferences.pushReferralConverted, pushStreakAtRisk = preferences.pushStreakAtRisk,
        pushStreakMilestone = preferences.pushStreakMilestone, pushStreakLapsed = preferences.pushStreakLapsed,
        pushBadgeEarned = preferences.pushBadgeEarned, pushEventNearby = preferences.pushEventNearby,
        pushEventReminder = preferences.pushEventReminder, pushEventPromoted = preferences.pushEventPromoted,
        rankingsOptedOut = preferences.rankingsOptedOut, networkVisible = preferences.networkVisible,
        volunteer = preferences.volunteer, pushGiftProcessed = preferences.pushGiftProcessed)

    override suspend fun deleteAccount() {
        val deleted = product.perform {
            it.create(AccountApi::class.java).deleteAccount().requireBody(conflict = AccountFailure.DeletionBlocked).deleted
        }
        if (!deleted) throw AccountFailure.InvalidResponse
        // Confirmed deleted server-side: nothing remains to revoke remotely, so wipe locally without a
        // further network call. Wipe the local data even if the credential clear throws.
        try { product.session.discardLocal() } finally { onLocalWipe() }
    }

    /**
     * Local credentials are cleared first ([AccountSession.logout]), so a remote-logout failure
     * afterwards (offline) still leaves the donor signed out on this device; the server session then
     * simply expires. Only a failure to clear secure storage is reported.
     */
    override suspend fun signOut() {
        try { product.session.logout() }
        catch (failure: java.io.IOException) { if (product.session.isSignedIn()) throw failure }
    }

    private fun snapshot(p: Profile): SettingsSnapshot = SettingsSnapshot(
        profile = ProfileSummary(email = p.email.orInvalid(), accountRole = p.accountRole.orInvalid().value, zip = p.zip.orNullIfBlank(), city = p.city.orNullIfBlank(), phone = p.phone.orNullIfBlank()),
        preferences = Preferences(
            version = p.version, referralEmails = p.referralEmails, marketingEmails = p.marketingEmails,
            pushReferralConverted = p.pushReferralConverted, pushStreakAtRisk = p.pushStreakAtRisk,
            pushStreakMilestone = p.pushStreakMilestone, pushStreakLapsed = p.pushStreakLapsed,
            pushBadgeEarned = p.pushBadgeEarned, pushEventNearby = p.pushEventNearby,
            pushEventReminder = p.pushEventReminder, pushEventPromoted = p.pushEventPromoted,
            pushGiftProcessed = p.pushGiftProcessed, rankingsOptedOut = p.rankingsOptedOut,
            networkVisible = p.networkVisible, volunteer = p.volunteer))

    // Gson leaves absent fields null despite the generated non-null types; treat that as a bad response.
    private fun <T> T?.orInvalid(): T = this ?: throw AccountFailure.InvalidResponse

    private fun String?.orNullIfBlank(): String? = if (isNullOrBlank()) null else this
}
