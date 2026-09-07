# `model` package — JSON data models

Gson-annotated DTOs for Instagram private API (`i.instagram.com/api/v1`) responses. No logic except best-URL selectors.

## Contents

| File | Type | Purpose |
|------|------|---------|
| `AuthModels.kt` | data classes | Login / challenge / error payloads. |
| `InstagramModels.kt` | data classes | User, media feed, image/video candidate payloads. |

## File details

### `AuthModels.kt`
- **Utility:** Deserializes `POST /accounts/login/` result in `InstagramClient.login()`, consumed by `MainActivity.performLogin()`.
- **Classes:**
  - `LoginResponse(status, message, loggedInUser, errorType, twoFactorRequired, checkpointRequired)` — `status=="ok"` + non-null `loggedInUser` means success; otherwise `message` shown in UI. `twoFactorRequired==true` → UI notes "2FA is not supported".
  - `LoggedInUser(pk, username, fullName)` — persisted to `SessionStore` via `saveSession()`.
  - `Checkpoint(challenge)` + `Challenge(url, apiPath)` — challenge/checkpoint flow info (parsed but not acted on).
  - `IgError(message, status)` — generic error body parsed in `executeAndCheck()` when HTTP is not successful.

### `InstagramModels.kt`
- **Utility:** Deserializes user lookup, post feed, and stories feed; provides best-quality URL pickers used by `MainActivity.downloadAll()`.
- **Classes:**
  - `MediaCandidate(width, height, url)` — single image rendition.
  - `VideoVersion(type, width, height, url)` — single video rendition.
  - `ImageVersion2(candidates)` (`image_versions2`) and `StoryImageVersions(candidates)` — image rendition lists for posts vs. stories.
  - `MediaItem(id, mediaType, imageVersions2, videoVersions, originalWidth/Height, videoDuration, takenAt)` — one post/story. `mediaType==2` = video, `==1` = photo.
    - `bestImageUrl()` → max `width` candidate URL.
    - `bestVideoUrl()` → max `width*height` version URL.
  - `UserInfo(pk, username, fullName, profilePicUrl, isPrivate)` — result of `fetchUserByUsername()`; `pk` is required for subsequent feed calls.
  - `MediaFeed(items, numResults, moreAvailable, nextMaxId, status)` — paginated container for `fetchUserMediaPk()` / `fetchStories()`; pagination fields parsed but single-page fetch only.
