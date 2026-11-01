# Instagram Downloader (Android)

A native Android (Kotlin) app that downloads **photos**, **videos** and **stories** from individual Instagram links. Public posts/reels need no login; private media, stories and highlights require a logged-in account. Login happens on Instagram's own web page in a WebView — the app never receives your password. It does **not** enumerate or bulk-download a user's posts or stories.

Built and verified — prebuilt test APKs are available in the project root:

| File | Description |
|------|-------------|
| `InstagramDownloader-debug.apk` | Debug build (signed with debug keystore) |
| `InstagramDownloader-release.apk` | Legacy prebuilt release (signed with debug keystore — do not distribute) |

New release builds are signed only with your own release keystore (see below) and are minified with R8.

## How it works

The app uses **Instagram's private API v1** endpoints (`i.instagram.com/api/v1/...`), authenticated by logging in with an Instagram account. Its flow:

1. **Login (optional)** — opens Instagram's own login page in a WebView; the app never handles the password. On success it captures the resulting `.instagram.com` session cookies (including `csrftoken`) and stores them in encrypted `SharedPreferences` (`EncryptedSharedPreferences`, Keystore-backed). Sessions expire locally after 30 days. Required only for private media, stories and highlights.
2. **Direct link** — paste/share a reel, post, story or highlight link:
   - **Public post/reel → NO login needed.** `InstagramClient.fetchPublicShortcodeMedia()` GETs the unauthenticated embed page (`/{reel,p}/{shortcode}/embed/`, browser headers, no cookies) and extracts CDN URLs from the embedded `contextJSON` (`display_url` / `video_url`, including carousel children) with legacy node/`og:video` / `og:image` fallbacks.
   - **Private post/reel → login fallback.** If the embed has no media, and you are logged in, the app decodes the shortcode locally to a media id (`InstagramLinks`) and calls authenticated `GET /media/{id}/info/`, saving every carousel child.
   - **Story link (`/stories/<user>/<id>`) → login required**, fetched via `GET /media/{storyId}/info/`.
   - **Highlight link (`/stories/highlights/<id>/` or app share `/s/<token>[?story_media_id=…]`) → login required.** Token is base64-decoded to `highlight:<id>`; then `GET /feed/reels_media/?reel_ids=highlight:<id>` returns the whole story carousel and **every** story (flattened) is saved as `highlight_<id>_N`.
3. **Download** — Media is fetched over a separate OkHttp client and written to shared storage:
   - Android 10+ (API 29+): via `MediaStore` into `Pictures/Instagram/` (images) or `Movies/Instagram/` (videos)
   - Android 9 and below: legacy `WRITE_EXTERNAL_STORAGE` into `Pictures/Instagram/`

### App UI — what you actually see

This is **not** an Instagram UI wrapper / WebView. It is a small native screen with two cards (`app/src/main/res/layout/activity_main.xml`, wired in `MainActivity.kt`):

- **Account card:** a `Log in with Instagram` button opens Instagram's own login page in a WebView; the app never receives the password. `Logout` best-effort POSTs `accounts/logout/`, then always wipes the encrypted session and WebView cookies. `Clear session & cookies` is always available to wipe local state even if server logout fails; sessions also expire locally after 30 days.
- **Download from link card:** a `Paste reel / post / story / highlight link` field (`etLink`) + `Download Link` button → `downloadFromLink()`. You can also **Share** a link from Instagram / browser to this app (ACTION_SEND) — it pre-fills the link field and auto-downloads (or asks you to log in for private/story content). Status text + progress bar report `Resolving… / Downloading N file(s)… / Saved X of N`.

Files are saved as `link_<shortcode>_<index>.jpg/.mp4` (stories: `story_<id>_<index>...`, highlights: `highlight_<id>_<index>...`) via `DownloadManager.download()`.

### FAQ — common misconceptions

**1. Is the app a wrapper around the Instagram UI with download buttons injected?**

No. The only Instagram page shown is the login page inside a WebView; there is no browsable feed or injected download UI. You paste or share a link; public media is read from the public embed page, while private/story/highlight media uses the authenticated API with Instaloader-style iPad headers and an `IGT:2` session-authorization token (`InstagramClient.kt:baseHeaders`), parses the JSON into `model/` DTOs, and downloads the CDN (`bestImageUrl()` / `bestVideoUrl()`) files.

**2. If I send a highlight / story / reel / post link, what gets downloaded?**

Paste (or Share) the link into the **link field**:

- **Public reel/post → no login needed.** The app scrapes the public embed page and downloads **that exact item, ALL files** (every carousel photo/video; mixed albums correlate each child, single reels yield exactly the video).
- **Private reel/post → login needed.** If the embed is empty, log in and retry — the app falls back to the authenticated `GET /media/{id}/info/` path.
- **Story link (`/stories/<user>/<id>`) → login always required** (stories are never public).
- **Highlight link → login required, downloads the ENTIRE highlight.** Accepts `/stories/highlights/<id>/` and app shares `/s/<token>` (base64 `highlight:<id>`, else `?story_media_id=` single-story fallback). Every story in the highlight is saved.

**3. If a post has multiple images and/or videos (carousel/album), does it download them all?**

Yes — **for single-link downloads.** `MediaItem.carousel_media` is modeled and `flatten()` expands albums, so `downloadAll()` saves every child (`link_<shortcode>_<index>.jpg/.mp4`). Single-photo/video posts download the highest-resolution rendition.

## Project structure

```
app/src/main/java/com/example/instadl/
├── MainActivity.kt            # UI: login state + link/story/highlight downloads + share intent
├── WebLoginActivity.kt        # Instagram login page (WebView) + session-cookie capture
├── auth/
│   └── SessionStore.kt        # Cookie/session persistence (encrypted)
├── network/
│   ├── InstagramClient.kt     # Instagram private API client + signing headers
│   └── InstagramLinks.kt      # Share-link parser + shortcode→mediaId decode
├── model/
│   ├── InstagramModels.kt     # Media/feed models (incl. carousel_media)
│   └── AuthModels.kt          # Login/error models
└── download/
    └── DownloadManager.kt     # MediaStore / legacy storage writers
```

Key files:
- `InstagramClient.kt` — authenticated/public API calls, request headers, cookie handling
- `WebLoginActivity.kt` — Instagram WebView login and cookie capture
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

Release builds enable R8 (`isMinifyEnabled = true`) and resource shrinking, and use the rules in `app/proguard-rules.pro`.

The release keystore is **never** hardcoded and the debug keystore is no longer used for release builds. Provide these values through `local.properties` (git-ignored), Gradle properties, or environment variables:

| Key | Meaning |
|-----|---------|
| `RELEASE_STORE_FILE` | Path to the `.jks`/`.keystore` (relative paths resolve from the project root) |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Example `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
RELEASE_STORE_FILE=release.jks
RELEASE_STORE_PASSWORD=change-me
RELEASE_KEY_ALIAS=instadl
RELEASE_KEY_PASSWORD=change-me
```

Then build:

```bash
gradle assembleRelease
# signed output: app/build/outputs/apk/release/app-release.apk
```

If any of the four values is missing, the release APK is built **unsigned** (`app-release-unsigned.apk`) instead of falling back to the debug key. CI should inject the values as secrets/environment variables.

## ⚠️ Important legal & safety warnings

- **Violates Instagram's Terms of Service.** Logging in and using Instagram's private/internal API for automated requests is against their ToS. This can lead to the logged-in account being **temporarily restricted or permanently disabled**, and Instagram's endpoints/code change often (the app may stop working without notice).
- **No bulk account scraping.** The app intentionally provides no “download all posts/stories from user” feature; it only processes individual links you supply.
- **Use at your own risk.** Prefer a **disposable account** you don't care about, not your main one.
- **Respect platforms' ToS and applicable law.** Only download content you have the right to use.
- This project is for **personal / educational use**.
