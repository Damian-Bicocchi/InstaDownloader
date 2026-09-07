# `auth` package — session persistence

Handles local storage of the Instagram login session using Android `SharedPreferences`.

## Contents

| File | Type | Purpose |
|------|------|---------|
| `SessionStore.kt` | `class SessionStore` | Saves, reads, and clears cookies + user identity (`userId`, `username`, `fullName`). |

## File details

### `SessionStore.kt`
- **Utility:** Decouples networking/UI from storage. Both `MainActivity` and `InstagramClient` depend on it instead of touching `SharedPreferences` directly.
- **Construction:** `SessionStore(context)` — opens `ig_session` prefs with `MODE_PRIVATE` via `applicationContext`.
- **Key API:**
  - `saveSession(cookies, userId, username, fullName)`: writes all keys + `saved_at` timestamp (`System.currentTimeMillis()`), `apply()` async.
  - `clear()`: `prefs.edit().clear()` — used on logout (`MainActivity.btnLogout`).
  - `cookies: String?` — raw `Cookie` header value; source of truth for auth.
  - `userId / username / fullName: String?` — display + API convenience.
  - `isLoggedIn: Boolean` — `!cookies.isNullOrBlank()`. Used by `updateLoginUi()` and `requireLoggedIn()`.
- **Keys (`companion object`):** `cookies`, `user_id`, `username`, `full_name`, `saved_at`.
- **Data flow:** `InstagramClient.login()` → `saveCookies()` → `saveSession()` → `MainActivity` enriches with `loggedInUser.pk/username/fullName`.
