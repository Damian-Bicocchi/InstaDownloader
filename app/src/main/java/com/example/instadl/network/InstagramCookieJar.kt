package com.example.instadl.network

import com.example.instadl.auth.SessionStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class InstagramCookieJar(private val session: SessionStore) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!isInstagramHost(url.host) || cookies.isEmpty()) return

        val merged = LinkedHashMap<String, String>()
        for ((name, value) in storedPairs()) {
            merged[name] = value
        }
        var changed = false
        val now = System.currentTimeMillis()

        for (cookie in cookies) {
            if (cookie.expiresAt <= now) {
                if (merged.remove(cookie.name) != null) changed = true
            } else {
                merged[cookie.name] = cookie.value
                changed = true
            }
        }

        if (changed) {
            session.saveCookies(
                merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
            )
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isInstagramHost(url.host)) return emptyList()

        return storedPairs().mapNotNull { (name, value) ->
            try {
                Cookie.Builder()
                    .hostOnlyDomain(url.host)
                    .name(name)
                    .value(value)
                    .path("/")
                    .build()
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    private fun storedPairs(): List<Pair<String, String>> {
        val raw = session.cookies ?: return emptyList()
        return raw.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val separator = trimmed.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                trimmed.substring(0, separator).trim() to
                    trimmed.substring(separator + 1).trim()
            }
        }
    }

    private fun isInstagramHost(host: String): Boolean =
        host == "i.instagram.com" || host == "www.instagram.com"

    companion object {
        const val API_HOST = "i.instagram.com"
    }
}
