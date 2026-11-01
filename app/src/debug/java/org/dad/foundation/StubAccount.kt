package org.dad.foundation

/**
 * The DEBUG stub backend's account-management routes: contact details, email change, sessions, sign out
 * everywhere, the employer link and team, and the Wallet pass. Company code `ACME42` links "Acme"; an email
 * starting `taken` belongs to another account (409). Google Wallet is offered but the pass is unavailable (503),
 * so a UI test sees the failure line rather than leaving the app. Debug source set only.
 */
internal class StubAccount {
    var zip = "78701"
        private set
    var city = "Austin"
        private set
    var phone = ""
        private set
    private var otherSessions = listOf(OTHER_SESSION)
    private var employer: String? = null
    private var team: String? = null

    fun handles(method: String, path: String) = (method == "POST" && path == "/donor/me/profile") ||
        path.startsWith("/donor/me/sessions") || path == "/donor/logout-all" || path == "/donor/me/email-change" ||
        path == "/donor/me/employer" || path == "/donor/me/team" || path.startsWith("/donor/me/wallet")

    /** [profile] renders the saved profile, which a contact-details save answers with. */
    fun answer(method: String, path: String, payload: String, profile: () -> String): Pair<Int, String> {
        fun field(key: String) = Regex("\"$key\":\"([^\"]*)\"").find(payload)?.groupValues?.get(1)
        return when {
            path == "/donor/me/profile" -> {
                val newZip = field("zip").orEmpty()
                if (newZip.isNotEmpty() && !Regex("^[0-9]{5}(-[0-9]{4})?$").matches(newZip)) 422 to "{}"
                else { zip = newZip; city = field("city").orEmpty(); phone = field("phone").orEmpty(); 200 to profile() }
            }
            path == "/donor/me/email-change" -> when {
                field("email").orEmpty().startsWith("taken") -> 409 to "{}"
                field("email").orEmpty().contains("@") -> 200 to """{"stage":"CURRENT"}"""
                else -> 422 to "{}"
            }
            path == "/donor/me/sessions" -> 200 to sessions()
            path == "/donor/me/sessions/revoke-others" -> { otherSessions = emptyList(); 200 to sessions() }
            path.startsWith("/donor/me/sessions/") && path.endsWith("/revoke") -> {
                val id = path.removePrefix("/donor/me/sessions/").removeSuffix("/revoke")
                val remaining = otherSessions.filterNot { it.contains("\"id\":\"$id\"") }
                if (remaining.size == otherSessions.size) 404 to "{}" else { otherSessions = remaining; 200 to sessions() }
            }
            path == "/donor/logout-all" -> { otherSessions = emptyList(); 200 to """{"signed_out":true}""" }
            path == "/donor/me/team" && method == "GET" -> 200 to team()
            path == "/donor/me/employer" ->
                if (field("company_code") == "ACME42") { employer = "Acme"; 200 to """{"organization":"Acme"}""" } else 422 to "{}"
            path == "/donor/me/team" -> {
                val name = field("name").orEmpty()
                if (employer == null || name.isBlank() || name.length > 80) 422 to "{}"
                else { team = name; 200 to """{"team_id":"$TEAM_ID"}""" }
            }
            path == "/donor/me/wallet" -> 200 to """{"apple":false,"google":true}"""
            path == "/donor/me/wallet/google" -> 503 to """{"detail":"unavailable"}"""
            else -> 404 to "{}"
        }
    }

    private fun sessions() = """{"sessions":[${(listOf(CURRENT_SESSION) + otherSessions).joinToString(",")}]}"""

    private fun team(): String {
        val org = employer ?: return """{"partner_id":"","organization":"","leaderboard":[]}"""
        val mine = team?.let { ""","team_id":"$TEAM_ID","team_name":"$it"""" }.orEmpty()
        return """{"partner_id":"44444444-4444-4444-8444-444444444444","organization":"$org"$mine,"leaderboard":[""" +
            """{"team_id":"55555555-5555-4555-8555-555555555555","name":"Design","active_givers":7,"annualized_minor":420000,"avg_streak_days":21,"volunteer_minutes":180}]}"""
    }

    companion object {
        private const val OTHER_SESSION_ID = "22222222-2222-4222-8222-222222222222"
        private const val TEAM_ID = "33333333-3333-4333-8333-333333333333"
        private const val CURRENT_SESSION = """{"id":"11111111-1111-4111-8111-111111111111","client":"APP","platform":"ANDROID","label":"Pixel","created_at":"2026-10-01T10:00:00Z","last_used_at":"2026-10-30T10:00:00Z","current":true}"""
        private const val OTHER_SESSION = """{"id":"$OTHER_SESSION_ID","client":"BROWSER","platform":null,"label":"Web browser","created_at":"2026-10-02T10:00:00Z","last_used_at":"2026-10-29T10:00:00Z","current":false}"""
    }
}
