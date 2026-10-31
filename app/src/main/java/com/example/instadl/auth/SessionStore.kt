package com.example.instadl.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted session store for Instagram auth state.
 *
 * All values (cookies / user identity) are held in
 * EncryptedSharedPreferences (AES256_SIV keys + AES256_GCM values),
 * with the master key in the Android Keystore — never as cleartext XML.
 *
 * One-time migration: if a legacy cleartext "ig_session" file exists
 * (from versions before encryption), its values are copied into the
 * encrypted file and the legacy file is wiped.
 */
class SessionStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences = createEncryptedPrefs(appContext)

    fun saveSession(
        cookies: String,
        userId: String,
        username: String,
        fullName: String
    ) {
        prefs.edit()
            .putString(KEY_COOKIES, cookies)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .putString(KEY_FULL_NAME, fullName)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun saveCookies(cookies: String) {
        prefs.edit()
            .putString(KEY_COOKIES, cookies)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        // Defense in depth: make sure no legacy cleartext copy survives.
        try {
            appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not wipe legacy prefs", e)
        }
    }

    val cookies: String? get() = prefs.getString(KEY_COOKIES, null)
    val userId: String? get() = prefs.getString(KEY_USER_ID, null)
    val username: String? get() = prefs.getString(KEY_USERNAME, null)
    val fullName: String? get() = prefs.getString(KEY_FULL_NAME, null)

    val isLoggedIn: Boolean get() = !cookies.isNullOrBlank()

    companion object {
        private const val TAG = "SessionStore"
        private const val ENC_PREFS = "ig_session_enc"
        private const val LEGACY_PREFS = "ig_session"

        private const val KEY_COOKIES = "cookies"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_FULL_NAME = "full_name"
        private const val KEY_SAVED_AT = "saved_at"

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val enc = EncryptedSharedPreferences.create(
                context,
                ENC_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migrateLegacyIfNeeded(context, enc)
            return enc
        }

        private fun migrateLegacyIfNeeded(context: Context, enc: SharedPreferences) {
            // Already migrated / already in use.
            if (enc.contains(KEY_COOKIES)) {
                wipeLegacy(context)
                return
            }
            val legacy: SharedPreferences = try {
                context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open legacy prefs", e)
                return
            }
            if (!legacy.contains(KEY_COOKIES)) return

            try {
                enc.edit()
                    .putString(KEY_COOKIES, legacy.getString(KEY_COOKIES, null))
                    .putString(KEY_USER_ID, legacy.getString(KEY_USER_ID, null))
                    .putString(KEY_USERNAME, legacy.getString(KEY_USERNAME, null))
                    .putString(KEY_FULL_NAME, legacy.getString(KEY_FULL_NAME, null))
                    .putLong(KEY_SAVED_AT, legacy.getLong(KEY_SAVED_AT, System.currentTimeMillis()))
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Legacy session migration failed", e)
                return
            }
            wipeLegacy(context)
        }

        private fun wipeLegacy(context: Context) {
            try {
                context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                    .edit().clear().apply()
            } catch (e: Exception) {
                Log.w(TAG, "Could not wipe legacy prefs", e)
            }
        }
    }
}
