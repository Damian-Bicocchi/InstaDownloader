package com.example.instadl

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.instadl.auth.SessionStore
import com.example.instadl.databinding.ActivityWebLoginBinding

class WebLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebLoginBinding
    private lateinit var session: SessionStore
    private var finishing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        super.onCreate(savedInstanceState)
        binding = ActivityWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionStore(this)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.isVisible = false
                finishIfLoggedIn()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                finishIfLoggedIn()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val scheme = request?.url?.scheme ?: return false
                return scheme != "http" && scheme != "https"
            }
        }

        cookieManager.removeAllCookies {
            if (!isFinishing && !isDestroyed) {
                binding.webView.loadUrl(LOGIN_URL)
            }
        }
    }

    private fun finishIfLoggedIn() {
        if (finishing) return
        val cookies = readSessionCookies() ?: return
        if (!cookies.contains("sessionid=")) return

        finishing = true
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        session.saveSession(
            cookies = cookies,
            userId = cookieValue(cookies, "ds_user_id") ?: "",
            username = "",
            fullName = ""
        )
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        setResult(RESULT_OK)
        finish()
    }

    private fun readSessionCookies(): String? {
        val manager = CookieManager.getInstance()
        val merged = LinkedHashMap<String, String>()

        for (url in listOf("https://www.instagram.com", "https://i.instagram.com")) {
            manager.getCookie(url)?.split(';')?.forEach { part ->
                val trimmed = part.trim()
                val separator = trimmed.indexOf('=')
                if (separator > 0) {
                    merged[trimmed.substring(0, separator).trim()] =
                        trimmed.substring(separator + 1).trim()
                }
            }
        }

        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun cookieValue(cookies: String, name: String): String? {
        val prefix = "$name="
        return cookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.substring(prefix.length)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            setResult(RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

        fun clearCookies() {
            val manager = CookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.flush()
        }
    }
}
