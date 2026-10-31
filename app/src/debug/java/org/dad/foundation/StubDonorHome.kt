package org.dad.foundation

/**
 * The DEBUG stub backend's donor-home reads (dashboard, today's initiative, 120 gifts paged 50 at a time,
 * receipts, annual summary), shaped exactly like the contract's responses (including the nulls). The
 * [scenario] picks what is answered; see [StubDashboard]. Debug source set only.
 */
internal class StubDonorHome(private val scenario: StubDashboard) {
    private val failuresServed = HashSet<String>()

    /** Under FAIL_FIRST the first read of each kind is a 503, so the UI test sees the error and its retry. */
    private fun failsThisTime(key: String) = scenario == StubDashboard.FAIL_FIRST && failuresServed.add(key)

    fun answer(path: String, offset: Int?): Pair<Int, String> {
        // SLOW holds the two home cards' reads back, so a UI test can look at the loading layout and compare it.
        if (scenario == StubDashboard.SLOW && (path == "/donor/me/dashboard" || path == "/donor/me/today-initiative")) Thread.sleep(SLOW_MILLIS)
        return respond(path, offset)
    }

    private fun respond(path: String, offset: Int?): Pair<Int, String> = when {
        path == "/donor/me/dashboard" -> when {
            failsThisTime("dashboard") -> 503 to UNAVAILABLE
            scenario == StubDashboard.EMPTY -> 200 to PREPARING
            scenario == StubDashboard.UNRANKED -> 200 to UNRANKED
            else -> 200 to READY
        }
        path == "/donor/me/today-initiative" -> if (scenario == StubDashboard.EMPTY) 404 to NOT_FOUND else 200 to TODAY
        path == "/donor/me/gifts" -> if (failsThisTime("gifts")) 503 to UNAVAILABLE else 200 to page(offset ?: 0)
        path.startsWith("/donor/me/receipts/") -> {
            val index = giftIndex(path.removePrefix("/donor/me/receipts/"))
            if (scenario == StubDashboard.EMPTY || index == null) 404 to NOT_FOUND
            else 200 to """{"mode":"test","tax_receipt":false,"as_of":"2026-10-30T00:00:00Z","receipt_id":"rcpt-$index","gift":${giftRecord(index, withRefunds = true)}}"""
        }
        path.startsWith("/donor/me/annual-summary/") ->
            if (scenario == StubDashboard.EMPTY) 404 to NOT_FOUND
            else 200 to """{"mode":"test","tax_receipt":false,"year":2026,"timezone":"America/Chicago","as_of":"2026-10-30T00:00:00Z","gifts":[${giftRecord(0, withRefunds = true)}],"amount_minor":360000,"refunded_minor":8500,"net_minor":351500,"currency":"USD"}"""
        else -> 404 to NOT_FOUND
    }

    private fun page(offset: Int): String {
        val total = if (scenario == StubDashboard.EMPTY) 0 else GIFT_TOTAL
        val end = minOf(offset + 50, total)
        val rows = (offset until maxOf(offset, end)).joinToString(",") { giftRecord(it, withRefunds = false) }
        return """{"email":"alex@example.org","mode":"test","gifts":[$rows],"next_offset":${if (end < total) end.toString() else "null"}}"""
    }

    private companion object {
        const val GIFT_TOTAL = 120
        const val SLOW_MILLIS = 1_500L
        const val UNAVAILABLE = """{"detail":"unavailable"}"""
        const val NOT_FOUND = """{"detail":"not found"}"""
        const val PREPARING = """{"state":"preparing","mode":"test"}"""
        const val READY = """{"state":"ready","mode":"test","computed_at":"2026-10-30T12:00:00Z","founding_donor":true,"referrals":{"currency":"USD","irl":1,"irl_minor":3000,"link":2,"link_minor":9000,"total_minor":12000},"streak":{"at_risk":false,"current":18,"grace_ends_at":null,"ladder":{"days":18,"last_milestone":7,"next_milestone":30,"days_to_next":12,"bar":[true,true,true,true,true,true,true,true,true,true,true,true,true,true,true,true,true,true,false,false,false,false,false,false,false,false,false,false,false,false]}},"rank":{"national_percentile":2,"city":"Austin","city_rank":4,"computed_at":"2026-10-30T00:00:00Z"},"rankings_opted_out":false,"network":{"active_count":3,"annualized_minor":474500,"currency":"USD"},"impact":{"given_minor":124000,"currency":"USD","initiatives_funded":4,"events_volunteered":2}}"""
        const val UNRANKED = """{"state":"ready","mode":"test","computed_at":"2026-10-30T12:00:00Z","founding_donor":false,"referrals":null,"streak":null,"rank":null,"rankings_opted_out":true,"network":null,"impact":{"given_minor":null,"currency":"USD","initiatives_funded":0,"events_volunteered":0}}"""
        const val TODAY = """{"initiative_id":"11111111-1111-4111-8111-111111111111","slug":"meals-on-wheels","title":"Meals on Wheels","summary":"Hot meals delivered to neighbours who cannot cook for themselves.","region":"TX","location":"Austin, TX","type":"PROGRAM","started_on":"2025-01-02","metrics":[{"label":"Meals this week","value":1200,"unit":"PEOPLE","verified_on":"2026-10-28"},{"label":"Cost per meal","value":425,"unit":"USD_CENTS","verified_on":"2026-10-28"},{"label":"Partners","value":1,"unit":"PARTNERS","verified_on":"2026-10-28"}],"distinct_shown":4}"""

        fun giftId(index: Int) = "00000000-0000-4000-8000-" + index.toString().padStart(12, '0')
        fun giftIndex(id: String): Int? =
            if (id.startsWith("00000000-0000-4000-8000-")) id.takeLast(12).toIntOrNull()?.takeIf { it in 0 until GIFT_TOTAL } else null

        /** Every seventh gift carries a $5 refund; only the receipt lists the refund rows. */
        fun giftRecord(index: Int, withRefunds: Boolean): String {
            val refunded = if (index % 7 == 0) 500 else 0
            val day = (28 - index % 28).toString().padStart(2, '0')
            val refund = if (refunded > 0) """{"id":"re_$index","amount_minor":500,"status":"succeeded","observed_at":"2026-09-29T10:00:00Z"}""" else ""
            val tail = if (withRefunds) ""","refunds":[$refund]""" else ""
            return """{"id":"${giftId(index)}","amount_minor":3000,"currency":"USD","paid_at":"2026-09-${day}T10:00:00Z","frequency":"MONTHLY","refunded_minor":$refunded,"net_minor":${3000 - refunded}$tail}"""
        }
    }
}
