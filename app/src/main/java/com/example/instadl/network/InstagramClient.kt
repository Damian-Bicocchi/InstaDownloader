package com.example.instadl.network

import com.example.instadl.auth.SessionStore
import com.example.instadl.model.IgError
import com.example.instadl.model.LoginResponse
import com.example.instadl.model.MediaFeed
import com.example.instadl.model.UserInfo
import com.google.gson.Gson
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Thin client for Instagram's private (web/API v1) endpoints.
 *
 * IMPORTANT: Using these endpoints to send automated requests to Instagram
 * violates Instagram's Terms of Service and can lead to account temporary
 * restrictions or permanent bans. Use only on accounts you own and are willing
 * to accept that risk on. This is for personal / educational use.
 */
/** Direct CDN file resolved without login (public post/reel embed scrape). */
data class PublicFile(val url: String, val isVideo: Boolean)

class InstagramClient(private val session: SessionStore) {

    private val gson = Gson()
    private val deviceUuid: String = UUID.randomUUID().toString()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** No-auth client for public embed pages (never sends session cookies). */
    private val publicClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Headers mirroring the official Instagram Android app webview/API client.
    private val baseHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Instagram 269.0.0.18.85 Android (31/12; 420dpi; 1080x2400; Google; Pixel 5; redfin; sm7150; en_US; 355471274)",
        "X-IG-App-ID" to "567067343352427",
        "X-IG-Capabilities" to "3brTvw==",
        "X-IG-Connection-Type" to "WIFI",
        "Accept-Language" to "en-US",
        "Accept" to "*/*",
        "X-FB-HTTP-Engine" to "Liger",
        "Connection" to "close"
    )

    /**
     * @param password caller-owned secret; wiped (zeroed) before returning,
     * so the cleartext lives only for the duration of this call. A transient
     * String copy is unavoidable for OkHttp FormBody — it is kept in the
     * smallest possible scope and never logged.
     */
    @Throws(IOException::class)
    fun login(username: String, password: CharArray): LoginResponse {
        val time = "${System.currentTimeMillis() / 1000}"
        val csrf = "missing"
        val passwordStr = String(password)
        password.fill('\u0000')
        try {
            val body = mapOf(
                "phone_id" to UUID.randomUUID().toString(),
                "username" to username,
                "adid" to UUID.randomUUID().toString(),
                "guid" to deviceUuid,
                "device_id" to "android-${deviceUuid}",
                "password" to passwordStr,
                "_csrftoken" to csrf,
                "login_attempt_count" to "0"
            )

            val form = FormBody.Builder()
            body.forEach { (k, v) -> form.add(k, v) }

            val req = Request.Builder()
                .url("$BASE_URL/accounts/login/")
                .post(form.build())
                .apply { baseHeaders.forEach { (k, v) -> header(k, v) } }
                .header("X-CSRFToken", csrf)
                .header("X-IG-WWW-Claim", "0")
                .header("X-Instagram-AJAX", "1")
                .header("X-Timestamp", time)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            client.newCall(req).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful || responseBody.isBlank()) {
                    // Status code only — never include body (may echo credentials).
                    throw IOException("Login failed HTTP ${response.code}")
                }

                // Keep session cookies on success (ignore login-specific errors).
                if (responseBody.contains("\"status\":\"ok\"") ||
                    responseBody.contains("\"logged_in_user\"")
                ) {
                    val cookieHeader = response.header("Set-Cookie") ?: ""
                    saveCookies(cookieHeader)
                }

                return gson.fromJson(responseBody, LoginResponse::class.java)
            }
        } finally {
            // password CharArray already wiped on entry; nothing retained here.
        }
    }

    @Throws(IOException::class)
    fun fetchUserByUsername(username: String): UserInfo {
        val req = buildGet(
            "${BASE_URL}/users/web_profile_info/?username=$username&is_sandbox=true")
        val body = executeAndCheck(req)
        val obj = JSONObject(body)
        val data = obj.getJSONObject("data").getJSONObject("user")
        return gson.fromJson(data.toString(), UserInfo::class.java)
    }

    @Throws(IOException::class)
    fun fetchUserMediaPk(pk: String, maxId: String? = null): MediaFeed {
        val cursor = if (maxId.isNullOrBlank()) "" else "&max_id=$maxId"
        val req = buildGet("${BASE_URL}/feed/user/$pk/?count=33$cursor")
        val body = executeAndCheck(req)
        return gson.fromJson(body, MediaFeed::class.java)
    }

    @Throws(IOException::class)
    fun fetchStories(userPk: String): MediaFeed {
        val req = buildGet("${BASE_URL}/feed/reels_media/?reel_ids=$userPk")
        val body = executeAndCheck(req)
        // reels_media wraps results under reels -> {userPk: {items: [...]}}
        val obj = JSONObject(body)
        val user = obj.getJSONObject("reels").getJSONObject(userPk)
        return gson.fromJson(user.toString(), MediaFeed::class.java)
    }

    @Throws(IOException::class)
    fun fetchMediaInfo(mediaId: String): MediaFeed {
        val req = buildGet("${BASE_URL}/media/$mediaId/info/")
        val body = executeAndCheck(req)
        // Real shape: {"items": [ {...} ], "status": "ok", ...}
        // (items is an ARRAY, not an object — older code used getJSONObject here.)
        val obj = JSONObject(body)
        val arr = obj.getJSONArray("items")
        val items = (0 until arr.length()).map { i ->
            gson.fromJson(arr.getJSONObject(i).toString(), com.example.instadl.model.MediaItem::class.java)
        }
        return MediaFeed(items = items, numResults = items.size, status = obj.optString("status"))
    }

    /** Single post / reel / story by numeric media id (incl. decoded shortcodes). */
    @Throws(IOException::class)
    fun fetchMediaItem(mediaId: String): com.example.instadl.model.MediaItem {
        val clean = mediaId.substringBefore('_').trim()
        require(clean.isNotBlank()) { "empty media id" }
        return fetchMediaInfo(clean).items?.firstOrNull()
            ?: throw IOException("Media not found")
    }

    /** Single post / reel by share-link shortcode (the ABC… part of /p/, /reel/). */
    @Throws(IOException::class)
    fun fetchMediaByShortcode(shortcode: String): com.example.instadl.model.MediaItem =
        fetchMediaItem(InstagramLinks.shortcodeToMediaId(shortcode))

    /**
     * Public post/reel fetch — NO login required.
     *
     * GETs the unauthenticated embed page
     * (`https://www.instagram.com/{reel,p}/{shortcode}/embed/`) and extracts
     * direct CDN URLs from the embedded JSON (`video_url` / `display_url`)
     * with `og:video` / `og:image` as fallback. No session cookie is sent.
     *
     * @throws IOException when the media is private, expired, or the page
     * layout changed — caller should fall back to the authenticated
     * /media/{id}/info/ path (or ask the user to log in).
     */
    @Throws(IOException::class)
    fun fetchPublicShortcodeMedia(shortcode: String): List<PublicFile> {
        val code = shortcode.substringBefore('?').substringBefore('#').trim()
        require(code.isNotEmpty()) { "empty shortcode" }
        var lastError: IOException? = null
        // Reel shortcodes also resolve under /p/ and vice versa; try both.
        for (kind in listOf("reel", "p")) {
            try {
                val files = fetchPublicEmbed(kind, code)
                if (files.isNotEmpty()) return files
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Public media not found")
    }

    @Throws(IOException::class)
    private fun fetchPublicEmbed(kind: String, shortcode: String): List<PublicFile> {
        val req = Request.Builder()
            .url("https://www.instagram.com/$kind/$shortcode/embed/")
            .get()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        publicClient.newCall(req).execute().use { response ->
            val html = response.body?.string() ?: ""
            if (!response.isSuccessful || html.isBlank()) {
                throw IOException("Embed HTTP ${response.code}")
            }
            val files = parseEmbedHtml(html)
            if (files.isEmpty()) {
                // Private account / login wall / removed media / markup change.
                throw IOException("Embed has no public media (private or removed?)")
            }
            return files
        }
    }

    /** Extract per-item files; correlates each carousel child via its node block. */
    internal fun parseEmbedHtml(html: String): List<PublicFile> {
        val out = mutableListOf<PublicFile>()
        val seen = LinkedHashSet<String>()

        // Carousel / sidecar children: each "node":{"is_video":…,"display_url":"…",…,"video_url":"…"}
        val nodeStarts = Regex(""""node"\s*:\s*\{\s*"is_video"\s*:\s*(true|false)""")
            .findAll(html).toList()
        if (nodeStarts.isNotEmpty()) {
            for (m in nodeStarts) {
                val window = html.substring(m.range.first, minOf(m.range.first + 6000, html.length))
                val isVideo = m.groupValues[1] == "true"
                val display = Regex(""""display_url"\s*:\s*"([^"]+)"""")
                    .find(window)?.groupValues?.get(1)?.let(::unescapeEmbedUrl)
                val video = Regex(""""video_url"\s*:\s*"([^"]+)"""")
                    .find(window)?.groupValues?.get(1)?.let(::unescapeEmbedUrl)
                val pick = if (isVideo) video ?: display else display
                if (!pick.isNullOrBlank() && seen.add(pick)) {
                    // A video child may only expose display_url in some markups;
                    // trust is_video flag only when we have a real video url.
                    out.add(PublicFile(pick, isVideo && video != null))
                }
            }
            if (out.isNotEmpty()) return out
        }

        // Single media: prefer embedded JSON urls, fall back to og: meta tags.
        val videos = Regex(""""video_url"\s*:\s*"([^"]+)"""")
            .findAll(html).map { unescapeEmbedUrl(it.groupValues[1]) }
            .filter { it.startsWith("http") }.distinct().toList()
        if (videos.isNotEmpty()) {
            return videos.filter { seen.add(it) }.map { PublicFile(it, true) }
        }
        val images = Regex(""""display_url"\s*:\s*"([^"]+)"""")
            .findAll(html).map { unescapeEmbedUrl(it.groupValues[1]) }
            .filter { it.startsWith("http") }.distinct().toList()
        if (images.isNotEmpty()) {
            return images.filter { seen.add(it) }.map { PublicFile(it, false) }
        }
        Regex("""<meta[^>]+property=["']og:video:secure_url["'][^>]+content=["']([^"']+)""")
            .find(html)?.groupValues?.get(1)?.let { unescapeHtmlAttr(it) }
            ?.takeIf { it.startsWith("http") && seen.add(it) }
            ?.let { return listOf(PublicFile(it, true)) }
        Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)""")
            .findAll(html).map { unescapeHtmlAttr(it.groupValues[1]) }
            .firstOrNull { it.startsWith("http") && seen.add(it) }
            ?.let { return listOf(PublicFile(it, false)) }
        return emptyList()
    }

    private fun unescapeEmbedUrl(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    private fun unescapeHtmlAttr(s: String): String =
        s.replace("&amp;", "&")

    fun isSessionValid(): Boolean = session.isLoggedIn

    /**
     * Best-effort server-side logout.
     *
     * POSTs to /accounts/logout/ with the stored cookies + CSRF token so a
     * stolen cookie cannot be replayed after the user taps Logout.
     * Callers must still wipe local storage even when this returns false
     * (offline / HTTP error) — see MainActivity.performLogout().
     *
     * @return true if Instagram acknowledged the logout (HTTP 2xx).
     */
    @Throws(IOException::class)
    fun logout(): Boolean {
        val cookies = session.cookies ?: return false
        val csrf = extractCsrf(cookies)
        val form = FormBody.Builder()
            .add("_csrftoken", csrf)
            .add("guid", deviceUuid)
            .add("device_id", "android-$deviceUuid")
            .build()

        val req = Request.Builder()
            .url("$BASE_URL/accounts/logout/")
            .post(form)
            .apply { baseHeaders.forEach { (k, v) -> header(k, v) } }
            .header("Cookie", cookies)
            .header("X-CSRFToken", csrf)
            .header("X-Instagram-AJAX", "1")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        client.newCall(req).execute().use { response ->
            // Drain body so the connection can be reused; ignore payload.
            try {
                response.body?.string()
            } catch (e: Exception) {
                // ignore body read errors; status code is what matters
            }
            return response.isSuccessful
        }
    }

    private fun buildGet(url: String): Request {
        val builder = Request.Builder().url(url).get()
        baseHeaders.forEach { (k, v) -> builder.header(k, v) }
        val cookies = session.cookies
        if (!cookies.isNullOrBlank()) {
            builder.header("Cookie", cookies)
        }
        builder.header("X-Instagram-AJAX", "1")
        builder.header(
            "X-CSRFToken", session.cookies?.let(::extractCsrf) ?: "missing")
        return builder.build()
    }

    private fun executeAndCheck(request: Request): String {
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val message = try {
                gson.fromJson(body, IgError::class.java)?.message ?: "HTTP ${response.code}"
            } catch (e: Exception) {
                "HTTP ${response.code}"
            }
            throw IOException(message)
        }
        return body
    }

    private fun saveCookies(setCookieHeader: String) {
        val pairs = setCookieHeader.split(';')
        val cookieMap = mutableMapOf<String, String>()
        for (p in pairs) {
            val kv = p.trim().split('=')
            if (kv.size == 2 && kv[0].isNotBlank()) {
                cookieMap[kv[0]] = kv[1]
            }
        }
        val userId = session.userId ?: ""
        val username = session.username ?: ""
        val cookieStr = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        session.saveSession(cookieStr, userId, username, username)
    }

    private fun extractCsrf(cookieHeader: String): String {
        val match = Regex("(?:^|; )csrftoken=([^;]*)").find(cookieHeader)
        return match?.groupValues?.get(1) ?: "missing"
    }

    companion object {
        const val BASE_URL = "https://i.instagram.com/api/v1"
    }
}
