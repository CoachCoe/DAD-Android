package org.dad.foundation

import org.dad.foundation.generated.api.AccountApi
import org.dad.foundation.generated.model.RegisterPushDeviceRequest

/**
 * This installation's push token (an FCM registration token). No provider is linked yet, which is a dependency
 * decision (Firebase Messaging plus the project's `google-services` configuration), so the app holds none and
 * registers nothing; everything after the token (registration, taps, routing) is wired and tested.
 */
fun interface PushTokenProvider {
    suspend fun token(): String?
}

/** Donor push over the donor session (`registerPushDevice` / `unregisterPushDevice`). The server drops the token
 *  itself when the session ends, so sign-out needs no unregister. */
class WireDonorPush(private val product: ProductClient) : PushRegistration {
    override suspend fun register(token: String) {
        product.perform {
            it.create(AccountApi::class.java).registerPushDevice(
                RegisterPushDeviceRequest(RegisterPushDeviceRequest.Platform.ANDROID, token, environment = null),
            ).requireBody()
        }
    }

    override suspend fun unregister() {
        product.perform { it.create(AccountApi::class.java).unregisterPushDevice().requireBody() }
    }
}

/**
 * A tapped notification, from the data the server sends with it: a `category` (`REFERRAL_CONVERTED`,
 * `STREAK_AT_RISK`, ...) and an app `path` to open. Notifications never carry names or amounts.
 */
data class PushTap(val category: String, val path: String?) {
    companion object {
        private val CATEGORY = Regex("^[A-Z][A-Z_]{0,63}$")

        /** The tap in a launch intent's extras, or null when the intent did not come from a notification. */
        fun from(category: String?, path: String?): PushTap? =
            category?.takeIf(CATEGORY::matches)?.let { PushTap(it, path?.takeIf { p -> p.length <= 256 }) }
    }
}
