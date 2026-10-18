package org.dad.foundation

import com.google.gson.Gson
import java.net.URI
import java.net.URLDecoder

enum class AccountDestination(val path: String) {
    DASHBOARD("/account/dashboard"), PROFILE("/account/profile"), PAYMENT_METHOD("/account/payment-method");
    companion object { fun from(path: String) = entries.firstOrNull { it.path == path } }
}
sealed interface LinkIntent {
    data class Account(val destination: AccountDestination) : LinkIntent
    data class Claim(val token: String, val next: AccountDestination?) : LinkIntent { override fun toString() = "Claim([redacted])" }
    data class EmailChange(val token: String) : LinkIntent { override fun toString() = "EmailChange([redacted])" }
    data class Web(val uri: URI) : LinkIntent { override fun toString() = "WebLink([redacted])" }
}
private data class LinkPolicy(
    val account_paths: List<String>, val proof_paths: List<String>, val claim_return_paths: List<String>,
    val notification_paths: List<String>,
)

class LinkRouter(publicOrigin: String, policyJson: String) {
    private val origin = URI(publicOrigin).also {
        require(it.scheme == "https" && it.host != null && it.userInfo == null && it.query == null && it.fragment == null && it.path in listOf("", "/"))
    }
    private val policy = Gson().fromJson(policyJson, LinkPolicy::class.java)
    fun route(value: String): LinkIntent? {
        if (value.length > 4096) return null
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { return null }
        if (uri.scheme != "https" || !uri.host.equals(origin.host, ignoreCase = true) || port(uri) != port(origin) || uri.userInfo != null ||
            uri.rawPath.orEmpty().contains(Regex("%2f|%5c", RegexOption.IGNORE_CASE))) return null
        if (uri.path in policy.proof_paths) {
            if (uri.query != null || uri.rawFragment == null || uri.rawFragment.length > 1024) return null
            val items = try { uri.rawFragment.split("&").map { entry ->
                val pair = entry.split("=", limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            } } catch (_: IllegalArgumentException) { return null }
            if (items.count { it.first == "token" } != 1 || items.count { it.first == "next" } > 1 ||
                items.any { it.first != "token" && !(uri.path == "/account/claim" && it.first == "next") }) return null
            val token = items.first { it.first == "token" }.second
            if (!token.matches(Regex("[A-Za-z0-9_-]{32,128}"))) return null
            if (uri.path == "/account/email-change") return LinkIntent.EmailChange(token)
            val next = items.firstOrNull { it.first == "next" }?.second
            if (next != null && next !in policy.claim_return_paths) return null
            return LinkIntent.Claim(token, next?.let(AccountDestination::from))
        }
        if (uri.path in policy.account_paths && uri.query == null && uri.fragment == null) {
            AccountDestination.from(uri.path)?.let { return LinkIntent.Account(it) }
        }
        return LinkIntent.Web(uri)
    }
    fun notification(path: String): AccountDestination? = if (path in policy.notification_paths) AccountDestination.from(path) else null
    private fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
}

class NavigationGate {
    var pending: AccountDestination? = null
        private set
    fun receive(destination: AccountDestination, signedIn: Boolean): AccountDestination? {
        if (signedIn) return destination
        pending = destination
        return null
    }
    fun signedIn(): AccountDestination? = pending.also { pending = null }
    fun signedOut() { pending = null }
}
