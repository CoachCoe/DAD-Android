package org.dad.foundation

/**
 * The shared analytics event taxonomy (PRD §9). Every §4 growth metric depends on these
 * events being emitted from day one, so the schema is modelled as a sealed type rather than
 * free-form strings — a new event is a compile-time addition, and each event carries exactly
 * the properties the metric needs. Property values are already sanitized (codes are the
 * Attribution-sanitized form); no PII or raw credential ever enters an event.
 */
sealed interface AnalyticsEvent {
    /** The event name sent on the wire (snake_case per §9). */
    val name: String
    /** Flat property bag; values are strings/numbers/bools only. */
    val properties: Map<String, Any?>

    /** D-3/R-3: attribution was captured or updated pre-signup. */
    data class AttributionCaptured(val attribution: Attribution) : AnalyticsEvent {
        override val name = "attribution_captured"
        override val properties = mapOf(
            "has_invite" to (attribution.inviteCode != null),
            "has_irl" to (attribution.irlCode != null),
            "has_company" to (attribution.companyCode != null),
            "source" to attribution.source,
        )
    }

    /** R-1/R-2: the donor shared their personal invite link (share sheet / copy). */
    data class InviteShared(val channel: String) : AnalyticsEvent {
        override val name = "invite_shared"
        override val properties = mapOf("channel" to channel)
    }

    /** R-2: the IRL QR code was shown full-screen for scanning. */
    data object IrlCodeShown : AnalyticsEvent {
        override val name = "irl_code_shown"
        override val properties = emptyMap<String, Any?>()
    }

    /** R-3/R-4: a referral converted (first completed gift by a referred donor). */
    data class ReferralConverted(val inviteCode: String?) : AnalyticsEvent {
        override val name = "referral_converted"
        override val properties = mapOf("has_invite" to (inviteCode != null))
    }
}

/**
 * Emits analytics events. An interface so the app sends them to the backend `ingestAnalytics`
 * endpoint (batched) and tests capture them in order. Emission is fire-and-forget from the
 * caller's perspective — a dropped analytics event must never block or fail a user action.
 */
interface AnalyticsEmitter {
    fun emit(event: AnalyticsEvent)
}

/** Test/collector emitter that records events in emission order. */
class RecordingAnalyticsEmitter : AnalyticsEmitter {
    private val _events = mutableListOf<AnalyticsEvent>()
    val events: List<AnalyticsEvent> get() = _events.toList()
    override fun emit(event: AnalyticsEvent) { _events.add(event) }
}
