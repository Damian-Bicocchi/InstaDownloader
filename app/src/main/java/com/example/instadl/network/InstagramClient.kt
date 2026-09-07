package com.example.instadl.network

import com.example.instadl.auth.SessionStore
import com.example.instadl.model.IgError
import com.example.instadl.model.LoginResponse
import com.example.instadl.model.MediaFeed
import com.example.instadl.model.UserInfo
import com.google.gson.Gson
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Thin client for Instagram's private (web/API v1) endpoints.
 *
 * IMPORTANT: Using these endpoints to send automated requests to Instagram
 * violates Instagram's Terms of Service and can lead to account temporary
 * restrictions or permanent bans. Use only on accounts you own and are willing
 * to accept that risk on. This is for personal / educational use.
 */
class InstagramClient(private val session: SessionStore) {

    private val gson = Gson()
    private val deviceUuid: String = UUID.randomUUID().toString()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Headers mirroring the official Instagram Android app webview/API client.
    private val baseHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Instagram 269.0.0.18.85 Android (31/12; 420dpi; 1080x2400; Google; Pixel 5; redfin; sm7150; en_US; 355471274)",
        "X-IG-App-ID" to "567067343352427",
        "X-IG-Capabilities" to "3brTvw==",
        "X-IG-Connection-Type" to "WIFI",
        "Accept-Language" to "en-US",
        "Accept" to "*/*",
        "X-FB-HTTP-Engine" to "Liger",
        "Connection" to "close"
    )

    @Throws(IOException::class)
    fun login(username: String, password: String): LoginResponse {
        val time = "${System.currentTimeMillis() / 1000}"
        val csrf = "missing"
        val body = mapOf(
            "phone_id" to UUID.randomUUID().toString(),
            "username" to username,
            "adid" to UUID.randomUUID().toString(),
            "guid" to deviceUuid,
            "device_id" to "android-${deviceUuid}",
            "password" to password,
            "_csrftoken" to csrf,
            "login_attempt_count" to "0"
        )

        val form = FormBody.Builder()
        body.forEach { (k, v) -> form.add(k, v) }

        val req = Request.Builder()
            .url("$BASE_URL/accounts/login/")
            .post(form.build())
            .apply { baseHeaders.forEach { (k, v) -> header(k, v) } }
            .header("X-CSRFToken", csrf)
            .header("X-IG-WWW-Claim", "0")
            .header("X-Instagram-AJAX", "1")
            .header("X-Timestamp", time)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = client.newCall(req).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful || responseBody.isBlank()) {
            throw IOException("Login failed HTTP ${response.code}")
        }

        // Keep session cookies on success (ignore login-specific errors).
        if (responseBody.contains("\"status\":\"ok\"") ||
            responseBody.contains("\"logged_in_user\"")
        ) {
            val cookieHeader = response.header("Set-Cookie") ?: ""
            saveCookies(cookieHeader)
        }

        return gson.fromJson(responseBody, LoginResponse::class.java)
    }

    @Throws(IOException::class)
    fun fetchUserByUsername(username: String): UserInfo {
        val req = buildGet(
            "${BASE_URL}/users/web_profile_info/?username=$username&is_sandbox=true")
        val body = executeAndCheck(req)
        val obj = JSONObject(body)
        val data = obj.getJSONObject("data").getJSONObject("user")
        return gson.fromJson(data.toString(), UserInfo::class.java)
    }

    @Throws(IOException::class)
    fun fetchUserMediaPk(pk: String, maxId: String? = null): MediaFeed {
        val cursor = if (maxId.isNullOrBlank()) "" else "&max_id=$maxId"
        val req = buildGet("${BASE_URL}/feed/user/$pk/?count=33$cursor")
        val body = executeAndCheck(req)
        return gson.fromJson(body, MediaFeed::class.java)
    }

    @Throws(IOException::class)
    fun fetchStories(userPk: String): MediaFeed {
        val req = buildGet("${BASE_URL}/feed/reels_media/?reel_ids=$userPk")
        val body = executeAndCheck(req)
        // reels_media wraps results under reels -> {userPk: {items: [...]}}
        val obj = JSONObject(body)
        val user = obj.getJSONObject("reels").getJSONObject(userPk)
        return gson.fromJson(user.toString(), MediaFeed::class.java)
    }

    @Throws(IOException::class)
    fun fetchMediaInfo(mediaId: String): MediaFeed {
        val req = buildGet("${BASE_URL}/media/$mediaId/info/")
        val body = executeAndCheck(req)
        val obj = JSONObject(body)
        val items = obj.getJSONObject("items")
        return gson.fromJson(items.toString(), MediaFeed::class.java)
    }

    fun isSessionValid(): Boolean = session.isLoggedIn

    private fun buildGet(url: String): Request {
        val builder = Request.Builder().url(url).get()
        baseHeaders.forEach { (k, v) -> builder.header(k, v) }
        val cookies = session.cookies
        if (!cookies.isNullOrBlank()) {
            builder.header("Cookie", cookies)
        }
        builder.header("X-Instagram-AJAX", "1")
        builder.header(
            "X-CSRFToken", session.cookies?.let(::extractCsrf) ?: "missing")
        return builder.build()
    }

    private fun executeAndCheck(request: Request): String {
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val message = try {
                gson.fromJson(body, IgError::class.java)?.message ?: "HTTP ${response.code}"
            } catch (e: Exception) {
                "HTTP ${response.code}"
            }
            throw IOException(message)
        }
        return body
    }

    private fun saveCookies(setCookieHeader: String) {
        val pairs = setCookieHeader.split(';')
        val cookieMap = mutableMapOf<String, String>()
        for (p in pairs) {
            val kv = p.trim().split('=')
            if (kv.size == 2 && kv[0].isNotBlank()) {
                cookieMap[kv[0]] = kv[1]
            }
        }
        val userId = session.userId ?: ""
        val username = session.username ?: ""
        val cookieStr = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        session.saveSession(cookieStr, userId, username, username)
    }

    private fun extractCsrf(cookieHeader: String): String {
        val match = Regex("(?:^|; )csrftoken=([^;]*)").find(cookieHeader)
        return match?.groupValues?.get(1) ?: "missing"
    }

    companion object {
        const val BASE_URL = "https://i.instagram.com/api/v1"
    }
}
