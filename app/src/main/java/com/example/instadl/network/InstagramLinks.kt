package com.example.instadl.network

import android.util.Base64
import java.net.URLDecoder

/**
 * Parses Instagram share links into something the private API can fetch.
 *
 * Supported:
 * - Post / reel / tv (public → no login via embed; private → logged-in
 *   fallback): https://www.instagram.com/p/SHORTCODE/…,
 *   /reel/SHORTCODE/…, /reels/SHORTCODE/…, /tv/SHORTCODE/…
 * - Story (login required): https://www.instagram.com/stories/<user>/<STORY_PK>/…
 * - Highlight = carousel of stories (login required):
 *   https://www.instagram.com/stories/highlights/<HIGHLIGHT_ID>/… or
 *   https://www.instagram.com/s/<TOKEN>[?...] (app share token, base64 of
 *   "highlight:<id>" or carrying ?story_media_id=…).
 *
 * Shortcodes are decoded locally (base64-ish) to numeric media ids, so no
 * extra network hop is needed before calling /media/{id}/info/.
 */
object InstagramLinks {

    sealed interface Resolved {
        /** Single post / reel / tv — fetch /media/{mediaId}/info/. */
        data class ShortcodeMedia(val shortcode: String, val mediaId: String) : Resolved

        /** Single story item — fetch /media/{storyId}/info/. */
        data class StoryMedia(val storyId: String, val username: String?) : Resolved

        /** Entire highlight (carousel of stories) — fetch reels_media highlight:. */
        data class Highlight(val highlightId: String) : Resolved
    }

    private val SHORTCODE_PATHS = setOf("p", "reel", "reels", "reelv2", "tv")

    // e.g. https://www.instagram.com/p/DABcdEF12-a/?img_index=2
    private val pathRegex =
        Regex("""instagram\.com(?::\d+)?/([A-Za-z0-9._-]+)/?([A-Za-z0-9_-]+)?/?(\d+)?""")

    fun parse(rawLink: String): Resolved? {
        val link = rawLink.trim().removeSuffix("/")
        if (!link.contains("instagram.com", ignoreCase = true)) return null

        // Highlights FIRST — /stories/highlights/<id> would otherwise match
        // the story pattern below with username="highlights".
        Regex("""/stories/highlights/(\d+)""").find(link)?.let {
            return Resolved.Highlight(highlightId = it.groupValues[1])
        }
        // App share token: /s/<base64 of "highlight:<id>" or story ref>[?...]
        Regex("""/s/([^/?#]+)""").find(link)?.let { m ->
            val token = m.groupValues[1]
            decodeHighlightToken(token)?.let { return Resolved.Highlight(it) }
            // Fallback: a /s/ story share carries ?story_media_id=<pk>_<user>
            // — downloads that single story (best we can do without highlight id).
            Regex("""[?&]story_media_id=(\d+)""").find(link)?.let { sm ->
                return Resolved.StoryMedia(storyId = sm.groupValues[1], username = null)
            }
            return null
        }

        // Stories: /stories/<user>/<numericId>[/...]
        val stories = Regex("""/stories/([^/?#]+)/(\d+)""").find(link)
        if (stories != null) {
            return Resolved.StoryMedia(
                storyId = stories.groupValues[2],
                username = stories.groupValues[1].takeIf { it != "_" }
            )
        }

        val m = pathRegex.find(link) ?: return null
        val seg1 = m.groupValues[1].lowercase()
        if (seg1 in SHORTCODE_PATHS) {
            val shortcode = m.groupValues[2].substringBefore('?').trim()
            if (shortcode.isBlank()) return null
            val mediaId = try {
                shortcodeToMediaId(shortcode)
            } catch (e: Exception) {
                return null
            }
            return Resolved.ShortcodeMedia(shortcode, mediaId)
        }
        return null
    }

    /**
     * Instagram shortcode alphabet (same as RFC 4648 url-safe base64
     * without padding): A-Z a-z 0-9 - _ .
     * mediaId = base64(shortcode). Leading 'A' chars are zero padding.
     */
    fun shortcodeToMediaId(shortcode: String): String {
        val code = shortcode.substringBefore('?').substringBefore('#').trim()
        require(code.isNotEmpty()) { "empty shortcode" }
        var id = 0L
        for (c in code) {
            val v = alphabet.indexOf(c)
            require(v >= 0) { "bad shortcode char: $c" }
            id = id * 64 + v
        }
        require(id > 0) { "unresolvable shortcode" }
        return id.toString()
    }

    /**
     * Decode an /s/ share token to a numeric highlight id.
     * Payloads look like base64("highlight:179…") or carry the id plainly.
     * Returns null when the token holds no highlight reference.
     */
    fun decodeHighlightToken(rawToken: String): String? {
        val token = try {
            URLDecoder.decode(rawToken, "UTF-8")
        } catch (e: Exception) {
            rawToken
        }.trim()
        if (token.isBlank()) return null
        if (token.all { it.isDigit() } && token.length >= 8) return token
        // Try standard + url-safe base64 (padding may be omitted in links).
        val variants = listOf(token, token.replace('-', '+').replace('_', '/'))
        for (v in variants) {
            val padded = v + "=".repeat((4 - v.length % 4) % 4)
            try {
                val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                Regex("""highlight:(\d+)""").find(decoded)?.let { return it.groupValues[1] }
                Regex("""(\d{8,})""").find(decoded)?.let { return it.groupValues[1] }
            } catch (e: Exception) {
                // try next variant
            }
        }
        Regex("""highlight:(\d+)""").find(token)?.let { return it.groupValues[1] }
        return null
    }

    private const val alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
}
