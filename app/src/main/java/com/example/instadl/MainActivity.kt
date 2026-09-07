package com.example.instadl

import android.Manifest
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

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
        binding.inputUsername.isVisible = !logged
        binding.inputPassword.isVisible = !logged
        binding.btnLogin.isVisible = !logged
        binding.btnLogout.isVisible = logged
        if (logged) {
            binding.etUsername.text?.clear()
            binding.etPassword.text?.clear()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.isVisible = loading
        val buttons = listOf(binding.btnLogin, binding.btnLogout, binding.btnDownloadPosts, binding.btnDownloadStories)
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
                val items = feed.items ?: emptyList()
                if (items.isEmpty()) {
                    setStatus("No posts found for @$target")
                    return@launch
                }
                setStatus("Downloading ${items.size} item(s)…")
                val saved = downloadAll(items, "$target")
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
                val items = feed.items ?: emptyList()
                if (items.isEmpty()) {
                    setStatus("No active stories found for @$target")
                    return@launch
                }
                setStatus("Downloading ${items.size} story item(s)…")
                val saved = downloadAll(items, "${target}_story")
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
