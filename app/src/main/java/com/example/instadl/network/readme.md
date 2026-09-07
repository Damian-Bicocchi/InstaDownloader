# `network` package — Instagram API client

Thin OkHttp + Gson wrapper over Instagram private API v1 (`https://i.instagram.com/api/v1`).

## Contents

| File | Type | Purpose |
|------|------|---------|
| `InstagramClient.kt` | `class InstagramClient(session)` | Login, logout, user lookup, post feed, stories, single-media info (auth) + public embed scrape (no auth); cookie + CSRF handling. |
| `InstagramLinks.kt` | `object InstagramLinks` | Parses share links (`/p/`, `/reel/`, `/tv/`, `/stories/`) + decodes shortcodes to media ids. |

## File details

### `InstagramClient.kt`
- **Utility:** All network I/O for the app. Constructed in `MainActivity` as `InstagramClient(session)`; blocking (`@Throws(IOException)`) so callers must use `Dispatchers.IO`.
- **Configuration:**
  - `BASE_URL = "https://i.instagram.com/api/v1"` (`companion object`).
  - `baseHeaders` mimics official Android app: `User-Agent: Instagram 269.0.0.18.85 ...`, `X-IG-App-ID`, `X-IG-Capabilities`, `X-FB-HTTP-Engine: Liger`, etc.
  - `deviceUuid` random per process; `OkHttpClient` with `followRedirects/SslRedirects`.
- **Key API:**
  - `login(username, password: CharArray): LoginResponse` — `POST /accounts/login/`; transient `String` copy scoped to call, caller array wiped on entry; status-code-only `IOException`, never logs secret.
  - `logout(): Boolean` — best-effort `POST /accounts/logout/` with `FormBody(_csrftoken, guid, device_id)` + `Cookie`/`X-CSRFToken`; returns `isSuccessful`. Caller (`MainActivity.performLogout()`) must still wipe `SessionStore` on `false`.
  - `fetchUserByUsername(username): UserInfo` — `GET /users/web_profile_info/?username=...&is_sandbox=true`, unwraps `data.user` via `JSONObject` then Gson.
  - `fetchUserMediaPk(pk, maxId?): MediaFeed` — `GET /feed/user/<pk>/?count=33[&max_id=]` → `MediaFeed`.
  - `fetchStories(userPk): MediaFeed` — `GET /feed/reels_media/?reel_ids=<pk>`, unwraps `reels.<pk>` → `MediaFeed`.
  - `fetchMediaInfo(mediaId): MediaFeed` — authenticated `GET /media/<id>/info/`; parses `items` **array** (fixed) for single post/reel/story.
  - `fetchMediaItem(mediaId): MediaItem` + `fetchMediaByShortcode(shortcode): MediaItem` — authenticated single-item fetch (private fallback).
  - `fetchPublicShortcodeMedia(shortcode): List<PublicFile>` — **no login**: GETs `/{reel,p}/<shortcode>/embed/` with browser UA and zero cookies, parses `video_url`/`display_url` per carousel `node` + `og:` fallback. Throws on private/removed/changed markup.
  - `PublicFile(url, isVideo)` — direct CDN file from the public path.
  - `isSessionValid(): Boolean` — delegates to `session.isLoggedIn`.

### `InstagramLinks.kt`
- **Utility:** Pure parser, no network. `parse(link): Resolved?` → `ShortcodeMedia(shortcode, mediaId)` for `/p|reel|reels|tv/` or `StoryMedia(storyId, username)` for `/stories/<user>/<id>`. Returns `null` for highlights (`/s/…`) and non-Instagram URLs.
- `shortcodeToMediaId(shortcode)` — base64url decode (`A-Za-z0-9-_`), offline.
- **Helpers:**
  - `buildGet(url)` — adds `baseHeaders` + `Cookie` from `SessionStore` + `X-CSRFToken` (extracted or `missing`) + `X-Instagram-AJAX: 1`.
  - `executeAndCheck(request): String` — throws `IOException` with `IgError.message` or `HTTP <code>` on failure.
  - `saveCookies(setCookieHeader)` — naive `;`/`=` split → `"; "`-joined string → `SessionStore.saveSession()`.
  - `extractCsrf(cookieHeader)` — regex `csrftoken=([^;]*)`.
- **Warning:** As noted in-file, automating these endpoints violates Instagram ToS and risks restrictions/bans; use only on owned accounts for personal/educational use.
