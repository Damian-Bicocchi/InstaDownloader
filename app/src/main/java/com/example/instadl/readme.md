# com.example.instadl (app root)

Entry point package for the Instagram Downloader app.

## Contents

| File | Type | Purpose |
|------|------|---------|
| `MainActivity.kt` | `AppCompatActivity` | Main UI + orchestration: login, resolve target user, fetch posts/stories, trigger downloads. |

## File details

### `MainActivity.kt`
- **Utility:** Single-screen controller. Owns `ActivityMainBinding`, `SessionStore`, and `InstagramClient` instances.
- **Key responsibilities:**
  - `onCreate()`: inflates view binding, wires `btnLogin / btnLogout / btnDownloadPosts / btnDownloadStories`, calls `updateLoginUi()` and `requestStoragePermissions()`.
  - `performLogin()`: reads `etUsername/etPassword`, calls `InstagramClient.login()` on `Dispatchers.IO`, saves session via `SessionStore.saveSession()`, handles `twoFactorRequired` / checkpoint failures.
  - `downloadPosts()`: `fetchUserByUsername(target)` → `fetchUserMediaPk(pk)` → `downloadAll(items, target)`.
  - `downloadStories()`: `fetchUserByUsername(target)` → `fetchStories(pk)` → `downloadAll(items, target_story)`.
  - `downloadAll(items, prefix)`: picks `bestVideoUrl()` for `mediaType == 2` else `bestImageUrl()`, names files `${prefix}_${index}.mp4/.jpg`, delegates to `DownloadManager.download()`.
  - Helpers: `updateLoginUi()`, `setLoading()`, `setStatus()`, `requireLoggedIn()`, `requestStoragePermissions()` (only `WRITE_EXTERNAL_STORAGE` on API ≤ 28), `toast()`.
- **Dependencies:** `auth.SessionStore` for persistence, `network.InstagramClient` for API, `download.DownloadManager` for file I/O, `model.MediaItem` for URL selection, coroutines for Main/IO switching.
