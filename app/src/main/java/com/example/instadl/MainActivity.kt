package com.example.instadl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.instadl.auth.SessionStore
import com.example.instadl.databinding.ActivityMainBinding
import com.example.instadl.download.DownloadManager
import com.example.instadl.model.MediaItem
import com.example.instadl.network.InstagramClient
import com.example.instadl.network.InstagramLinks
import com.example.instadl.network.PublicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-screen controller: web-session login and single-link downloads
 * (public-first embed fetch, authenticated fallback for private media,
 * login-gated stories / highlights).
 *
 * Hardening kept in place: FLAG_SECURE on the login screen, credentials are
 * entered only in Instagram's own WebView page, session cookies live in
 * EncryptedSharedPreferences, and logout clears both the server session and
 * WebView cookies.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: SessionStore
    private lateinit var client: InstagramClient

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val webLoginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) finishWebLogin()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never let the login screen (password field) appear in
        // recents thumbnails, screenshots or screen recordings.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionStore(this)
        val sessionExpired = session.clearIfExpired()
        if (sessionExpired) WebLoginActivity.clearCookies()
        client = InstagramClient(session)

        updateLoginUi()
        if (sessionExpired) setStatus("Session expired. Log in again.")
        requestStoragePermissions()

        binding.btnLogin.setOnClickListener { performLogin() }
        binding.btnLogout.setOnClickListener { performLogout() }
        binding.btnClearSession.setOnClickListener { clearSession() }
        binding.btnAbout.setOnClickListener { showAbout() }
        binding.btnDownloadLink.setOnClickListener {
            downloadFromLink(binding.etLink.text.toString())
        }
        binding.btnClearLink.setOnClickListener { binding.etLink.text?.clear() }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /** Accept Instagram URLs shared from Instagram / browser via ACTION_SEND. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("""https?://[^\s'"]*instagram\.com[^\s'"]*""")
            .find(text)?.value ?: return
        binding.etLink.setText(url)
        // Public posts/reels resolve without login; stories/highlights and
        // private media need it — downloadFromLink() gates per link type.
        downloadFromLink(url)
    }

    private fun requestStoragePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun updateLoginUi() {
        val logged = session.isLoggedIn
        val name = session.username ?: session.fullName
        binding.tvLoginStatus.text = when {
            !logged -> "Not logged in"
            name.isNullOrBlank() -> "Logged in"
            else -> "Logged in as @$name"
        }
        binding.btnLogin.isVisible = !logged
        binding.btnLogout.isVisible = logged
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.isVisible = loading
        val buttons = listOf(
            binding.btnLogin,
            binding.btnLogout,
            binding.btnClearSession,
            binding.btnDownloadLink
        )
        buttons.forEach { it.isEnabled = !loading }
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun performLogin() {
        setStatus("Log in through Instagram…")
        webLoginLauncher.launch(Intent(this, WebLoginActivity::class.java))
    }

    private fun finishWebLogin() {
        setLoading(true)
        setStatus("Verifying Instagram session…")
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val user = withContext(Dispatchers.IO) { client.fetchCurrentUser() }
                session.saveSession(
                    cookies = session.cookies ?: "",
                    userId = user.pk ?: "",
                    username = user.username ?: "",
                    fullName = user.fullName ?: ""
                )
                setStatus("Login successful")
                updateLoginUi()
                toast(user.username?.let { "Logged in as @$it" } ?: "Logged in")
            } catch (e: Exception) {
                Log.w(TAG, "Could not verify web login session", e)
                updateLoginUi()
                if (session.isLoggedIn) {
                    setStatus("Logged in")
                    toast("Logged in")
                } else {
                    setStatus("Login did not complete. Try again.")
                    toast("Login did not complete. Try again.")
                }
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showAbout() {
        startActivity(Intent(this, AboutActivity::class.java))
    }

    private fun clearSession() {
        session.clear()
        WebLoginActivity.clearCookies()
        updateLoginUi()
        setStatus("Session and cookies cleared")
        toast("Session and cookies cleared")
    }

    private fun performLogout() {
        if (!session.isLoggedIn) {
            updateLoginUi()
            return
        }
        setLoading(true)
        setStatus("Logging out…")
        CoroutineScope(Dispatchers.Main).launch {
            val serverOk = try {
                withContext(Dispatchers.IO) { client.logout() }
            } catch (e: Exception) {
                false
            }
            // Always wipe local storage, even if server call failed/offline.
            session.clear()
            WebLoginActivity.clearCookies()
            updateLoginUi()
            setLoading(false)
            if (serverOk) {
                setStatus("Logged out")
                toast("Logged out")
            } else {
                setStatus("Logged out locally (server logout failed)")
                toast("Logged out locally")
            }
        }
    }

    /**
     * Link download: public posts/reels work WITHOUT login (unauthenticated
     * embed scrape). Authenticated API is only a fallback for private media,
     * and a requirement for stories/highlights (never public).
     */
    private fun downloadFromLink(rawLink: String) {
        val link = rawLink.trim()
        if (link.isBlank()) {
            toast("Paste an Instagram link first")
            return
        }
        val resolved = InstagramLinks.parse(link)
        if (resolved == null) {
            toast("Unsupported link (use post / reel / story / highlight link)")
            setStatus("Unsupported link.")
            return
        }
        if ((resolved is InstagramLinks.Resolved.StoryMedia ||
                resolved is InstagramLinks.Resolved.Highlight) && !session.isLoggedIn
        ) {
            toast("Log in to download stories & highlights")
            setStatus("Stories & highlights need login — log in, then retry the link.")
            return
        }
        setLoading(true)
        setStatus("Resolving link…")
        CoroutineScope(Dispatchers.Main).launch {
            try {
                when (resolved) {
                    is InstagramLinks.Resolved.ShortcodeMedia ->
                        downloadShortcode(resolved)
                    is InstagramLinks.Resolved.StoryMedia ->
                        downloadStoryLink(resolved)
                    is InstagramLinks.Resolved.Highlight ->
                        downloadHighlight(resolved)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download link failed", e)
                setStatus("Download failed. Try again.")
                toast("Download failed. Try again.")
            } finally {
                setLoading(false)
            }
        }
    }

    /** Public-first: embed scrape (no login) → auth fallback (private). */
    private suspend fun downloadShortcode(resolved: InstagramLinks.Resolved.ShortcodeMedia) {
        val prefix = "link_${resolved.shortcode}"

        val pub = withContext(Dispatchers.IO) {
            try {
                client.fetchPublicShortcodeMedia(resolved.shortcode)
            } catch (e: Exception) {
                Log.w(TAG, "Public embed fetch failed, trying auth", e)
                null
            }
        }
        if (!pub.isNullOrEmpty()) {
            setStatus("Downloading ${pub.size} file(s)…")
            val saved = withContext(Dispatchers.IO) { downloadPublicFiles(pub, prefix) }
            setStatus("Saved $saved of ${pub.size} files to your Photos/Downloads.")
            toast("Downloaded $saved files")
            return
        }

        if (!session.isLoggedIn) {
            setStatus("This looks private — log in, then retry the link.")
            toast("Log in to download private posts")
            return
        }
        try {
            val item = withContext(Dispatchers.IO) {
                client.fetchMediaByShortcode(resolved.shortcode)
            }
            // Carousels expand to all children — every image/video is saved.
            val units = item.flatten()
            if (units.isEmpty()) {
                setStatus("No media found for this link")
                return
            }
            setStatus("Downloading ${units.size} file(s)…")
            val saved = withContext(Dispatchers.IO) { downloadAll(units, prefix) }
            setStatus("Saved $saved of ${units.size} files to your Photos/Downloads.")
            toast("Downloaded $saved files")
        } catch (e: Exception) {
            Log.w(TAG, "Auth link fetch failed", e)
            setStatus("Download failed. Private/expired media needs a logged-in viewer that can see it.")
            toast("Download failed. Try again.")
        }
    }

    private suspend fun downloadStoryLink(resolved: InstagramLinks.Resolved.StoryMedia) {
        // Caller guarantees logged-in (checked above).
        try {
            val item = withContext(Dispatchers.IO) {
                client.fetchMediaItem(resolved.storyId)
            }
            val units = item.flatten()
            if (units.isEmpty()) {
                setStatus("No media found for this link")
                return
            }
            val prefix = "story_${resolved.storyId}"
            setStatus("Downloading ${units.size} file(s)…")
            val saved = withContext(Dispatchers.IO) { downloadAll(units, prefix) }
            setStatus("Saved $saved of ${units.size} files to your Photos/Downloads.")
            toast("Downloaded $saved files")
        } catch (e: Exception) {
            Log.w(TAG, "Story link fetch failed", e)
            setStatus("Download failed. Expired/private stories need a logged-in viewer that can see them.")
            toast("Download failed. Try again.")
        }
    }

    /** Entire highlight = carousel of stories; every story (flattened) is saved. */
    private suspend fun downloadHighlight(resolved: InstagramLinks.Resolved.Highlight) {
        // Caller guarantees logged-in (checked above).
        try {
            setStatus("Fetching highlight…")
            val items = withContext(Dispatchers.IO) {
                client.fetchHighlightItems(resolved.highlightId)
            }.flatMap { it.flatten() }
            if (items.isEmpty()) {
                setStatus("No stories found in this highlight")
                return
            }
            val prefix = "highlight_${resolved.highlightId}"
            setStatus("Downloading ${items.size} story file(s)…")
            val saved = withContext(Dispatchers.IO) { downloadAll(items, prefix) }
            setStatus("Saved $saved of ${items.size} highlight files to your Photos/Downloads.")
            toast("Downloaded $saved files")
        } catch (e: Exception) {
            Log.w(TAG, "Highlight fetch failed", e)
            setStatus("Download failed. Private/removed highlights need a logged-in viewer that can see them.")
            toast("Download failed. Try again.")
        }
    }

    private fun downloadPublicFiles(files: List<PublicFile>, prefix: String): Int {
        val counter = AtomicInteger(0)
        files.forEachIndexed { index, file ->
            if (file.url.isBlank()) return@forEachIndexed
            try {
                val name = "${prefix}_${index + 1}.${if (file.isVideo) "mp4" else "jpg"}"
                DownloadManager.download(this, file.url, name, file.isVideo)
                counter.incrementAndGet()
            } catch (e: Exception) {
                Log.w(TAG, "Public file download failed", e)
            }
        }
        return counter.get()
    }

    private fun downloadAll(items: List<MediaItem>, prefix: String): Int {
        val counter = AtomicInteger(0)
        items.forEachIndexed { index, item ->
            val isVideo = item.mediaType == 2
            val url = if (isVideo) item.bestVideoUrl() else item.bestImageUrl()
            if (!url.isNullOrBlank()) {
                try {
                    val name = "${prefix}_${index + 1}.${if (isVideo) "mp4" else "jpg"}"
                    DownloadManager.download(this, url, name, isVideo)
                    counter.incrementAndGet()
                } catch (e: Exception) {
                    // continue with remaining items
                }
            }
        }
        return counter.get()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
