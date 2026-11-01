package org.dad.foundation

/**
 * The DEBUG stub backend's engagement routes (badges, network, sharing, conversions, initiative spend), shaped like
 * the contract's responses. Stateful: saving a public name mints the invite. [scenario] picks the variation; see
 * [StubEngagementScenario]. Debug source set only.
 */
internal class StubEngagement(private val scenario: StubEngagementScenario) {
    private var publicName: String? = if (scenario == StubEngagementScenario.NO_PUBLIC_NAME) null else "Alex C."

    /** True when a preferences save should fail (the rankings toggle failure scenario). */
    val failsPreferenceSave: Boolean get() = scenario == StubEngagementScenario.TOGGLE_FAIL

    fun handles(path: String) = path == "/donor/me/badges" || path == "/donor/me/network" || path == "/donor/me/sharing" ||
        path == "/donor/me/conversions" || (path.startsWith("/initiatives/") && path.endsWith("/spend"))

    fun answer(method: String, path: String, payload: String): Pair<Int, String> = when {
        path == "/donor/me/badges" -> if (scenario == StubEngagementScenario.BADGES_DOWN) 503 to UNAVAILABLE else 200 to badges()
        path == "/donor/me/network" -> if (scenario == StubEngagementScenario.NETWORK_DOWN) 503 to UNAVAILABLE else 200 to NETWORK
        path == "/donor/me/sharing" && method == "POST" -> {
            val name = Regex("\"public_name\":\"([^\"]*)\"").find(payload)?.groupValues?.get(1).orEmpty()
            if (name.isBlank()) 422 to "{}" else { publicName = name; 200 to sharing() }
        }
        path == "/donor/me/sharing" -> 200 to sharing()
        path == "/donor/me/conversions" -> 200 to CONVERSIONS
        path.startsWith("/initiatives/") ->
            if (scenario == StubEngagementScenario.SPEND_UNPUBLISHED) 404 to "{}" else 200 to SPEND
        else -> 404 to "{}"
    }

    private fun sharing(): String {
        val name = publicName ?: return """{"public_name":null,"link":null,"irl_link":null}"""
        return """{"public_name":"$name","link":"https://stub.invalid/r/ALEX2026","irl_link":"https://stub.invalid/r/ALEX2026?via=irl"}"""
    }

    private fun badges(): String {
        val specs = listOf(
            Triple("F", "Founding Donor", "Started a monthly gift before January 2027, or joined the website waitlist") to "FOUNDING_EVENTS",
            Triple("1D", "First Day", "Completed a first gift") to "",
            Triple("7", "One Week", "7-day streak") to "",
            Triple("30", "One Month", "30-day streak") to "",
            Triple("1K", "1,000 Days", "1,000-day streak") to "MILESTONE_EVENTS",
            Triple("TC", "Team Captain", "20 converted referrals") to "TEAM_CAPTAIN",
            Triple("NAT2", "Top 2%", "National top 2% by streak") to "",
            Triple("WT", "Winning Team", "Their team finished a month at #1 on the company leaderboard") to "",
        )
        val rows = specs.mapIndexed { index, (spec, perk) ->
            val earned = index < 3
            val at = if (earned) "\"2026-06-01T00:00:00Z\"" else "null"
            """{"code":"${spec.first}","title":"${spec.second}","rule":"${spec.third}","perk":"$perk","earned":$earned,"earned_at":$at,"artwork":"x"}"""
        }
        return """{"badges":[${rows.joinToString(",")}]}"""
    }

    private companion object {
        const val UNAVAILABLE = """{"detail":"unavailable"}"""
        const val NETWORK = """{"active_count":5,"annualized_minor":348000,"next_milestone":{"label":"Brought 12 In","target":12,"remaining":7},"invitees":[{"name":"Jordan M.","active":true},{"name":"Sam R.","active":false}],"anonymous_active":2}"""
        const val CONVERSIONS = """{"conversions":[{"converted_at":"2026-10-20T12:00:00Z","kind":"LINK"},{"converted_at":"2026-10-12T12:00:00Z","kind":"IRL"}],"more":false}"""
        const val SPEND = """{"initiative_id":"11111111-1111-4111-8111-111111111111","total_minor":500000,"categories":[{"category":"Food","amount_minor":300000},{"category":"Transport","amount_minor":200000}]}"""
    }
}
