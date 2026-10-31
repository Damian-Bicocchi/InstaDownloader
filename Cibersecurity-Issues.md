# Cibersecurity Issues — inst-download

**Project:** `com.example.instadl` (Instagram Downloader, Android/Kotlin)
**Date:** 2026-09-07
**Scope:** `app/src/main/java/com/example/instadl/`, `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `app/src/main/res/layout/activity_main.xml`
**Method:** Static code review (no dynamic test / no secrets scan).

> Note: filename keeps the requested spelling `Cibersecurity-Issues.md`.

## Summary

| # | Severity | Issue                                                                                                                                                                                                     | Location                                                                                                    |
|---|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| 1 | Critical | ~~Session tokens (cookies) stored in cleartext `SharedPreferences`~~ — **FIXED 2026-09-07: `EncryptedSharedPreferences` (`ig_session_enc`) + Keystore `MasterKey`, legacy `ig_session` migrated + wiped** | `app/src/main/java/com/example/instadl/auth/SessionStore.kt`                                                |
| 2 | High     | ~~Password lives as immutable `String` in heap, no `FLAG_SECURE`, errors echoed to UI~~ — **FIXED 2026-10-31: login moved to Instagram WebView (password never enters app), `FLAG_SECURE` on both screens, encrypted cookie storage, safe status messages**                    | `app/src/main/java/com/example/instadl/WebLoginActivity.kt`, `MainActivity.kt` |
| 3 | High     | ~~Logout is local-only, server session stays valid~~ — **FIXED 2026-09-07: `InstagramClient.logout()` POSTs `/accounts/logout/` best-effort, `MainActivity.performLogout()` always wipes local**          | `app/src/main/java/com/example/instadl/network/InstagramClient.kt`, `MainActivity.kt:performLogout()` |
| 4 | High     | ~~Release signed with debug keystore + minify disabled~~ — **FIXED 2026-10-31: dedicated release signing config from `local.properties`/Gradle props/env (unsigned if absent), `isMinifyEnabled` + `isShrinkResources` enabled, R8 rules added** | `app/build.gradle.kts`, `app/proguard-rules.pro`                                                                                |
| 5 | High     | ~~No TLS hardening (no pinning, no `networkSecurityConfig`), redirects enabled, unused logging-interceptor~~ — **FIXED 2026-10-31: cleartext disabled, pins added, auth redirects disabled, cookies domain-scoped via `CookieJar`, logging interceptor removed** | `app/src/main/res/xml/network_security_config.xml`, `AndroidManifest.xml`, `network/InstagramClient.kt`, `network/InstagramCookieJar.kt`, `app/build.gradle.kts` |
| 6 | Medium   | ~~`allowBackup="true"` allows `adb backup` extraction of session~~ — **FIXED 2026-10-31: `allowBackup="false"`, legacy `fullBackupContent="false"`, Android 12+ `dataExtractionRules` exclude all cloud/D2D data**                                                                                                                    | `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml` |
| 7 | Medium   | Partial fix: bulk username/feed endpoints removed; remaining media/highlight URLs still interpolate IDs and JSON parsing is fragile                                                                        | `app/src/main/java/com/example/instadl/network/InstagramClient.kt`                                          |
| 8 | Medium   | ~~Broken `Set-Cookie` parser (first header only, attributes as cookies, empty overwrite)~~ — **FIXED 2026-10-31: OkHttp `CookieJar` parses all `Set-Cookie` headers, merges by name, keeps identity fields intact**                                                                                                                    | `app/src/main/java/com/example/instadl/network/InstagramCookieJar.kt`, `auth/SessionStore.kt`                                  |
| 9 | Low      | Unbounded in-RAM download (`body.bytes()`) + world-readable shared storage output                                                                                                                         | `app/src/main/java/com/example/instadl/download/DownloadManager.kt:34-46,65-114`                            |

---

## 1. [Critical] Cleartext session storage — FIXED (2026-09-07)

**File:** `app/src/main/java/com/example/instadl/auth/SessionStore.kt`

**Was:**
```kotlin
context.getSharedPreferences("ig_session", MODE_PRIVATE)
putString("cookies", cookies) // sessionid, csrftoken, ds_user_id, rur
```

**Now:** `EncryptedSharedPreferences` file `ig_session_enc` (AES256_SIV key / AES256_GCM value encryption) with Keystore-backed `MasterKey` (AES256_GCM). Dependency: `androidx.security:security-crypto:1.1.0-alpha06` in `app/build.gradle.kts`. Legacy `ig_session` is migrated once into the encrypted file and wiped (`migrateLegacyIfNeeded` + `wipeLegacy`; `clear()` wipes both).

**Update 2026-10-31:** sessions now expire locally after 30 days (`SessionStore.clearIfExpired()`), there is an always-available `Clear session & cookies` action, WebView cookies are cleared after capture/logout, and backup extraction is disabled (#6).

## 2. [High] In-memory password handling + UI exposure — FIXED (2026-10-31)

**Files:** `app/src/main/java/com/example/instadl/WebLoginActivity.kt`, `MainActivity.kt`

**Was:** username/password fields existed in the native UI; the password lived as a `String` in the heap, `FLAG_SECURE` was missing, and login exceptions could be echoed to the UI.

**Now:**
- The native username/password fields were removed. `WebLoginActivity` loads Instagram's own login page in a WebView; the app never receives, stores, or logs the password.
- `FLAG_SECURE` is set on both `MainActivity` and `WebLoginActivity`.
- Only the resulting `.instagram.com` session cookies are captured, then stored in `EncryptedSharedPreferences`.
- Login state errors are reduced to safe status text; raw exceptions/server messages go only to redacted logs.
- Logout wipes both `SessionStore` and WebView cookies.

**Residual:** the WebView login avoids credential handling but authenticated downloads still use Instagram's private API (ToS/ban risk remains).

## 3. [High] Logout does not invalidate server session — FIXED (2026-09-07)

**Files:** `app/src/main/java/com/example/instadl/network/InstagramClient.kt:logout()`, `MainActivity.kt:performLogout()`

**Was:** `btnLogout` → `session.clear()` only. Stolen cookies remained valid server-side.

**Now:** `InstagramClient.logout()` POSTs `$BASE_URL/accounts/logout/` with `FormBody(_csrftoken, guid, device_id)` + `Cookie` + `X-CSRFToken` + base headers, returns `isSuccessful`. `MainActivity.performLogout()` runs it on `Dispatchers.IO` (best-effort, swallows network errors), then **always** `session.clear()` (encrypted store + legacy wipe), `updateLoginUi()`, disables all buttons including Logout while in flight. UI reports `Logged out` vs `Logged out locally (server logout failed)`.

## 4. [High] Debug signing + no obfuscation — FIXED (2026-10-31)

**File:** `app/build.gradle.kts`, `app/proguard-rules.pro`

**Was:**
```kotlin
isMinifyEnabled = false
signingConfig = signingConfigs.getByName("debug")
```

Debug keystore password is public (`android`). Anyone can decompile (`isMinifyEnabled=false` keeps `X-IG-App-ID`, `User-Agent`, endpoint strings) and ship a spoofed update with the same signature.

**Now:**
- `release` no longer references the debug keystore. A dedicated `release` signing config is created only when `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` are supplied (Gradle properties → `local.properties` → env vars).
- Without those values the release build is **unsigned** (`app-release-unsigned.apk`), never debug-signed.
- `isMinifyEnabled = true` and `isShrinkResources = true`; R8 uses `proguard-android-optimize.txt` + project rules.
- `proguard-rules.pro` keeps the Gson DTO package/`@SerializedName` fields/annotations so JSON parsing survives shrinking, and silences unused OkHttp TLS providers.
- README documents the four signing keys; `local.properties` stays git-ignored, CI should use secrets.

**Residual:** creates a real release keystore and keep it outside the repo; the legacy `InstagramDownloader-release.apk` in the project root was built before this change and is still debug-signed (documented as not for distribution).

## 5. [High] No TLS hardening — FIXED (2026-10-31)

**Files:** `app/src/main/res/xml/network_security_config.xml`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/example/instadl/network/InstagramClient.kt`, `network/InstagramCookieJar.kt`, `app/build.gradle.kts`

**Was:**
- Default `OkHttpClient`, `followRedirects/followSslRedirects=true` with no certificate pinning or `networkSecurityConfig`.
- Manually set `Cookie` header could be replayed on a cross-host redirect.
- Unused `logging-interceptor` dependency could leak passwords/cookies if enabled at `BODY` level.

**Now:**
- `network_security_config.xml` disables cleartext globally and pins `i.instagram.com` / `www.instagram.com` to the current DigiCert intermediate + root (SPKI SHA-256, chain checked 2026-10-31).
- The authenticated `OkHttpClient` has `followRedirects=false` and `followSslRedirects=false`; redirects fail instead of forwarding credentials. The public embed client still follows redirects but sends no cookies.
- Session cookies are attached by `InstagramCookieJar` only for `i.instagram.com`; removal of the manual `Cookie` header prevents cross-host replay.
- `logging-interceptor` was removed from `app/build.gradle.kts` (it was unused).

**Residual:** pin set expires 2031-03-29; update pins if Meta changes CA. `assembleRelease` and `lintRelease` pass.

## 6. [Medium] Backup enabled — FIXED (2026-10-31)

**File:** `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml`

**Was:** `allowBackup="true"` (default) with no backup rules → `adb backup` / cloud backup / device-to-device transfer could extract the encrypted session prefs and app data.

**Now:**
- `android:allowBackup="false"` disables cloud backup and `adb backup`.
- `android:fullBackupContent="false"` keeps legacy (API ≤ 30) behavior closed.
- `android:dataExtractionRules="@xml/data_extraction_rules"` explicitly excludes `root`, `file`, `database`, `sharedpref`, `external`, and device-protected domains from both `<cloud-backup>` and `<device-transfer>`, covering OEMs where `allowBackup="false"` alone may not block D2D migration.
- Session data was already AES-encrypted with a Keystore key; this adds defense-in-depth so it cannot be copied off-device in the first place.

**Residual:** none for backup extraction. `assembleRelease` and `lintRelease` pass.

## 7. [Medium] Unencoded URLs + fragile JSON parsing — PARTIAL (2026-10-31)

**File:** `app/src/main/java/com/example/instadl/network/InstagramClient.kt`

**Was:** `.../web_profile_info/?username=$username`, `.../feed/user/$pk/?count=33$cursor` and `.../reels_media/?reel_ids=$userPk` interpolated user-controlled values without encoding, and `getJSONObject("data").getJSONObject("user")` / `getJSONObject("reels").getJSONObject(userPk)` could throw on API changes.

**Now:** the username/feed bulk endpoints were removed together with the “Download from user” feature, eliminating the main parameter-injection and parsing surface.

**Residual:** `fetchMediaInfo()` / `fetchHighlightItems()` still interpolate media/highlight IDs into URLs and use `getJSONArray`/`getJSONObject` without typed error mapping. IDs normally come from the link parser, but should still be digit-validated and built with `HttpUrl.Builder`; JSON access should use `opt*`/typed errors.

## 8. [Medium] Broken cookie handling — FIXED (2026-10-31)

**Files:** `app/src/main/java/com/example/instadl/network/InstagramCookieJar.kt`, `auth/SessionStore.kt`

**Was:**
- `response.header("Set-Cookie")` read only the first header — multi-cookie login (`sessionid`, `csrftoken`, `ds_user`) was truncated.
- `split(';')` / `split('=')` treated `Expires`, `Path`, `Domain` as cookies; `kv.size==2` dropped values containing `=`.
- Overwrote the store with `session.userId ?: ""` — empty on first login, clobbering identity.

**Now:**
- `InstagramCookieJar` implements OkHttp `CookieJar`; OkHttp parses every `Set-Cookie` header with `Cookie.parseAll`.
- Cookies are merged by name instead of replacing the whole jar, expired cookies are removed, and `SessionStore.saveCookies()` updates only the cookie key + timestamp, leaving `userId`/`username`/`fullName` intact.
- `InstagramClient` no longer hand-parses or manually sets `Cookie`; the jar supplies cookies only for `i.instagram.com`.

**Residual:** cookie attributes (domain/path/expiry) are reduced to name/value pairs for the single Instagram host; sufficient for this app, acceptable given encrypted local storage.

## 9. [Low] Resource exhaustion + shared-storage exposure

**File:** `app/src/main/java/com/example/instadl/download/DownloadManager.kt:34-46,65-114`

- `body.bytes()` buffers entire video in RAM → OOM on large reels; no `Content-Length` cap, no MIME check, no checksum.
- Output to `Pictures/Instagram/` (images) or `Movies/Instagram/` (videos) on API ≥ 29, or `Pictures/Instagram/` (legacy), is world-readable by any app with media/storage permission.
- `sanitize()` (`[^A-Za-z0-9._-] → _`) is good against path traversal — keep it.

**Fix:** stream `byteStream().copyTo(outputStream)`, enforce size cap (e.g. 250 MB), verify `Content-Type` (`image/jpeg`, `video/mp4`), keep MediaStore `IS_PENDING` protocol (already correct).

---

## General hardening checklist

- [x] `EncryptedSharedPreferences` for `ig_session`, `allowBackup=false` / `backupRules`.
- [ ] Server-side logout, session expiry check.
- [x] `networkSecurityConfig` + cert pinning, no cleartext, no auto-redirect with cookies.
- [x] Remove `logging-interceptor` from release (R8/minify and private signing key are now done).
- [x] `FLAG_SECURE` on login, safe mapped UI errors, no sensitive data in `Toast`/`TextView`.
- [ ] URL-encode all params via `HttpUrl.Builder`, robust JSON parsing.
- [x] `CookieJar` instead of hand-rolled parser; handle multi-`Set-Cookie`.
- [ ] Stream downloads to disk with size/MIME limits.
- [ ] Update deps (`okhttp:4.12.0`, `gson:2.11.0`, `compileSdk/targetSdk 34`) on a cadence; run `gradle dependencyCheck` / Play Data Safety review.
- [ ] Consider official Instagram Graph API + OAuth instead of private `i.instagram.com/api/v1` (ToS violation → ban risk, already disclosed in `activity_main.xml:215` and `InstagramClient.kt:16-23`).

## References (code)

- `app/src/main/java/com/example/instadl/auth/SessionStore.kt`
- `app/src/main/java/com/example/instadl/MainActivity.kt`
- `app/src/main/java/com/example/instadl/network/InstagramClient.kt`
- `app/src/main/java/com/example/instadl/download/DownloadManager.kt`
- `app/src/main/java/com/example/instadl/model/AuthModels.kt`, `InstagramModels.kt`
- `app/src/main/AndroidManifest.xml`
- `app/build.gradle.kts`
- `app/src/main/res/layout/activity_main.xml`
