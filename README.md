# Instagram Downloader (Android)

A native Android (Kotlin) app that logs into Instagram and downloads **photos**, **videos** and **stories** from any user.

Built and verified — you can grab a ready-to-install APK from the project root:

| File | Description |
|------|-------------|
| `InstagramDownloader-debug.apk` | Debug build (signed with debug keystore) |
| `InstagramDownloader-release.apk` | Release build (signed with debug keystore) |

## How it works

The app uses **Instagram's private API v1** endpoints (`i.instagram.com/api/v1/...`), authenticated by logging in with an Instagram account. Its flow:

1. **Login** — `POST /accounts/login/` with the account credentials. Session cookies (including `csrftoken`) are stored locally in encrypted `SharedPreferences` (`EncryptedSharedPreferences`, Keystore-backed). Only needed for private media, stories, and username-based browsing.
2. **Resolve user** — `GET /users/web_profile_info/?username=...` turns a username into a numeric user id (`pk`). Login required.
3. **Posts** — `GET /feed/user/{pk}/?count=33` returns recent media; each item yields best-quality image (`image_versions2.candidates`) or video (`video_versions`) URLs. Carousels expand via `carousel_media` → all children downloaded. Login required.
4. **Stories** — `GET /feed/reels_media/?reel_ids={pk}` returns the currently-active story items. Login required (stories are never public).
5. **Direct link** — paste/share a reel, post, story or highlight link:
   - **Public post/reel → NO login needed.** `InstagramClient.fetchPublicShortcodeMedia()` GETs the unauthenticated embed page (`/{reel,p}/{shortcode}/embed/`, browser UA, no cookies) and extracts CDN URLs from embedded JSON (`video_url` / `display_url`, per carousel `node`) with `og:video` / `og:image` fallback.
   - **Private post/reel → login fallback.** If the embed has no media, and you are logged in, the app decodes the shortcode locally to a media id (`InstagramLinks`) and calls authenticated `GET /media/{id}/info/`, saving every carousel child.
   - **Story link (`/stories/<user>/<id>`) → login required**, fetched via `GET /media/{storyId}/info/`.
   - **Highlight link (`/stories/highlights/<id>/` or app share `/s/<token>[?story_media_id=…]`) → login required.** Token is base64-decoded to `highlight:<id>`; then `GET /feed/reels_media/?reel_ids=highlight:<id>` returns the whole story carousel and **every** story (flattened) is saved as `highlight_<id>_N`.
6. **Download** — Media is fetched over a separate OkHttp client and written to shared storage:
   - Android 10+ (API 29+): via `MediaStore` into `Downloads/Instagram/`
   - Android 9 and below: legacy `WRITE_EXTERNAL_STORAGE` into `Pictures/Instagram/`

### App UI — what you actually see

This is **not** an Instagram UI wrapper / WebView. It is a small native screen with two cards (`app/src/main/res/layout/activity_main.xml`, wired in `MainActivity.kt`):

- **Account card:** `Username` + `Password` fields and `Login` / `Logout` buttons. Login runs `InstagramClient.login()` on a background coroutine; logout best-effort POSTs `accounts/logout/` then always wipes the encrypted session.
- **Download from user card:** one `Instagram username` field (`etTarget`) plus two buttons: `Download Posts` → `downloadPosts()` and `Download Stories` → `downloadStories()`. Below that, a `Paste reel / post / story link` field (`etLink`) + `Download Link` button → `downloadFromLink()`. You can also **Share** a link from Instagram / browser to this app (ACTION_SEND) — it pre-fills the link field and auto-downloads when logged in. Status text + progress bar report `Resolving… / Downloading N file(s)… / Saved X of N`.

Files are saved as `<username>_<index>.jpg/.mp4` (stories: `<username>_story_<index>...`, links: `link_<shortcode>_<index>...` / `story_<id>_...`) via `DownloadManager.download()`.

### FAQ — common misconceptions

**1. Is the app a wrapper around the Instagram UI with download buttons injected?**

No. There is no `WebView`, no Custom Tab, no embedded Instagram feed. You never browse Instagram inside the app. You type a username, the app talks directly to the private JSON API with spoofed app headers (`Instagram 269.0.0.18.85…`, `X-IG-App-ID`, … in `InstagramClient.kt:baseHeaders`), parses the JSON into `model/` DTOs, and downloads the CDN (`bestImageUrl()` / `bestVideoUrl()`) files.

**2. If I send a highlight / story / reel / post link, what gets downloaded?**

Paste (or Share) the link into the **link field**:

- **Public reel/post → no login needed.** The app scrapes the public embed page and downloads **that exact item, ALL files** (every carousel photo/video; mixed albums correlate each child, single reels yield exactly the video).
- **Private reel/post → login needed.** If the embed is empty, log in and retry — the app falls back to the authenticated `GET /media/{id}/info/` path.
- **Story link (`/stories/<user>/<id>`) → login always required** (stories are never public).
- **Highlight link → login required, downloads the ENTIRE highlight.** Accepts `/stories/highlights/<id>/` and app shares `/s/<token>` (base64 `highlight:<id>`, else `?story_media_id=` single-story fallback). Every story in the highlight is saved.

**3. If a post has multiple images and/or videos (carousel/album), does it download them all?**

Yes — **for single-link downloads and for user downloads.** `MediaItem.carousel_media` is now modeled and `flatten()` expands albums, so `downloadAll()` saves every child (`link_<shortcode>_<index>.jpg/.mp4`). Single-photo/video posts download the highest-resolution rendition. Remaining limit for username-based post download: only the **first page** (`?count=33`, no `next_max_id` pagination yet).

## Project structure

```
app/src/main/java/com/example/instadl/
├── MainActivity.kt            # UI: login + download posts/stories/link + share intent
├── auth/
│   └── SessionStore.kt        # Cookie/session persistence (encrypted)
├── network/
│   ├── InstagramClient.kt     # Instagram private API client + signing headers
│   └── InstagramLinks.kt      # Share-link parser + shortcode→mediaId decode
├── model/
│   ├── InstagramModels.kt     # Media/feed/user models (incl. carousel_media)
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
