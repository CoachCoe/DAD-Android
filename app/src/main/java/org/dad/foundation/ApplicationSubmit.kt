package org.dad.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.dad.foundation.generated.model.ApplyWithReceiptRequest
import org.dad.foundation.generated.model.ApplyWithReceiptRequestApplication
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.security.SecureRandom
import java.util.UUID

// Native partner application (contract 2.11, `applyWithReceipt`). The app chooses the status receipt, keeps it in
// its own secure store before the request leaves, and later reads `applicationStatus` with it as the bearer. The
// server never echoes the receipt, so losing it before the request is sent would orphan the application — the
// receipt is therefore stored first, and a definitive refusal is the only thing that forgets it again.

/** The organization types the website offers. The server stores the value as free text; this is the picker's list. */
enum class PartnerOrganizationType(val label: String) {
    CORPORATE("Corporate"), NONPROFIT("Nonprofit"), FOUNDATION("Foundation"), GOVERNMENT("Government"), OTHER("Other")
}

data class PartnerApplication(
    val organization: String,
    val website: String,
    val type: PartnerOrganizationType,
    val contactName: String,
    val title: String,
    val workEmail: String,
    val phone: String,
    val workforceSize: Int,
)

/**
 * One submission frozen with its idempotency key and receipt, so a retry after an unconfirmed attempt repeats
 * exactly what the server may already have received.
 */
data class PartnerApplicationAttempt(
    val application: PartnerApplication,
    val idempotencyKey: String,
    val receipt: String,
) {
    companion object {
        fun make(application: PartnerApplication): PartnerApplicationAttempt =
            PartnerApplicationAttempt(application, UUID.randomUUID().toString(), makeApplicationReceipt())
    }
}

/** 32 secure-random bytes as 43 unpadded base64url characters, the shape the contract requires. */
fun makeApplicationReceipt(): String {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** How a submission failed, so the form shows the right copy and keeps or forgets the receipt accordingly. */
sealed class ApplicationSubmitFailure : java.io.IOException() {
    /** 422: a field is invalid, or this work email already has an application under review. Forgets the receipt. */
    data object Invalid : ApplicationSubmitFailure()
    /** 409: the key was reused with different content. Forgets the receipt. */
    data object Conflict : ApplicationSubmitFailure()
    /** 403: the request was refused as not coming from the app. Forgets the receipt. */
    data object Forbidden : ApplicationSubmitFailure()
    /** 429: wait [retryAfterSeconds] when the server said how long. Keeps the receipt. */
    data class RateLimited(val retryAfterSeconds: Int?) : ApplicationSubmitFailure()
    /** Any other server status or a lost connection: the server may have received it. Keeps the receipt. */
    data object Unconfirmed : ApplicationSubmitFailure()
}

interface PartnerApplicationService {
    /**
     * Keeps the attempt's receipt, then sends it. A definitive refusal (422/409/403) forgets the receipt again; a
     * throttle, an outage or a lost connection keeps it, because the server may have recorded the application.
     */
    suspend fun submit(attempt: PartnerApplicationAttempt)
}

private interface ApplicationWrites {
    @POST("donor/partner/applications")
    suspend fun apply(@Header("Idempotency-Key") key: UUID, @Body body: ApplyWithReceiptRequest): Response<JsonElement>
}

/**
 * Submits `applyWithReceipt` over the native transport (`X-DAD-Client: native`, no Origin or cookie). The receipt is
 * stored in its own [CredentialStore] — apart from the donor and partner sessions — before the request leaves, and
 * forgotten only on a definitive refusal; the status card then reads it back through [WireApplicationStatus].
 */
class WirePartnerApplication(
    origin: String,
    private val receipt: CredentialStore,
    private val baseClient: OkHttpClient = defaultNativeClient(),
    private val now: () -> Long = System::currentTimeMillis,
) : PartnerApplicationService {
    private val url: HttpUrl = validatedOrigin(origin)

    override suspend fun submit(attempt: PartnerApplicationAttempt) {
        // Keep the receipt before the request leaves: the server never echoes it, so a crash after send must still
        // be able to read the status. Its 30-day server lifetime is mirrored as the stored expiry.
        withContext(Dispatchers.IO) { receipt.save(Credentials(attempt.receipt, refresh = "", expiresAtMillis = now() + RECEIPT_LIFETIME_MILLIS)) }
        try {
            send(attempt)
        } catch (failure: ApplicationSubmitFailure) {
            when (failure) {
                is ApplicationSubmitFailure.Invalid, ApplicationSubmitFailure.Conflict, ApplicationSubmitFailure.Forbidden -> withContext(NonCancellable + Dispatchers.IO) { receipt.clear() }
                is ApplicationSubmitFailure.RateLimited, ApplicationSubmitFailure.Unconfirmed -> Unit
            }
            throw failure
        }
    }

    private suspend fun send(attempt: PartnerApplicationAttempt) {
        val response = try {
            val api = nativeRetrofit(url, baseClient).create(ApplicationWrites::class.java)
            decoding { api.apply(UUID.fromString(attempt.idempotencyKey), attempt.application.toRequest(attempt.receipt)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AccountFailure.InvalidResponse) {
            // A 2xx with an unreadable body is still a success for this fire-and-forget write (`{"received":true}`
            // carries nothing the app needs), so an invalid-body decode on a success is not a failure.
            return
        } catch (_: java.io.IOException) {
            // A lost or timed-out connection: the server may have received it.
            throw ApplicationSubmitFailure.Unconfirmed
        }
        if (response.isSuccessful) { response.body(); return }
        val retryAfter = response.headers()["Retry-After"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        response.errorBody()?.close()
        throw when (response.code()) {
            403 -> ApplicationSubmitFailure.Forbidden
            409 -> ApplicationSubmitFailure.Conflict
            422 -> ApplicationSubmitFailure.Invalid
            429 -> ApplicationSubmitFailure.RateLimited(retryAfter)
            else -> ApplicationSubmitFailure.Unconfirmed
        }
    }

    private fun PartnerApplication.toRequest(receipt: String) = ApplyWithReceiptRequest(
        application = ApplyWithReceiptRequestApplication(
            organization = organization, website = website, type = type.label, contactName = contactName,
            title = title, workEmail = workEmail, phone = phone, workforceSize = workforceSize,
        ),
        receipt = receipt,
    )

    companion object {
        /** The server remembers a receipt for 30 days. */
        const val RECEIPT_LIFETIME_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

sealed interface ApplicationSubmitPhase {
    data object Editing : ApplicationSubmitPhase
    data object Submitting : ApplicationSubmitPhase
    data object Submitted : ApplicationSubmitPhase
    data class Failed(val message: String) : ApplicationSubmitPhase
}

/**
 * The partner application form. Fields are validated the way the website's form does before a submit is allowed;
 * after an unconfirmed attempt the form LOCKS onto the frozen attempt so a retry resends exactly what was sent (same
 * Idempotency-Key and receipt). A definitive refusal unlocks it so the applicant can correct and send anew.
 */
class PartnerApplicationModel(
    private val service: PartnerApplicationService,
    private val makeAttempt: (PartnerApplication) -> PartnerApplicationAttempt = PartnerApplicationAttempt::make,
) : ViewModel() {
    var organization by mutableStateOf("")
    var website by mutableStateOf("")
    var type by mutableStateOf(PartnerOrganizationType.CORPORATE)
    var contactName by mutableStateOf("")
    var title by mutableStateOf("")
    var workEmail by mutableStateOf("")
    var phone by mutableStateOf("")
    var workforce by mutableStateOf("")

    var phase: ApplicationSubmitPhase by mutableStateOf(ApplicationSubmitPhase.Editing)
        private set

    /** An attempt the server may have received: the form stays as sent and a retry repeats it exactly. */
    private var frozen: PartnerApplicationAttempt? = null

    /** Once an attempt is frozen the fields are read-only until it is sent or definitively refused. */
    val isLocked: Boolean get() = frozen != null

    /** The form as the server expects it, or null while a required field is missing or malformed. */
    val application: PartnerApplication?
        get() {
            val organization = organization.trim()
            val contact = contactName.trim()
            val title = title.trim()
            val phone = phone.trim()
            val email = workEmail.trim()
            val site = normalizedWebsite(website)
            val size = workforce.trim().toIntOrNull()
            if (organization.isEmpty() || organization.length > 200 || contact.isEmpty() || contact.length > 100 ||
                title.isEmpty() || title.length > 100 || phone.isEmpty() || phone.length > 40 ||
                !isPlausibleEmail(email) || email.length > 320 || site == null || size == null || size < 0
            ) {
                return null
            }
            return PartnerApplication(organization, site, type, contact, title, email, phone, size)
        }

    val canSubmit: Boolean get() = phase != ApplicationSubmitPhase.Submitting && (frozen != null || application != null)

    fun submit() {
        if (!canSubmit || phase == ApplicationSubmitPhase.Submitted) return
        val attempt = frozen ?: application?.let(makeAttempt) ?: return
        frozen = attempt
        phase = ApplicationSubmitPhase.Submitting
        viewModelScope.launch {
            try {
                service.submit(attempt)
                frozen = null
                phase = ApplicationSubmitPhase.Submitted
            } catch (cancelled: CancellationException) {
                phase = ApplicationSubmitPhase.Editing
                throw cancelled
            } catch (failure: ApplicationSubmitFailure) {
                phase = when (failure) {
                    ApplicationSubmitFailure.Invalid -> { frozen = null; ApplicationSubmitPhase.Failed(INVALID) }
                    ApplicationSubmitFailure.Conflict -> { frozen = null; ApplicationSubmitPhase.Failed(CONFLICT) }
                    ApplicationSubmitFailure.Forbidden -> { frozen = null; ApplicationSubmitPhase.Failed(FORBIDDEN) }
                    is ApplicationSubmitFailure.RateLimited -> ApplicationSubmitPhase.Failed(waitMessage(failure.retryAfterSeconds))
                    ApplicationSubmitFailure.Unconfirmed -> ApplicationSubmitPhase.Failed(UNCONFIRMED)
                }
            } catch (_: Exception) {
                phase = ApplicationSubmitPhase.Failed(UNCONFIRMED)
            }
        }
    }

    companion object {
        const val INVALID = "We couldn’t accept that application. Check each field. If this work email already has an application under review, wait for that decision."
        const val CONFLICT = "That application changed while it was being sent. Review it and submit again."
        const val FORBIDDEN = "This build can’t send an application. Apply on the website instead."
        const val UNCONFIRMED = "We couldn’t confirm your application was received. Check your connection and retry; nothing will be sent twice."

        /** The server takes only https addresses; a bare host gets the https scheme the website's form adds. */
        fun normalizedWebsite(raw: String): String? {
            val text = raw.trim()
            if (text.isEmpty() || text.length > 500 || text.any(Char::isWhitespace)) return null
            val full = if (text.contains("://")) text else "https://$text"
            val url = full.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || !url.host.contains('.')) return null
            return full
        }

        fun waitMessage(seconds: Int?): String {
            if (seconds == null || seconds <= 0) return "Too many attempts. Please wait a moment and try again."
            val wait = if (seconds < 60) "$seconds second${if (seconds == 1) "" else "s"}"
            else ((seconds + 59) / 60).let { "$it minute${if (it == 1) "" else "s"}" }
            return "Too many attempts. Please wait $wait and try again."
        }

        fun factory(
            service: PartnerApplicationService,
            makeAttempt: (PartnerApplication) -> PartnerApplicationAttempt = PartnerApplicationAttempt::make,
        ): ViewModelProvider.Factory = viewModelFactory { initializer { PartnerApplicationModel(service, makeAttempt) } }
    }
}
