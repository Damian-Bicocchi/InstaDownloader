# `model` package — JSON data models

Gson-annotated DTOs for Instagram private API (`i.instagram.com/api/v1`) responses. No logic except best-URL selectors.

## Contents

| File | Type | Purpose |
|------|------|---------|
| `AuthModels.kt` | data classes | Login / challenge / error payloads. |
| `InstagramModels.kt` | data classes | Media feed, image/video candidate payloads. |

## File details

### `AuthModels.kt`
- **Utility:** Deserializes the authenticated profile returned by `InstagramClient.fetchCurrentUser()` after WebView login, plus generic API errors.
- **Classes:**
  - `LoggedInUser(pk, username, fullName)` — persisted to `SessionStore` via `saveSession()` after WebView login.
  - `IgError(message, status)` — generic error body parsed in `executeAndCheck()` when HTTP is not successful.

### `InstagramModels.kt`
- **Utility:** Deserializes media/story feeds and provides best-quality URL pickers used by `MainActivity.downloadAll()`.
- **Classes:**
  - `MediaCandidate(width, height, url)` — single image rendition.
  - `VideoVersion(type, width, height, url)` — single video rendition.
  - `ImageVersion2(candidates)` (`image_versions2`) and `StoryImageVersions(candidates)` — image rendition lists for posts vs. stories.
  - `MediaItem(id, mediaType, imageVersions2, videoVersions, carouselMedia, …)` — one post/story. `mediaType==2` = video, `==1` = photo, `==8` = carousel/album.
    - `bestImageUrl()` → max `width` candidate URL.
    - `bestVideoUrl()` → max `width*height` version URL.
    - `isCarousel()` / `flatten()` — album expands to `carouselMedia` children so every image/video is downloaded.
  - `MediaFeed(items, numResults, moreAvailable, nextMaxId, status)` — container returned by `fetchMediaInfo()`; pagination fields are parsed but not used.
