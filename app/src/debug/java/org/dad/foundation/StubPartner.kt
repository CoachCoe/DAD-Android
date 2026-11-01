package org.dad.foundation

/**
 * The DEBUG stub backend's partner routes. Partner sign-in links are accepted for tokens starting `goodpartner`
 * (anything else is the server's 422 "invalid or expired"); the portal is an ADMIN at Acme with two users, one
 * unverified figure, an earlier export whose retained PDF has aged out (404), and no logo. Debug source set only.
 */
internal class StubPartner {
    class Reply(val code: Int, val body: ByteArray, val type: String = "application/json") {
        constructor(code: Int, json: String) : this(code, json.toByteArray())
    }

    private var organization = "Acme Corp"
    private var about = "Doing good together."
    private var workforce = 240
    private var logo: ByteArray? = null
    private val users = mutableListOf(
        """{"id":"u1","email":"ada@acme.com","name":"Ada Admin","role":"ADMIN"}""",
        """{"id":"u2","email":"val@acme.com","name":"Val Viewer","role":"VIEWER"}""",
    )
    private val exports = mutableListOf(
        """{"id":"r1","committed_minor":4000000,"deployed_minor":2000000,"people_reached":9000,"exported_by":"Ada Admin","created_at":"2026-09-01T10:00:00Z","downloadable":true,"people_reached_verified":true}""",
    )

    fun handles(path: String) = path.startsWith("/donor/partner/")

    fun answer(method: String, path: String, payload: String, authorization: String?): Reply {
        fun field(key: String) = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(payload)?.groupValues?.get(1)
        when (method to path) {
            "POST" to "/donor/partner/sign-in" -> return if (field("email").orEmpty().contains("@")) Reply(202, """{"message":"stub"}""") else Reply(422, "{}")
            "POST" to "/donor/partner/accept-invite" ->
                return if (field("invite").orEmpty().startsWith("goodpartner")) Reply(200, """{"session":"stub-partner-1"}""") else Reply(422, "{}")
        }
        if (authorization?.startsWith("Bearer stub-partner-") != true) return Reply(401, "{}")
        return when (method to path) {
            "GET" to "/donor/partner/overview" -> Reply(200, """{"user_id":"u1","organization":${quoted(organization)},"description":${quoted(about)},""" +
                """"role":"ADMIN","company_code":"ACME","committed_minor":5000000,"deployed_minor":3000000,"deployed_percent":60,""" +
                """"people_reached":12400,"employees_enrolled":86,"workforce_size":$workforce,"enrolled_percent":35,""" +
                """"updated_at":"2026-10-28T10:00:00Z","has_logo":${logo != null},"committed_verified":true,"deployed_verified":false,"people_reached_verified":true}""")
            "GET" to "/donor/partner/initiatives" -> Reply(200, """{"initiatives":[{"id":"i1","title":"Clean Water West","summary":"Wells across 6 counties.",""" +
                """"status":"ACTIVE","region":"West","county_count":6,"committed_minor":2000000,"deployed_minor":1200000,""" +
                """"metrics":[{"label":"People reached","value":8400,"unit":"PEOPLE","verified_on":"2026-10-20"}],""" +
                """"spend":[{"category":"Water","amount_minor":1200000}],"updated_at":"2026-10-28T10:00:00Z"}]}""")
            "GET" to "/donor/partner/spend" -> Reply(200, """{"categories":[{"category":"Water","amount_minor":1800000},{"category":"Housing","amount_minor":1200000}]}""")
            "GET" to "/donor/partner/our-people" -> Reply(200, """{"enrolled":86,"workforce_size":$workforce,"given_minor":940000,"matched_minor":470000,""" +
                """"avg_streak_days":42,"volunteer_minutes":6300,"teams":2,"suppressed":false}""")
            "GET" to "/donor/partner/teams" -> Reply(200, """{"teams":[{"team_id":"t1","name":"Engineering","active_givers":31,"annualized_minor":1200000,""" +
                """"avg_streak_days":40,"volunteer_minutes":1200},{"team_id":"t2","name":"Sales","active_givers":12,"annualized_minor":600000,""" +
                """"avg_streak_days":18,"volunteer_minutes":300}]}""")
            "GET" to "/donor/partner/users" -> Reply(200, """{"users":[${users.joinToString(",")}]}""")
            "POST" to "/donor/partner/users" -> {
                val email = field("email").orEmpty()
                val name = field("name").orEmpty()
                val role = field("role").orEmpty()
                when {
                    !email.contains("@") || name.isEmpty() || role.isEmpty() -> Reply(422, "{}")
                    email.startsWith("taken") -> Reply(409, "{}")
                    else -> {
                        users.add("""{"id":"u${users.size + 1}","email":${quoted(email)},"name":${quoted(name)},"role":"$role"}""")
                        Reply(200, """{"invited":true}""")
                    }
                }
            }
            "POST" to "/donor/partner/profile" -> {
                val name = field("organization").orEmpty()
                if (name.length !in 1..200) Reply(422, "{}")
                else { organization = name; about = field("description").orEmpty(); Reply(200, """{"saved":true}""") }
            }
            "POST" to "/donor/partner/workforce" -> {
                val size = Regex("\"workforce_size\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toIntOrNull()
                if (size == null) Reply(422, "{}") else { workforce = size; Reply(200, """{"saved":true}""") }
            }
            "GET" to "/donor/partner/logo" -> logo?.let { Reply(200, it, "image/png") } ?: Reply(404, "{}")
            "POST" to "/donor/partner/logo" -> {
                val dataUrl = field("data_url").orEmpty()
                val decoded = if (dataUrl.isEmpty()) null else runCatching { java.util.Base64.getDecoder().decode(dataUrl.substringAfter(",")) }.getOrNull()
                if (dataUrl.isNotEmpty() && decoded == null) Reply(422, "{}") else { logo = decoded; Reply(200, """{"saved":true}""") }
            }
            "GET" to "/donor/partner/exports" -> Reply(200, """{"exports":[${exports.joinToString(",")}]}""")
            "GET" to "/donor/partner/report.pdf" -> {
                exports.add(0, """{"id":"r${exports.size + 1}","committed_minor":5000000,"deployed_minor":3000000,"people_reached":12400,""" +
                    """"exported_by":"Ada Admin","created_at":"2026-11-01T10:00:00Z","downloadable":true,"people_reached_verified":true}""")
                Reply(200, "%PDF-1.4 stub".toByteArray(), "application/pdf")
            }
            "POST" to "/donor/partner/sign-out" -> Reply(200, """{"signed_out":true}""")
            "POST" to "/donor/partner/push-device", "POST" to "/donor/partner/push-device/unregister" ->
                Reply(200, """{"registered":false,"platform":null,"environment":null}""")
            else -> when {
                method == "POST" && path.startsWith("/donor/partner/users/") && path.endsWith("/remove") -> {
                    val id = path.removePrefix("/donor/partner/users/").removeSuffix("/remove")
                    users.removeAll { it.contains("\"id\":\"$id\"") }
                    Reply(200, """{"removed":true}""")
                }
                method == "GET" && path.startsWith("/donor/partner/reports/") ->
                    if (path.contains("/r1/")) Reply(404, "{}") else Reply(200, "%PDF-1.4 stub".toByteArray(), "application/pdf")
                else -> Reply(404, "{}")
            }
        }
    }

    private fun quoted(text: String) = com.google.gson.Gson().toJson(text)
}
