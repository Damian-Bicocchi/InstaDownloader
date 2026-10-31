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
 * Single-screen controller: login (encrypted session), username-based
 * posts/stories downloads, and single-link downloads (public-first embed
 * fetch, authenticated fallback for private media, login-gated stories /
 * highlights).
 *
 * Hardening kept in place: FLAG_SECURE on the password screen, CharArray
 * passwords wiped immediately, generic UI errors with redacted Log details,
 * EncryptedSharedPreferences session, best-effort server logout with
 * always-wipe-local.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: SessionStore
    private lateinit var client: InstagramClient

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

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
        client = InstagramClient(session)

        updateLoginUi()
        requestStoragePermissions()

        binding.btnLogin.setOnClickListener { performLogin() }
        binding.btnLogout.setOnClickListener { performLogout() }
        binding.btnDownloadPosts.setOnClickListener { downloadPosts() }
        binding.btnDownloadStories.setOnClickListener { downloadStories() }
        binding.btnDownloadLink.setOnClickListener {
            downloadFromLink(binding.etLink.text.toString())
        }
        binding.btnClearUsername.setOnClickListener { binding.etUsername.text?.clear() }
        binding.btnClearPassword.setOnClickListener { binding.etPassword.text?.clear() }
        binding.btnClearTarget.setOnClickListener { binding.etTarget.text?.clear() }
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
        binding.tvLoginStatus.text = if (logged) {
            "Logged in as @${session.username ?: session.fullName ?: "user"}"
        } else {
            "Not logged in"
        }
        binding.rowUsername.isVisible = !logged
        binding.rowPassword.isVisible = !logged
        binding.btnLogin.isVisible = !logged
        binding.btnLogout.isVisible = logged
        if (logged) {
            binding.etUsername.text?.clear()
            binding.etPassword.text?.clear()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.isVisible = loading
        val buttons = listOf(binding.btnLogin, binding.btnLogout, binding.btnDownloadPosts, binding.btnDownloadStories, binding.btnDownloadLink)
        buttons.forEach { it.isEnabled = !loading }
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun performLogin() {
        val username = binding.etUsername.text.toString().trim()
        // Copy password out of the Editable and wipe the field immediately
        // so the secret lives in memory for the shortest possible time.
        val passwordEditable = binding.etPassword.text
        val password: CharArray = if (passwordEditable == null) {
            CharArray(0)
        } else {
            val len = passwordEditable.length
            val arr = CharArray(len)
            for (i in 0 until len) arr[i] = passwordEditable[i]
            arr
        }
        binding.etPassword.text?.clear()
        if (username.isBlank() || password.isEmpty()) {
            wipe(password)
            toast("Enter username and password")
            return
        }
        setLoading(true)
        setStatus("Logging in…")
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) { client.login(username, password) }
                if (result.status == "ok" && result.loggedInUser != null) {
                    session.saveSession(
                        cookies = session.cookies ?: "",
                        userId = result.loggedInUser.pk ?: "",
                        username = result.loggedInUser.username ?: username,
                        fullName = result.loggedInUser.fullName ?: ""
                    )
                    setStatus("Login successful")
                    updateLoginUi()
                    toast("Logged in as @${result.loggedInUser.username}")
                } else {
                    Log.w(TAG, "Login failed: status=${result.status} error=${result.errorType}")
                    setStatus("Login failed. Check credentials.")
                    toast("Login failed. Check credentials." +
                        if (result.twoFactorRequired == true) " (2FA is not supported)" else ""
                    )
                }
            } catch (e: Exception) {
                // Never echo raw exception (may contain username / server internals).
                Log.w(TAG, "Login error", e)
                setStatus("Login failed. Check connection and try again.")
                toast("Login failed. Check connection and try again.")
            } finally {
                wipe(password)
                setLoading(false)
            }
        }
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

    private fun downloadPosts() {
        if (!requireLoggedIn()) return
        val target = binding.etTarget.text.toString().trim()
        if (target.isBlank()) {
            toast("Enter a username to download from")
            return
        }
        setLoading(true)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                setStatus("Resolving @$target…")
                val user = withContext(Dispatchers.IO) { client.fetchUserByUsername(target) }
                val pk = user.pk ?: throw Exception("Could not resolve user id")
                setStatus("Fetching posts of @$target…")
                val feed = withContext(Dispatchers.IO) { client.fetchUserMediaPk(pk) }
                val items = (feed.items ?: emptyList()).flatMap { it.flatten() }
                if (items.isEmpty()) {
                    setStatus("No posts found for @$target")
                    return@launch
                }
                setStatus("Downloading ${items.size} item(s)…")
                val saved = withContext(Dispatchers.IO) { downloadAll(items, "$target") }
                setStatus("Saved $saved of ${items.size} files to your Photos/Downloads.")
                toast("Downloaded $saved files")
            } catch (e: Exception) {
                Log.w(TAG, "Download posts failed", e)
                setStatus("Download failed. Try again.")
                toast("Download failed. Try again.")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun downloadStories() {
        if (!requireLoggedIn()) return
        val target = binding.etTarget.text.toString().trim()
        if (target.isBlank()) {
            toast("Enter a username to download stories from")
            return
        }
        setLoading(true)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                setStatus("Resolving @$target…")
                val user = withContext(Dispatchers.IO) { client.fetchUserByUsername(target) }
                val pk = user.pk ?: throw Exception("Could not resolve user id")
                setStatus("Fetching current stories of @$target…")
                val feed = withContext(Dispatchers.IO) { client.fetchStories(pk) }
                val items = (feed.items ?: emptyList()).flatMap { it.flatten() }
                if (items.isEmpty()) {
                    setStatus("No active stories found for @$target")
                    return@launch
                }
                setStatus("Downloading ${items.size} story item(s)…")
                val saved = withContext(Dispatchers.IO) { downloadAll(items, "${target}_story") }
                setStatus("Saved $saved of ${items.size} story files.")
                toast("Downloaded $saved stories")
            } catch (e: Exception) {
                Log.w(TAG, "Download stories failed", e)
                setStatus("Download failed. Try again.")
                toast("Download failed. Try again.")
            } finally {
                setLoading(false)
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

    private fun requireLoggedIn(): Boolean {
        if (!session.isLoggedIn) {
            toast("Please log in first")
            return false
        }
        return true
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    private fun wipe(secret: CharArray) {
        secret.fill('\u0000')
    }
}
