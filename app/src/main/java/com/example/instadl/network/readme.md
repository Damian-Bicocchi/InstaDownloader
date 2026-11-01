# `network` package — Instagram API client

Thin OkHttp + Gson wrapper over Instagram private API v1 (`https://i.instagram.com/api/v1`).

## Contents

| File | Type | Purpose |
|------|------|---------|
| `InstagramClient.kt` | `class InstagramClient(session)` | Session validation, logout, single-media/story/highlight info (auth) + public embed scrape (no auth); cookie + CSRF handling. |
| `InstagramCookieJar.kt` | `class InstagramCookieJar(session)` | OkHttp `CookieJar` backed by encrypted `SessionStore`; scopes cookies to `i.instagram.com` and merges `Set-Cookie` updates by name. |
| `InstagramLinks.kt` | `object InstagramLinks` | Parses share links (`/p/`, `/reel/`, `/tv/`, `/stories/`) + decodes shortcodes to media ids. |

## File details

### `InstagramClient.kt`
- **Utility:** All network I/O for the app. Constructed in `MainActivity` as `InstagramClient(session)`; blocking (`@Throws(IOException)`) so callers must use `Dispatchers.IO`.
- **Configuration:**
  - `BASE_URL = "https://i.instagram.com/api/v1"` (`companion object`).
  - `baseHeaders` mirror Instaloader's iPad client (`Instagram 361…`, `X-IG-App-ID: 124024574287414`, `X-IG-Capabilities`, `X-FB-HTTP-Engine: Liger`, …). Requests also carry an `Authorization: Bearer IGT:2:<base64(ds_user_id + sessionid)>` token derived from the captured session.
  - `deviceUuid` random per process; authenticated `OkHttpClient` uses `InstagramCookieJar` and disables redirects (`followRedirects=false`, `followSslRedirects=false`). The public embed client follows redirects but sends no cookies.
- **Key API:**
  - `fetchCurrentUser(): LoggedInUser` — authenticated `GET /accounts/current_user/?edit=true`; validates the captured WebView session and returns `pk`/`username`/`fullName`.
  - `logout(): Boolean` — best-effort `POST /accounts/logout/` with `FormBody(_csrftoken, guid, device_id)` + `X-CSRFToken`; cookies come from `InstagramCookieJar`. Returns `isSuccessful`. Caller (`MainActivity.performLogout()`) must still wipe `SessionStore` on `false`.
  - `fetchMediaInfo(mediaId): MediaFeed` — authenticated `GET /media/<id>/info/`; parses `items` **array** (fixed) for single post/reel/story.
  - `fetchMediaItem(mediaId): MediaItem` + `fetchMediaByShortcode(shortcode): MediaItem` — authenticated single-item fetch (private fallback).
  - `fetchHighlightItems(highlightId): List<MediaItem>` — authenticated `GET /feed/reels_media/?reel_ids=highlight:<id>`; unwraps `reels["highlight:<id>"]items`, the whole story carousel.
  - `fetchPublicShortcodeMedia(shortcode): List<PublicFile>` — **no login, downloads ALL media of the post**: GETs `/{reel,p}/<shortcode>/embed/` (both, keeps longest) with browser UA, `Sec-Fetch-*` headers and zero cookies. `parseEmbedHtml()` first decodes the escaped `contextJSON` (`gql_data.shortcode_media`, including `edge_sidecar_to_children`), then falls back to order-independent `node` blocks and plain `video_url`/`display_url`/`og:*` markup. Throws on private/removed/changed markup.
  - `PublicFile(url, isVideo)` — direct CDN file from the public path.
  - `isSessionValid(): Boolean` — delegates to `session.isLoggedIn`.

### `InstagramLinks.kt`
- **Utility:** Pure parser, no network. `parse(link): Resolved?` → `ShortcodeMedia(shortcode, mediaId)` for `/p|reel|reels|tv/`, `StoryMedia(storyId, username)` for `/stories/<user>/<id>`, `Highlight(highlightId)` for `/stories/highlights/<id>/` or `/s/<token>` (base64 `highlight:<id>`, else `?story_media_id=` single-story fallback). Highlights checked before stories to avoid `username="highlights"` misparse.
- `shortcodeToMediaId(shortcode)` — base64url decode (`A-Za-z0-9-_`), offline. `decodeHighlightToken(token)` — URL/base64 decode → numeric id.
- **Helpers:**
  - `buildGet(url)` — adds `baseHeaders`, `X-CSRFToken`, the derived mobile `Authorization` token and device headers; cookies are supplied by `InstagramCookieJar`.
  - `executeAndCheck(request): String` — throws `IOException` with `IgError.message` or `HTTP <code>` on failure.
  - `extractCsrf(cookieHeader)` — regex `csrftoken=([^;]*)`.
- **Warning:** As noted in-file, automating these endpoints violates Instagram ToS and risks restrictions/bans; use only on owned accounts for personal/educational use.
