# `download` package — media file saving

Downloads remote image/video bytes and saves them to shared storage (Gallery/Downloads).

## Contents

| File | Type | Purpose |
|------|------|---------|
| `DownloadManager.kt` | `object DownloadManager` | Singleton HTTP downloader + MediaStore / legacy filesystem writer. |

## File details

### `DownloadManager.kt`
- **Utility:** Called by `MainActivity.downloadAll()` for every post/story item. Takes a CDN `url` + `fileName`, returns display path or throws.
- **Key API:**
  - `download(context, url, fileName, isVideo): String` — executes OkHttp `GET`, reads `body.bytes()`, sanitizes name, branches on SDK version.
- **Internals:**
  - `client: OkHttpClient` — 30s connect / 60s read timeout, interceptor forcing desktop `User-Agent` (`Mozilla/5.0 ...`) to avoid Instagram CDN blocks.
  - `saveToMediaStore(...)` (API ≥ 29 / `Q`): inserts into `MediaStore.Images|Video.Media` (`VOLUME_EXTERNAL_PRIMARY`), `RELATIVE_PATH = Pictures/Instagram/` for images and `Movies/Instagram/` for videos, `MIME_TYPE = image/jpeg | video/mp4`, uses `IS_PENDING=1 → write → IS_PENDING=0` protocol.
  - `saveToLegacyStorage(...)` (API < 29): writes to `Pictures/Instagram/` via `FileOutputStream`, creates dir with `mkdirs()`.
  - `sanitize(name)`: replaces `[^A-Za-z0-9._-]` with `_`, falls back to `insta_<timestamp>` if blank — prevents path traversal / illegal filenames.
- **Error handling:** Throws on non-2xx (`Download failed HTTP <code>`), null body (`Empty response body`), failed `insert` / `openOutputStream`.
- **Note:** `mime` vs `extension` params are currently both MIME strings; `relPath` is `Instagram/<safeName>` but only basename is used for `DISPLAY_NAME`.
