package com.example.instadl.network

import android.util.Base64
import com.example.instadl.auth.SessionStore
import com.example.instadl.model.IgError
import com.example.instadl.model.LoggedInUser
import com.example.instadl.model.MediaFeed
import com.google.gson.Gson
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Thin client for Instagram's private (web/API v1) endpoints, plus a
 * public no-auth embed fetcher for shared post/reel links.
 *
 * IMPORTANT: Using the private endpoints to send automated requests to
 * Instagram violates Instagram's Terms of Service and can lead to account
 * temporary restrictions or permanent bans. Use only on accounts you own and
 * are willing to accept that risk on. This is for personal / educational use.
 *
 * Design notes:
 * - Login uses a caller-owned CharArray password (wiped on entry); only a
 *   transient String copy exists for the OkHttp FormBody and it is never
 *   logged. Session cookies live in EncryptedSharedPreferences, never as
 *   cleartext. Logout POSTs server-side best-effort, then local storage is
 *   always wiped.
 * - Public post/reel links are tried FIRST via the unauthenticated embed
 *   page (no cookies sent). The authenticated /media/{id}/info/ path is only
 *   a fallback for private media, and stories/highlights always require the
 *   logged-in session.
 */
/** Direct CDN file resolved without login (public post/reel embed scrape). */
data class PublicFile(val url: String, val isVideo: Boolean)

class InstagramClient(private val session: SessionStore) {

    private val gson = Gson()
    private val deviceUuid: String = UUID.randomUUID().toString()
    private val cookieJar = InstagramCookieJar(session)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** No-auth client for public embed pages (never sends session cookies). */
    private val publicClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Matches Instaloader's iPad client, which is known to accept web sessions.
    private val baseHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Instagram 361.0.0.35.82 (iPad13,8; iOS 18_0; en_US; en-US; scale=2.00; 2048x2732; 674117118) AppleWebKit/420+",
        "X-IG-App-ID" to "124024574287414",
        "X-IG-Capabilities" to "36r/F/8=",
        "X-IG-Connection-Type" to "WiFi",
        "X-IG-App-Locale" to "en-US",
        "X-IG-Device-Locale" to "en-US",
        "X-IG-Mapped-Locale" to "en-US",
        "X-IG-App-Startup-Country" to "US",
        "X-IG-Timezone-Offset" to "0",
        "X-IG-WWW-Claim" to "0",
        "X-Bloks-Version-Id" to "16b7bd25c6c06886d57c4d455265669345a2d96625385b8ee30026ac2dc5ed97",
        "X-FB-Client-IP" to "True",
        "X-FB-Server-Cluster" to "True",
        "X-FB-HTTP-Engine" to "Liger",
        "X-Pigeon-Session-Id" to UUID.randomUUID().toString(),
        "Accept-Language" to "en-US",
        "Accept" to "*/*",
        "Connection" to "keep-alive"
    )

    // Web-session fallback headers (the WebView login produces web cookies).
    private val webHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
        "X-IG-App-ID" to "936619743392459",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-IG-WWW-Claim" to "0",
        "X-Instagram-AJAX" to "1",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "https://www.instagram.com/",
        "Origin" to "https://www.instagram.com"
    )

    @Throws(IOException::class)
    fun fetchCurrentUser(): LoggedInUser {
        val body = executeAuthenticated("accounts/current_user/?edit=true")
        val user = try {
            JSONObject(body).optJSONObject("user")
        } catch (e: Exception) {
            null
        } ?: throw IOException("Session is not logged in")
        return gson.fromJson(user.toString(), LoggedInUser::class.java)
    }

    @Throws(IOException::class)
    fun fetchMediaInfo(mediaId: String): MediaFeed {
        val body = executeAuthenticated("media/$mediaId/info/")
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
     * Entire highlight (carousel of stories) — login required.
     *
     * Highlights are reels addressed as `highlight:<id>`:
     * GET /feed/reels_media/?reel_ids=highlight:<id> returns
     * {"reels": {"highlight:<id>": {"items": [...]}}}.
     * Each story item is returned raw (caller flattens carousel children).
     */
    @Throws(IOException::class)
    fun fetchHighlightItems(highlightId: String): List<com.example.instadl.model.MediaItem> {
        val id = highlightId.removePrefix("highlight:").trim()
        require(id.isNotBlank()) { "empty highlight id" }
        val body = executeAuthenticated("feed/reels_media/?reel_ids=highlight:$id")
        val obj = JSONObject(body)
        val reels = obj.getJSONObject("reels")
        // Exact key first, then any highlight:* key (server may normalize).
        var key: String? = null
        if (reels.has("highlight:$id")) {
            key = "highlight:$id"
        } else {
            val keys = reels.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("highlight:")) {
                    key = k
                    break
                }
            }
        }
        val reel = key?.let { reels.getJSONObject(it) }
            ?: throw IOException("Highlight not found")
        val arr = reel.optJSONArray("items")
            ?: throw IOException("Highlight is empty")
        if (arr.length() == 0) throw IOException("Highlight is empty")
        return (0 until arr.length()).map { i ->
            gson.fromJson(
                arr.getJSONObject(i).toString(),
                com.example.instadl.model.MediaItem::class.java
            )
        }
    }

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

    /**
     * Public post/reel fetch — NO login, NO cookies.
     *
     * GETs the unauthenticated embed page
     * (`https://www.instagram.com/{reel,p}/{shortcode}/embed/`) and extracts
     * direct CDN URLs from the embedded JSON (`video_url` / `display_url`)
     * with `og:video` / `og:image` as fallback. No session cookie is sent.
     *
     * @throws IOException when the media is private, expired, removed, or the
     * page layout changed — caller should fall back to the authenticated
     * /media/{id}/info/ path (or ask the user to log in).
     */
    @Throws(IOException::class)
    fun fetchPublicShortcodeMedia(shortcode: String): List<PublicFile> {
        val code = shortcode.substringBefore('?').substringBefore('#').trim()
        require(code.isNotEmpty()) { "empty shortcode" }
        var lastError: IOException? = null
        // Reel shortcodes also resolve under /p/ and vice versa; try both and
        // keep the LONGEST result — a kind mismatch can return a partial page.
        var best: List<PublicFile> = emptyList()
        for (kind in listOf("reel", "p")) {
            try {
                val files = fetchPublicEmbed(kind, code)
                if (files.size > best.size) best = files
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (best.isNotEmpty()) return best
        throw lastError ?: IOException("Public media not found")
    }

    @Throws(IOException::class)
    fun fetchAuthenticatedShortcodeMedia(shortcode: String): List<PublicFile> {
        val code = shortcode.substringBefore('?').substringBefore('#').trim()
        require(code.isNotEmpty()) { "empty shortcode" }

        var best: List<PublicFile> = emptyList()
        var lastError: IOException? = null
        for (kind in listOf("reel", "p")) {
            for (suffix in listOf("embed/", "embed/captioned/")) {
                try {
                    val files = fetchSessionEmbed(
                        "https://www.instagram.com/$kind/$code/$suffix"
                    )
                    if (files.size > best.size) best = files
                } catch (e: IOException) {
                    lastError = e
                }
            }
        }
        if (best.isNotEmpty()) return best
        throw lastError ?: IOException("Authenticated embed has no media")
    }

    @Throws(IOException::class)
    private fun fetchSessionEmbed(url: String): List<PublicFile> {
        val builder = Request.Builder().url(url).get()
        webHeaders.forEach { (k, v) -> builder.header(k, v) }
        builder.header("Accept", "text/html,*/*")
        builder.header("Sec-Fetch-Dest", "iframe")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", "same-origin")

        client.newCall(builder.build()).execute().use { response ->
            val html = response.body?.string() ?: ""
            if (!response.isSuccessful || html.isBlank()) {
                throw IOException("Embed HTTP ${response.code}")
            }
            val files = parseEmbedHtml(html)
            if (files.isEmpty()) {
                throw IOException("Authenticated embed has no media")
            }
            return files
        }
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
            .header("Sec-Fetch-Dest", "iframe")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "cross-site")
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
        val contextFiles = parseContextJson(html)
        if (contextFiles.isNotEmpty()) return contextFiles

        val out = mutableListOf<PublicFile>()
        val seen = LinkedHashSet<String>()

        // Carousel / sidecar children. Key order inside "node" is NOT fixed
        // (real markup: id, shortcode, dimensions, display_url, …, is_video),
        // so match the node start and read flags/urls from the window.
        val nodeStarts = Regex(""""node"\s*:\s*\{""")
            .findAll(html).toList()
        if (nodeStarts.isNotEmpty()) {
            for (m in nodeStarts) {
                val window = html.substring(m.range.first, minOf(m.range.first + 6000, html.length))
                // Stop at the next sibling node to avoid cross-child leakage.
                val nextNode = Regex(""""node"\s*:\s*\{""").find(window, 1)
                val bounded = if (nextNode != null) window.substring(0, nextNode.range.first) else window
                val isVideo = Regex(""""is_video"\s*:\s*(true|false)""")
                    .find(bounded)?.groupValues?.get(1) == "true"
                val display = Regex(""""display_url"\s*:\s*"([^"]+)"""")
                    .find(bounded)?.groupValues?.get(1)?.let(::unescapeEmbedUrl)
                    ?.takeIf { it.startsWith("http") }
                val video = Regex(""""video_url"\s*:\s*"([^"]+)"""")
                    .find(bounded)?.groupValues?.get(1)?.let(::unescapeEmbedUrl)
                    ?.takeIf { it.startsWith("http") }
                // Skip non-media nodes (e.g. owner/profile blobs that also use "node").
                if (display == null && video == null) continue
                val pick = if (isVideo) video ?: display else display
                if (!pick.isNullOrBlank() && seen.add(pick)) {
                    // A video child may only expose display_url in some markups;
                    // trust is_video flag only when we have a real video url.
                    out.add(PublicFile(pick, isVideo && video != null))
                }
            }
            if (out.isNotEmpty()) return out
        }

        // Fallback when node correlation fails (markup change or single media):
        // combine BOTH videos and images so mixed carousels are never truncated.
        // Single-video posts carry their poster as display_url — suppress that
        // one duplicate (first image) so a lone reel still yields exactly 1 file.
        val videos = Regex(""""video_url"\s*:\s*"([^"]+)"""")
            .findAll(html).map { unescapeEmbedUrl(it.groupValues[1]) }
            .filter { it.startsWith("http") }.distinct().toList()
        val images = Regex(""""display_url"\s*:\s*"([^"]+)"""")
            .findAll(html).map { unescapeEmbedUrl(it.groupValues[1]) }
            .filter { it.startsWith("http") }.distinct().toList()
        if (videos.isNotEmpty() || images.isNotEmpty()) {
            val combined = mutableListOf<PublicFile>()
            val localSeen = LinkedHashSet<String>()
            val imgs = if (videos.size == 1 && images.isNotEmpty()) images.drop(1) else images
            for (v in videos) if (localSeen.add(v)) combined.add(PublicFile(v, true))
            for (img in imgs) if (localSeen.add(img)) combined.add(PublicFile(img, false))
            if (combined.isNotEmpty()) return combined
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

    private fun parseContextJson(html: String): List<PublicFile> {
        val out = mutableListOf<PublicFile>()
        val seen = LinkedHashSet<String>()

        for (rawContext in extractContextJsonStrings(html)) {
            val decoded = try {
                gson.fromJson("\"$rawContext\"", String::class.java)
            } catch (e: Exception) {
                null
            } ?: continue
            val root = try {
                JSONObject(decoded)
            } catch (e: Exception) {
                continue
            }
            val media = root.optJSONObject("gql_data")?.optJSONObject("shortcode_media")
                ?: root.optJSONObject("shortcode_media")
                ?: continue
            val edges = media.optJSONObject("edge_sidecar_to_children")
                ?.optJSONArray("edges")
            val nodes = if (edges != null && edges.length() > 0) {
                (0 until edges.length()).mapNotNull { i ->
                    edges.optJSONObject(i)?.optJSONObject("node")
                }
            } else {
                listOf(media)
            }
            for (node in nodes) appendEmbedFile(node, out, seen)
        }
        return out
    }

    private fun extractContextJsonStrings(html: String): List<String> {
        val marker = "\"contextJSON\":\""
        val values = mutableListOf<String>()
        var searchFrom = 0

        while (true) {
            val start = html.indexOf(marker, searchFrom)
            if (start < 0) break

            val builder = StringBuilder()
            var escaped = false
            var index = start + marker.length
            while (index < html.length) {
                val c = html[index]
                if (escaped) {
                    builder.append(c)
                    escaped = false
                } else if (c == '\\') {
                    builder.append(c)
                    escaped = true
                } else if (c == '"') {
                    break
                } else {
                    builder.append(c)
                }
                index++
            }

            if (index >= html.length) break
            values.add(builder.toString())
            searchFrom = index + 1
        }
        return values
    }

    private fun appendEmbedFile(
        node: JSONObject,
        out: MutableList<PublicFile>,
        seen: MutableSet<String>
    ) {
        val video = node.optString("video_url").takeIf { it.startsWith("http") }
        val display = node.optString("display_url").takeIf { it.startsWith("http") }
        val isVideo = node.optBoolean("is_video", false)
        val pick = if (isVideo) video ?: display else display ?: video
        if (!pick.isNullOrBlank() && seen.add(pick)) {
            out.add(PublicFile(pick, isVideo && video != null))
        }
    }

    private fun unescapeEmbedUrl(s: String): String =
        s.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")

    private fun unescapeHtmlAttr(s: String): String =
        s.replace("&amp;", "&")

    private fun executeAuthenticated(path: String): String {
        val csrf = session.cookies?.let(::extractCsrf) ?: "missing"
        try {
            return executeAndCheck(buildGet("$BASE_URL/$path", csrf))
        } catch (mobileError: IOException) {
            try {
                return executeAndCheck(buildGet("$BASE_URL/$path", csrf, webHeaders))
            } catch (webMobileError: IOException) {
                return executeAndCheck(buildGet("$WEB_BASE_URL/$path", csrf, webHeaders))
            }
        }
    }

    private fun buildGet(
        url: String,
        csrf: String,
        headers: Map<String, String> = baseHeaders
    ): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("X-Instagram-AJAX", "1")
        builder.header("X-CSRFToken", csrf)

        if (headers === baseHeaders) {
            mobileAuthorization()?.let { builder.header("Authorization", it) }
            cookieValue("ds_user_id")?.let {
                builder.header("IG-Intended-User-ID", it)
                builder.header("IG-U-DS-USER-ID", it)
            }
            cookieValue("mid")?.let { builder.header("X-MID", it) }
            cookieValue("ig_did")?.let {
                builder.header("X-IG-Device-ID", it)
                builder.header("X-IG-Family-Device-ID", it)
            }
        }
        return builder.build()
    }

    private fun mobileAuthorization(): String? {
        val userId = cookieValue("ds_user_id") ?: return null
        val sessionId = cookieValue("sessionid") ?: return null
        val payload = gson.toJson(
            linkedMapOf("ds_user_id" to userId, "sessionid" to sessionId)
        )
        val encoded = Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        return "Bearer IGT:2:$encoded"
    }

    private fun cookieValue(name: String): String? {
        val raw = session.cookies ?: return null
        val prefix = "$name="
        return raw.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.substring(prefix.length)
            ?.trim()
            ?.removeSurrounding("\"")
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

    private fun extractCsrf(cookieHeader: String): String {
        val match = Regex("(?:^|; )csrftoken=([^;]*)").find(cookieHeader)
        return match?.groupValues?.get(1) ?: "missing"
    }

    companion object {
        const val BASE_URL = "https://i.instagram.com/api/v1"
        const val WEB_BASE_URL = "https://www.instagram.com/api/v1"
    }
}
