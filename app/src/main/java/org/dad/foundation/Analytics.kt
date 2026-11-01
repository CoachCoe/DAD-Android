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
    /**
     * Wire properties, string to string exactly as the ingest endpoint accepts them. DAD-Main's
     * analytics allow-list rejects any event carrying a key it does not declare, so an event with
     * no declared property must keep this empty.
     */
    val properties: Map<String, String>

    /** D-3/R-3: attribution was captured or updated pre-signup. Carries only `source`. */
    data class AttributionCaptured(val source: AttributionSource) : AnalyticsEvent {
        override val name = "attribution_captured"
        override val properties = mapOf("source" to source.wire)
    }

    /** R-1/R-2: the donor shared their personal invite link (share sheet / copy). Carries `channel`. */
    data class InviteShared(val channel: String) : AnalyticsEvent {
        override val name = "invite_shared"
        override val properties = mapOf("channel" to channel)
    }

    /** R-2: the IRL QR code was shown full-screen for scanning. */
    data object IrlCodeShown : AnalyticsEvent {
        override val name = "irl_code_shown"
        override val properties = emptyMap<String, String>()
    }

    /** A tapped notification; `type` is its server `category` (`REFERRAL_CONVERTED`, `STREAK_AT_RISK`, ...). */
    data class PushOpened(val type: String) : AnalyticsEvent {
        override val name = "push_opened"
        override val properties = mapOf("type" to type)
    }

    /** One of the §9 events that carry no properties. */
    data class Plain(val event: PlainEvent) : AnalyticsEvent {
        override val name = event.wire
        override val properties = emptyMap<String, String>()
    }

    /** R-3/R-4: a referral converted (first completed gift by a referred donor). Takes no properties. */
    data object ReferralConverted : AnalyticsEvent {
        override val name = "referral_converted"
        override val properties = emptyMap<String, String>()
    }
}

/** The §9 events without properties that the app emits, by their ingest names. */
enum class PlainEvent(val wire: String) {
    APP_OPENED("app_opened"), ROLE_SELECTED("role_selected"),
    SIGNUP_STARTED("signup_started"), SIGNUP_COMPLETED("signup_completed"),
    GIFT_STARTED("gift_started"), AMOUNT_CHANGED("amount_changed"), GIFT_CANCELLED("gift_cancelled"), ACCOUNT_DELETED("account_deleted"),
    RANKINGS_TOGGLED("rankings_toggled"), VOLUNTEER_OPTED_IN("volunteer_opted_in"),
    EVENT_VIEWED("event_viewed"), EVENT_RESERVED("event_reserved"), EVENT_WAITLISTED("event_waitlisted"), EVENT_CHECKED_IN("event_checked_in"),
    TEAM_JOINED("team_joined"), PARTNER_REPORT_EXPORTED("partner_report_exported"),
}

/** The closed `source` set the ingest endpoint accepts for `attribution_captured` (case-sensitive). */
enum class AttributionSource(val wire: String) {
    LINK("link"), IRL("IRL"), COMPANY("company");

    companion object {
        /** The touch kind of one attribution payload: an IRL code, else an invite link, else a company code. */
        fun of(attribution: Attribution): AttributionSource? = when {
            attribution.irlCode != null -> IRL
            attribution.inviteCode != null -> LINK
            attribution.companyCode != null -> COMPANY
            else -> null
        }
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
