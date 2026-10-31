# com.example.instadl (app root)

Entry point package for the Instagram Downloader app.

## Contents

| File | Type | Purpose |
|------|------|---------|
| `MainActivity.kt` | `AppCompatActivity` | Main UI + orchestration: login state, direct-link/story/highlight downloads, share intent. |
| `WebLoginActivity.kt` | `AppCompatActivity` | Instagram WebView login page; captures session cookies into encrypted storage. |

## File details

### `MainActivity.kt`
- **Utility:** Single-screen controller. Owns `ActivityMainBinding`, `SessionStore`, and `InstagramClient` instances.
- **Key responsibilities:**
  - `onCreate()`: sets `FLAG_SECURE`, inflates view binding, wires `btnLogin / btnLogout / btnDownloadLink / clear-link`, calls `updateLoginUi()` and `requestStoragePermissions()`.
  - `performLogin()`: launches `WebLoginActivity`, where the user logs in on Instagram's own page; the app never receives the password. `finishWebLogin()` calls `fetchCurrentUser()` to validate the session and store the username/full name.
  - `downloadFromLink(link)`: public-first — `fetchPublicShortcodeMedia()` with no login; private fallback `fetchMediaByShortcode()` when logged in; stories/highlights always require login (`downloadStoryLink()` / `downloadHighlight()` saves the entire highlight carousel). Handles ACTION_SEND via `handleShareIntent()` + `downloadPublicFiles()`.
  - `performLogout()`: calls `InstagramClient.logout()` on `Dispatchers.IO` best-effort, then always `SessionStore.clear()`, clears WebView cookies, and calls `updateLoginUi()`; reports `Logged out` vs `Logged out locally`.
  - `clearSession()`: always-available local wipe of encrypted session + WebView cookies.
  - Startup expiry: `SessionStore.clearIfExpired()` removes sessions older than 30 days.
  - `downloadAll(items, prefix)`: picks `bestVideoUrl()` for `mediaType == 2` else `bestImageUrl()`, names files `${prefix}_${index}.mp4/.jpg`, delegates to `DownloadManager.download()`.
  - Helpers: `updateLoginUi()`, `setLoading()`, `setStatus()`, `requestStoragePermissions()` (only `WRITE_EXTERNAL_STORAGE` on API ≤ 28), `toast()`.
- **Dependencies:** `auth.SessionStore` for persistence, `network.InstagramClient` for API, `download.DownloadManager` for file I/O, `model.MediaItem` for URL selection, coroutines for Main/IO switching.
