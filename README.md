# Instagram Downloader (Android)

A native Android (Kotlin) app that logs into Instagram and downloads **photos**, **videos** and **stories** from any user.

Built and verified — you can grab a ready-to-install APK from the project root:

| File | Description |
|------|-------------|
| `InstagramDownloader-debug.apk` | Debug build (signed with debug keystore) |
| `InstagramDownloader-release.apk` | Release build (signed with debug keystore) |

## How it works

The app uses **Instagram's private API v1** endpoints (`i.instagram.com/api/v1/...`), authenticated by logging in with an Instagram account. Its flow:

1. **Login** — `POST /accounts/login/` with the account credentials. Session cookies (including `csrftoken`) are stored locally in encrypted `SharedPreferences` (`EncryptedSharedPreferences`, Keystore-backed).
2. **Resolve user** — `GET /users/web_profile_info/?username=...` turns a username into a numeric user id (`pk`).
3. **Posts** — `GET /feed/user/{pk}/?count=33` returns recent media; each item yields best-quality image (`image_versions2.candidates`) or video (`video_versions`) URLs.
4. **Stories** — `GET /feed/reels_media/?reel_ids={pk}` returns the currently-active story items.
5. **Download** — Media is fetched over a separate OkHttp client and written to shared storage:
   - Android 10+ (API 29+): via `MediaStore` into `Downloads/Instagram/`
   - Android 9 and below: legacy `WRITE_EXTERNAL_STORAGE` into `Pictures/Instagram/`

### App UI — what you actually see

This is **not** an Instagram UI wrapper / WebView. It is a small native screen with two cards (`app/src/main/res/layout/activity_main.xml`, wired in `MainActivity.kt`):

- **Account card:** `Username` + `Password` fields and `Login` / `Logout` buttons. Login runs `InstagramClient.login()` on a background coroutine; logout best-effort POSTs `accounts/logout/` then always wipes the encrypted session.
- **Download from user card:** one `Instagram username` field (`etTarget` — plain username, **not** a URL/link) plus two buttons: `Download Posts` → `downloadPosts()` and `Download Stories` → `downloadStories()`. Status text + progress bar report `Resolving @user… / Fetching… / Downloading N item(s)… / Saved X of N`.

Files are saved as `<username>_<index>.jpg/.mp4` (stories: `<username>_story_<index>...`) via `DownloadManager.download()`.

### FAQ — common misconceptions

**1. Is the app a wrapper around the Instagram UI with download buttons injected?**

No. There is no `WebView`, no Custom Tab, no embedded Instagram feed. You never browse Instagram inside the app. You type a username, the app talks directly to the private JSON API with spoofed app headers (`Instagram 269.0.0.18.85…`, `X-IG-App-ID`, … in `InstagramClient.kt:baseHeaders`), parses the JSON into `model/` DTOs, and downloads the CDN (`bestImageUrl()` / `bestVideoUrl()`) files.

**2. If I paste a highlight / story link, does it download all highlighted stories or just the current/first one?**

Neither — **link input and highlights are not supported at all.** The target field accepts only a username (e.g. `nasa`, not `https://www.instagram.com/s/aGlnaGxpZ2h0Oj…` or `.../stories/highlights/...`). `downloadStories()` calls `fetchStories(pk)` → `GET /feed/reels_media/?reel_ids={pk}`, which returns **only currently-active (unexpired, last-24h) stories**. `downloadAll()` then loops over **all** items in that feed (not just the first) and saves each as its own file. Expired stories, highlights tray, and highlight reels need different endpoints (`highlight_reels` / `reels_tray`) that this codebase does not call — those links will just fail/be treated as a bad username.

**3. If I download a post with multiple images and/or videos (carousel/album), does it download them all?**

No — **at most one file per post, and carousels are mostly skipped.** `MediaItem` (`model/InstagramModels.kt`) only models `image_versions2` + `video_versions` with `media_type == 1` (photo) / `2` (video). It has **no `carousel_media` field** (`media_type == 8`), so for an album `bestImageUrl()` / `bestVideoUrl()` is `null` and `downloadAll()` skips that item. Single-photo/video posts download the highest-resolution rendition. Related limits: only the **first page** (`?count=33`, `maxId` is never paged via `next_max_id`/`more_available`) is fetched, so accounts with >33 posts need repeated/paginated runs (not yet implemented).

## Project structure

```
app/src/main/java/com/example/instadl/
├── MainActivity.kt            # UI: login + download posts/stories
├── auth/
│   └── SessionStore.kt        # Cookie/session persistence
├── network/
│   └── InstagramClient.kt     # Instagram private API client + signing headers
├── model/
│   ├── InstagramModels.kt     # Media/feed/user models
│   └── AuthModels.kt          # Login/error models
└── download/
    └── DownloadManager.kt     # MediaStore / legacy storage writers
```

Key files:
- `InstagramClient.kt` — all API calls, request headers, cookie handling
- `DownloadManager.kt` — saves media to the device
- `MainActivity.kt` — UI wiring and coroutine orchestration

## Building from source

### Option A — Docker (recommended, no local toolchain needed)

```bash
# 1. Build the toolchain image (Android SDK + Gradle 8.7)
docker build -f Dockerfile.build -t insta-android-build .

# 2. Build the APKs
docker run --rm -v "$PWD":/work -v gradle-cache:/root/.gradle \
    --workdir /work insta-android-build gradle assembleDebug assembleRelease
# outputs: app/build/outputs/apk/{debug,release}/*.apk
```

### Option B — Local Android Studio / Gradle

Open the project in Android Studio, or with a JDK 17 + Android SDK + Gradle 8.7 installed:

```bash
# point the SDK at your install
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleRelease
```

## Building a production-signed APK

`app/build.gradle.kts` currently signs `release` with the **debug keystore** so the APK is installable out of the box. For Play Store distribution, replace that with your own `signingConfig`.

## ⚠️ Important legal & safety warnings

- **Violates Instagram's Terms of Service.** Logging in and using Instagram's private/internal API for automated requests is against their ToS. This can lead to the logged-in account being **temporarily restricted or permanently disabled**, and Instagram's endpoints/code change often (the app may stop working without notice).
- **Use at your own risk.** Prefer a **disposable account** you don't care about, not your main one.
- **Respect platforms' ToS and applicable law.** Only download content you have the right to use.
- This project is for **personal / educational use**.
