package com.bililive.danmaku.data.remote

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Shared HTTP/WebSocket cookie routing, without an Android dependency.
 *
 * LoginManager owns account cookies. Read one current snapshot per request, rather than caching
 * them alongside server cookies, so an old response cannot undo logout or an account change.
 */
internal class BiliBiliCookieStore(
    private val loginCookieProvider: () -> String = { "" },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) : CookieJar {
    private data class CookieKey(val name: String, val domain: String, val path: String)

    private val lock = Any()
    private val browserCookies = linkedMapOf<CookieKey, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // This is an application-specific jar, not a general-purpose browser cookie store.
        if (!isBilibiliHost(url.host)) return
        synchronized(lock) {
            val now = currentTimeMillis()
            removeExpired(now)
            for (cookie in cookies) {
                // These values must come only from the current authenticated account snapshot.
                if (cookie.name in LOGIN_COOKIE_NAMES) continue
                if (!isBilibiliHost(cookie.domain)) continue
                if (!matchesOrigin(cookie, url)) continue
                val key = CookieKey(cookie.name, cookie.domain, cookie.path)
                if (cookie.expiresAt <= now) {
                    browserCookies.remove(key)
                } else {
                    browserCookies[key] = cookie
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isBilibiliHost(url.host)) return emptyList()
        val loginCookies = if (url.isHttps) {
            currentLoginCookies().filter { it.matches(url) }
        } else {
            emptyList()
        }
        return loginCookies + browserCookiesFor(url)
    }

    fun browserCookieValue(name: String, url: HttpUrl): String =
        browserCookiesFor(url).firstOrNull { it.name == name }?.value.orEmpty()

    fun clear() {
        synchronized(lock) { browserCookies.clear() }
    }

    private fun browserCookiesFor(url: HttpUrl): List<Cookie> = synchronized(lock) {
        removeExpired(currentTimeMillis())
        // Cookie.matches enforces host-only/domain boundaries, path boundaries and Secure.
        browserCookies.values.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }

    private fun removeExpired(now: Long) {
        browserCookies.values.removeAll { it.expiresAt <= now }
    }

    private fun currentLoginCookies(): List<Cookie> {
        val cookies = linkedMapOf<String, Cookie>()
        loginCookieProvider().split(';').forEach { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size != 2) return@forEach
            val name = pair[0].trim()
            val value = pair[1].trim()
            if (name !in LOGIN_COOKIE_NAMES || value.isEmpty()) return@forEach
            // Cookie.Builder does not reject embedded controls. Accept RFC 6265 cookie-octets
            // explicitly so imported data cannot inject a header or another cookie.
            if (value.any { it !in '\u0021'..'\u007e' || it in "\",;\\" }) return@forEach
            // Invalid imported values must not crash a request or become arbitrary headers.
            val cookie = try {
                Cookie.Builder().name(name).value(value).domain(BILI_DOMAIN).path("/")
                    .secure().httpOnly().build()
            } catch (_: IllegalArgumentException) {
                return@forEach
            }
            cookies[name] = cookie
        }
        // Auxiliary account cookies alone do not represent an authenticated session.
        return if ("SESSDATA" in cookies) cookies.values.toList() else emptyList()
    }

    private fun matchesOrigin(cookie: Cookie, url: HttpUrl): Boolean {
        // A response may set a cookie for a different path, but never an unrelated domain.
        val domainMatches = url.host == cookie.domain ||
            (!cookie.hostOnly && url.host.endsWith(".${cookie.domain}"))
        return domainMatches && (!cookie.secure || url.isHttps)
    }

    private fun isBilibiliHost(host: String): Boolean =
        host == BILI_DOMAIN || host.endsWith(".$BILI_DOMAIN")

    private companion object {
        const val BILI_DOMAIN = "bilibili.com"
        val LOGIN_COOKIE_NAMES = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
    }
}
