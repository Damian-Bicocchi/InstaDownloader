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
| 2 | High     | ~~Password lives as immutable `String` in heap, no `FLAG_SECURE`, errors echoed to UI~~ — **FIXED 2026-09-07: `FLAG_SECURE`, `CharArray` + immediate wipe, generic UI errors + `Log`** | `app/src/main/java/com/example/instadl/MainActivity.kt:performLogin()`, `network/InstagramClient.kt:login()` |
| 3 | High     | ~~Logout is local-only, server session stays valid~~ — **FIXED 2026-09-07: `InstagramClient.logout()` POSTs `/accounts/logout/` best-effort, `MainActivity.performLogout()` always wipes local** | `app/src/main/java/com/example/instadl/network/InstagramClient.kt`, `MainActivity.kt:performLogout()` |
| 4 | High     | Release signed with debug keystore + minify disabled                                                                                                                                                      | `app/build.gradle.kts:18-28`                                                                                |
| 5 | High     | No TLS hardening (no pinning, no `networkSecurityConfig`), redirects enabled, unused logging-interceptor                                                                                                  | `app/src/main/java/com/example/instadl/network/InstagramClient.kt:29-32,132-143`, `app/build.gradle.kts:58` |
| 6 | Medium   | `allowBackup="true"` allows `adb backup` extraction of session                                                                                                                                            | `app/src/main/AndroidManifest.xml:24`                                                                       |
| 7 | Medium   | Unencoded URL interpolation + fragile `getJSONObject` parsing (DoS / param injection)                                                                                                                     | `app/src/main/java/com/example/instadl/network/InstagramClient.kt:94-119`                                   |
| 8 | Medium   | Broken `Set-Cookie` parser (first header only, attributes as cookies, empty overwrite)                                                                                                                    | `app/src/main/java/com/example/instadl/network/InstagramClient.kt:159-172`                                  |
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

**Residual:** still exclude from backup (see #6) and validate expiry (`saved_at`) / re-auth on 401. No build run here (no Gradle toolchain in this env) — run `./gradlew :app:assembleDebug` / `lint` before release and confirm no `ig_session.xml` cleartext remains on device.

## 2. [High] In-memory password handling + UI exposure — FIXED (2026-09-07)

**Files:** `app/src/main/java/com/example/instadl/MainActivity.kt`, `network/InstagramClient.kt`

**Was:**
- `val password = etPassword.text.toString()` immutable `String`, retained until GC.
- No `FLAG_SECURE` → recents/screenshot leak.
- `setStatus("Login error: ${e.message}")` echoed internals.

**Now:**
- `onCreate()` sets `FLAG_SECURE` (no recents thumbnail / screenshot / recording).
- `performLogin()` copies `Editable` → `CharArray`, clears `etPassword` immediately, passes `CharArray` to `login()`, wipes with `fill('\u0000')` in `finally` (both sides). Transient `String` for OkHttp `FormBody` lives only inside `InstagramClient.login()` scope, never logged.
- UI shows generic `Login failed. Check credentials / connection`; details go to `Log.w(TAG, ...)` redacted (`status/errorType` only). Same sanitizing applied to post/story download errors. `Response.use {}` closes login response.

## 3. [High] Logout does not invalidate server session — FIXED (2026-09-07)

**Files:** `app/src/main/java/com/example/instadl/network/InstagramClient.kt:logout()`, `MainActivity.kt:performLogout()`

**Was:** `btnLogout` → `session.clear()` only. Stolen cookies remained valid server-side.

**Now:** `InstagramClient.logout()` POSTs `$BASE_URL/accounts/logout/` with `FormBody(_csrftoken, guid, device_id)` + `Cookie` + `X-CSRFToken` + base headers, returns `isSuccessful`. `MainActivity.performLogout()` runs it on `Dispatchers.IO` (best-effort, swallows network errors), then **always** `session.clear()` (encrypted store + legacy wipe), `updateLoginUi()`, disables all buttons including Logout while in flight. UI reports `Logged out` vs `Logged out locally (server logout failed)`.

## 4. [High] Debug signing + no obfuscation

**File:** `app/build.gradle.kts:18-28`

```kotlin
isMinifyEnabled = false
signingConfig = signingConfigs.getByName("debug")
```

Debug keystore password is public (`android`). Anyone can decompile (`isMinifyEnabled=false` keeps `X-IG-App-ID`, `User-Agent`, endpoint strings) and ship a spoofed update with the same signature.

**Fix:** private release keystore (CI secret), `isMinifyEnabled=true` + R8 / `proguard-rules.pro`, separate `debug`/`release` configs.

## 5. [High] No TLS hardening

**Files:** `app/src/main/java/com/example/instadl/network/InstagramClient.kt:29-32,132-143`, `app/build.gradle.kts:58`, `app/src/main/AndroidManifest.xml` (no `networkSecurityConfig`)

- Default `OkHttpClient`, `followRedirects/followSslRedirects=true`. System CAs trusted → MITM with user-installed CA succeeds.
- Manually set `Cookie` header in `buildGet()` may be replayed on cross-host redirect.
- `logging-interceptor` dependency is present but unused — if enabled at `BODY` level it would dump `password` and `Cookie`.

**Fix:**
- Add `res/xml/network_security_config.xml` with `cleartextTrafficPermitted="false"`, pin `i.instagram.com` (backup pin included), reference from manifest.
- Disable auto-redirects or strip `Cookie/Authorization` on host change.
- Remove `logging-interceptor` from release or restrict to `HttpLoggingLevel.NONE` in release builds.

## 6. [Medium] Backup enabled

**File:** `app/src/main/AndroidManifest.xml:24`

`android:allowBackup="true"` with no `backupRules` → `adb backup` extracts `ig_session.xml` and downloaded media metadata.

**Fix:** `allowBackup="false"`, or:
```xml
android:allowBackup="true" android:backupRules="@xml/backup_rules"
```
with `backup_rules.xml` excluding `shared_prefs/ig_session.xml`.

## 7. [Medium] Unencoded URLs + fragile JSON parsing

**File:** `app/src/main/java/com/example/instadl/network/InstagramClient.kt:94-119`

```kotlin
".../web_profile_info/?username=$username&is_sandbox=true"
".../feed/user/$pk/?count=33$cursor"
".../reels_media/?reel_ids=$userPk"
```

No `URLEncoder` / `HttpUrl.Builder` — `&`, `#`, `/` in target corrupts query. `getJSONObject("data").getJSONObject("user")` and `getJSONObject("reels").getJSONObject(userPk)` throw `JSONException` on API change → crash/DoS (currently caught only as generic `Exception` in `MainActivity`).

**Fix:** build URLs with `HttpUrl.Builder.addQueryParameter()`, validate with `has()`/`optJSONObject()`, map to typed errors.

## 8. [Medium] Broken cookie handling

**File:** `app/src/main/java/com/example/instadl/network/InstagramClient.kt:82-88,159-172`

- `response.header("Set-Cookie")` returns only the first header — multi-cookie login (`sessionid`, `csrftoken`, `ds_user`) is truncated.
- `split(';')` / `split('=')` treats `Expires`, `Path`, `Domain` as cookies; `kv.size==2` drops values containing `=`.
- Overwrites store with `session.userId ?: ""` — empty on first login, clobbering identity.

**Fix:** iterate `response.headers("Set-Cookie")`, use `Cookie.parse()` + persistent `CookieJar` backed by encrypted store.

## 9. [Low] Resource exhaustion + shared-storage exposure

**File:** `app/src/main/java/com/example/instadl/download/DownloadManager.kt:34-46,65-114`

- `body.bytes()` buffers entire video in RAM → OOM on large reels; no `Content-Length` cap, no MIME check, no checksum.
- Output to `Downloads/Instagram/` (MediaStore, API ≥ 29) or `Pictures/Instagram/` (legacy) is world-readable by any app with media/storage permission.
- `sanitize()` (`[^A-Za-z0-9._-] → _`) is good against path traversal — keep it.

**Fix:** stream `byteStream().copyTo(outputStream)`, enforce size cap (e.g. 250 MB), verify `Content-Type` (`image/jpeg`, `video/mp4`), keep MediaStore `IS_PENDING` protocol (already correct).

---

## General hardening checklist

- [ ] `EncryptedSharedPreferences` for `ig_session`, `allowBackup=false` / `backupRules`.
- [ ] Server-side logout, session expiry check.
- [ ] `networkSecurityConfig` + cert pinning, no cleartext, no auto-redirect with cookies.
- [ ] Remove `logging-interceptor` from release, enable R8/minify, private signing key.
- [ ] `FLAG_SECURE` on login, generic UI errors, no sensitive data in `Toast`/`TextView`.
- [ ] URL-encode all params via `HttpUrl.Builder`, robust JSON parsing.
- [ ] `CookieJar` instead of hand-rolled parser; handle multi-`Set-Cookie`.
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
