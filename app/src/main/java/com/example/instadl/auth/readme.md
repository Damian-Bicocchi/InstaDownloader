# `auth` package — session persistence

Handles local storage of the Instagram login session using **encrypted** `SharedPreferences` (AndroidX Security).

## Contents

| File | Type | Purpose |
|------|------|---------|
| `SessionStore.kt` | `class SessionStore` | Saves, reads, and clears cookies + user identity (`userId`, `username`, `fullName`) in `EncryptedSharedPreferences`. |

## File details

### `SessionStore.kt`
- **Utility:** Decouples networking/UI from storage. Both `MainActivity` and `InstagramClient` depend on it instead of touching `SharedPreferences` directly.
- **Security:** uses `EncryptedSharedPreferences` (`ig_session_enc`, AES256_SIV keys + AES256_GCM values) with `MasterKey` (AES256_GCM) in the Android Keystore. No cleartext token file. Requires `androidx.security:security-crypto` (see `app/build.gradle.kts`).
- **Migration:** one-time copy from legacy cleartext `ig_session` → encrypted store, then wipes legacy (`clear()` also wipes legacy as defense in depth).
- **Construction:** `SessionStore(context)` — builds `MasterKey` + encrypted prefs via `applicationContext`.
- **Key API:**
  - `saveSession(cookies, userId, username, fullName)`: writes all keys + `saved_at` timestamp (`System.currentTimeMillis()`), `apply()` async.
  - `saveCookies(cookies)`: updates only the cookie string + `saved_at`, preserving identity fields — used by `InstagramCookieJar`.
  - `clear()`: wipes encrypted prefs + legacy cleartext copy — used on logout (`MainActivity.btnLogout`).
  - `cookies: String?` — raw `Cookie` header value; source of truth for auth.
  - `userId / username / fullName: String?` — display + API convenience.
  - `isLoggedIn: Boolean` — `!cookies.isNullOrBlank()`. Used by `updateLoginUi()` and `requireLoggedIn()`.
- **Keys (`companion object`):** `cookies`, `user_id`, `username`, `full_name`, `saved_at`.
- **Data flow:** `InstagramClient.login()` → OkHttp → `InstagramCookieJar.saveFromResponse()` → `SessionStore.saveCookies()` → `MainActivity` enriches with `loggedInUser.pk/username/fullName` via `saveSession()`.
