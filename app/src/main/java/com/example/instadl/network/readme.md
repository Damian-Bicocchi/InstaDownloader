# `network` package — Instagram API client

Thin OkHttp + Gson wrapper over Instagram private API v1 (`https://i.instagram.com/api/v1`).

## Contents

| File | Type | Purpose |
|------|------|---------|
| `InstagramClient.kt` | `class InstagramClient(session)` | Login, user lookup, post feed, stories, media info; cookie + CSRF handling. |

## File details

### `InstagramClient.kt`
- **Utility:** All network I/O for the app. Constructed in `MainActivity` as `InstagramClient(session)`; blocking (`@Throws(IOException)`) so callers must use `Dispatchers.IO`.
- **Configuration:**
  - `BASE_URL = "https://i.instagram.com/api/v1"` (`companion object`).
  - `baseHeaders` mimics official Android app: `User-Agent: Instagram 269.0.0.18.85 ...`, `X-IG-App-ID`, `X-IG-Capabilities`, `X-FB-HTTP-Engine: Liger`, etc.
  - `deviceUuid` random per process; `OkHttpClient` with `followRedirects/SslRedirects`.
- **Key API:**
  - `login(username, password): LoginResponse` — `POST /accounts/login/` with `FormBody` (`phone_id, adid, guid, device_id, password, _csrftoken=missing, login_attempt_count`). On body containing `"status":"ok"` / `"logged_in_user"`, calls `saveCookies(Set-Cookie)` then Gson-parses to `LoginResponse`.
  - `logout(): Boolean` — best-effort `POST /accounts/logout/` with `FormBody(_csrftoken, guid, device_id)` + `Cookie`/`X-CSRFToken`; returns `isSuccessful`. Caller (`MainActivity.performLogout()`) must still wipe `SessionStore` on `false`.
  - `fetchUserByUsername(username): UserInfo` — `GET /users/web_profile_info/?username=...&is_sandbox=true`, unwraps `data.user` via `JSONObject` then Gson.
  - `fetchUserMediaPk(pk, maxId?): MediaFeed` — `GET /feed/user/<pk>/?count=33[&max_id=]` → `MediaFeed`.
  - `fetchStories(userPk): MediaFeed` — `GET /feed/reels_media/?reel_ids=<pk>`, unwraps `reels.<pk>` → `MediaFeed`.
  - `fetchMediaInfo(mediaId): MediaFeed` — `GET /media/<id>/info/` (currently unused by UI).
  - `isSessionValid(): Boolean` — delegates to `session.isLoggedIn`.
- **Helpers:**
  - `buildGet(url)` — adds `baseHeaders` + `Cookie` from `SessionStore` + `X-CSRFToken` (extracted or `missing`) + `X-Instagram-AJAX: 1`.
  - `executeAndCheck(request): String` — throws `IOException` with `IgError.message` or `HTTP <code>` on failure.
  - `saveCookies(setCookieHeader)` — naive `;`/`=` split → `"; "`-joined string → `SessionStore.saveSession()`.
  - `extractCsrf(cookieHeader)` — regex `csrftoken=([^;]*)`.
- **Warning:** As noted in-file, automating these endpoints violates Instagram ToS and risks restrictions/bans; use only on owned accounts for personal/educational use.
