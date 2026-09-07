package com.example.instadl.network

/**
 * Parses Instagram share links into something the private API can fetch.
 *
 * Supported (authenticated session required — caller must be logged in):
 * - Post:  https://www.instagram.com/p/SHORTCODE/...
 * - Reel:  https://www.instagram.com/reel/SHORTCODE/...,
 *           https://www.instagram.com/reels/SHORTCODE/...,
 *           https://www.instagram.com/tv/SHORTCODE/...
 * - Story: https://www.instagram.com/stories/<user>/<STORY_PK>/...
 *           (numeric story media id; highlights `/s/...` are NOT supported)
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
    }

    private val SHORTCODE_PATHS = setOf("p", "reel", "reels", "reelv2", "tv")

    // e.g. https://www.instagram.com/p/DABcdEF12-a/?img_index=2
    private val pathRegex =
        Regex("""instagram\.com(?::\d+)?/([A-Za-z0-9._-]+)/?([A-Za-z0-9_-]+)?/?(\d+)?""")

    fun parse(rawLink: String): Resolved? {
        val link = rawLink.trim().removeSuffix("/")
        if (!link.contains("instagram.com", ignoreCase = true)) return null

        // Stories: /stories/<user>/<numericId>[/...]
        val stories = Regex("""/stories/([^/?#]+)/(\d+)""").find(link)
        if (stories != null) {
            return Resolved.StoryMedia(
                storyId = stories.groupValues[2],
                username = stories.groupValues[1].takeIf { it != "_" }
            )
        }
        // Highlights (/s/<base64> or /stories/highlights/<id>) need a
        // different endpoint family — explicitly unsupported for now.
        if (Regex("""/s/[^/?#]+|/stories/highlights/""").containsMatchIn(link)) return null

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

    private const val alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
}
