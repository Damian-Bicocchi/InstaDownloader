package com.example.instadl

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.instadl.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.aboutText.text = ABOUT_TEXT
        binding.btnCloseAbout.setOnClickListener { finish() }
    }

    companion object {
        private val ABOUT_TEXT = """
            Made by Damián Bicocchi.

            This app is an independent project and is not affiliated with, endorsed by, or sponsored by Instagram or Meta.

            RISKS AND WARNINGS
            • Using Instagram's private/internal API for automated requests violates Instagram's Terms of Service.
            • Logging in and downloading content can lead to your account being temporarily restricted or permanently disabled.
            • Prefer a disposable account you can afford to lose, never your main account.
            • Only download content you own or have explicit permission to use. Respect copyright, privacy, and other people's rights.
            • Do not redistribute downloaded content.
            • The app does not bulk-download a user's posts or stories; it only processes individual links you provide.
            • Instagram can change or block its endpoints at any time, so the app may stop working without notice.
            • This app is provided "as is", without warranty of any kind. You use it entirely at your own risk.

            SECURITY
            • Your password is entered only on Instagram's own login page in a WebView. This app never receives or stores it.
            • Session cookies are stored locally in encrypted storage and can be removed with "Clear session & cookies" or Logout.
            • Local sessions expire automatically after 30 days.
            • The developer does not collect or receive your credentials, cookies, or downloaded media.
        """.trimIndent()
    }
}
