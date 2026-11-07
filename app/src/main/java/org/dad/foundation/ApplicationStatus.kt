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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.http.GET

// Partner application status (PRD P-1..P-3): an organization that applied sees where its application stands before
// it has a portal. The server identifies the application by a private receipt the applicant generated when applying;
// a native request presents that receipt as its bearer (`applicationStatus`).
//
// The receipt is created by `applyWithReceipt` (contract 2.11) when the applicant submits the form natively
// (`WirePartnerApplication` in ApplicationSubmit.kt); this card then reads the status back from the stored receipt,
// and forgets it once the server no longer recognizes it.

/** Where an application stands. A status this build does not know is kept verbatim, never guessed. */
sealed interface ApplicationState {
    data object Pending : ApplicationState
    data object Approved : ApplicationState
    data object Rejected : ApplicationState
    data class Other(val raw: String) : ApplicationState

    companion object {
        fun from(raw: String): ApplicationState = when (raw) {
            "PENDING" -> Pending
            "APPROVED" -> Approved
            "REJECTED" -> Rejected
            else -> Other(raw)
        }
    }
}

data class ApplicationStatusInfo(val organization: String, val state: ApplicationState) {
    /** One sentence for the applicant. No contact details or staff notes ever reach the app. */
    val message: String
        get() = when (state) {
            ApplicationState.Pending -> "Your application for $organization is being reviewed. We’ll email you when it’s decided."
            ApplicationState.Approved -> "$organization is approved. Request a sign-in link with your work email to open the portal."
            ApplicationState.Rejected -> "$organization wasn’t approved. Contact DAD support if you have questions."
            is ApplicationState.Other -> "We’re updating the status of your application for $organization. Check back soon."
        }

    val title: String
        get() = when (state) {
            ApplicationState.Pending -> "Application under review"
            ApplicationState.Approved -> "Application approved"
            ApplicationState.Rejected -> "Application not approved"
            is ApplicationState.Other -> "Application status"
        }
}

interface ApplicationStatusService {
    /** The status of the application this install holds a receipt for, or null when it holds none (or the server
     *  no longer recognizes it, which also forgets it). */
    suspend fun status(): ApplicationStatusInfo?
}

private interface ApplicationReads {
    @GET("donor/partner/application-status") suspend fun status(): Response<JsonElement>
}

/**
 * Reads `applicationStatus` with the stored receipt as the bearer. The receipt is the only thing kept, in its own
 * store apart from the donor and partner sessions. It is never logged or shown.
 */
class WireApplicationStatus(
    origin: String,
    private val receipt: CredentialStore,
    private val baseClient: OkHttpClient = defaultNativeClient(),
) : ApplicationStatusService {
    private val url: HttpUrl = validatedOrigin(origin)

    override suspend fun status(): ApplicationStatusInfo? {
        val kept = withContext(Dispatchers.IO) { receipt.load() } ?: return null
        val response = decoding { nativeRetrofit(url, baseClient, kept.access).create(ApplicationReads::class.java).status() }
        if (response.code() == 401) {
            // Unknown or expired receipt: there is no application to show, and the receipt is useless.
            response.errorBody()?.close()
            withContext(Dispatchers.IO) { receipt.clear() }
            return null
        }
        val body = decoding { response.successBody() }.asObjectOrInvalid()
        return ApplicationStatusInfo(body.str("organization"), ApplicationState.from(body.str("status")))
    }
}

sealed interface ApplicationStatusPhase {
    data object Loading : ApplicationStatusPhase
    /** No receipt, or the server no longer knows it: show nothing. */
    data object None : ApplicationStatusPhase
    data class Loaded(val info: ApplicationStatusInfo) : ApplicationStatusPhase
    data class Failed(val message: String) : ApplicationStatusPhase
}

class ApplicationStatusModel(private val service: ApplicationStatusService) : ViewModel() {
    var phase: ApplicationStatusPhase by mutableStateOf(ApplicationStatusPhase.Loading)
        private set

    init { load() }

    /** A cancelled read keeps whatever was showing; a failed one never replaces a status already read. */
    fun load() {
        viewModelScope.launch {
            val prior = phase
            try {
                phase = service.status()?.let(ApplicationStatusPhase::Loaded) ?: ApplicationStatusPhase.None
            } catch (cancelled: CancellationException) {
                phase = prior
                throw cancelled
            } catch (error: Exception) {
                if (prior is ApplicationStatusPhase.Loaded) return@launch
                phase = ApplicationStatusPhase.Failed(
                    if (error is AccountFailure.RateLimited) "Too many attempts. Please wait a moment and try again."
                    else "We couldn’t check your application right now. Please try again in a moment.",
                )
            }
        }
    }

    companion object {
        fun factory(service: ApplicationStatusService): ViewModelProvider.Factory =
            viewModelFactory { initializer { ApplicationStatusModel(service) } }
    }
}
