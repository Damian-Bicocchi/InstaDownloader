package com.example.instadl.auth

import android.content.Context
import android.content.SharedPreferences

class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            "ig_session", Context.MODE_PRIVATE)

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

    fun clear() {
        prefs.edit().clear().apply()
    }

    val cookies: String? get() = prefs.getString(KEY_COOKIES, null)
    val userId: String? get() = prefs.getString(KEY_USER_ID, null)
    val username: String? get() = prefs.getString(KEY_USERNAME, null)
    val fullName: String? get() = prefs.getString(KEY_FULL_NAME, null)

    val isLoggedIn: Boolean get() = !cookies.isNullOrBlank()

    companion object {
        private const val KEY_COOKIES = "cookies"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_FULL_NAME = "full_name"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
