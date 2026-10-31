package com.example.instadl.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object DownloadManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    /**
     * Downloads the media at [url] into the app-created folder on shared storage.
     * Returns the display path of the saved file, or throws on failure.
     */
    @Throws(Exception::class)
    fun download(context: Context, url: String, fileName: String, isVideo: Boolean): String {
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Download failed HTTP ${response.code}")
        }
        val body = response.body ?: run {
            response.close()
            throw Exception("Empty response body")
        }
        val bytes = body.bytes()
        response.close()

        val safeName = sanitize(fileName)
        val relPath = "Instagram/${safeName}"
        val mime = if (isVideo) "video/mp4" else "image/jpeg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(
                context,
                relPath,
                mime,
                if (isVideo) "video/mp4" else "image/jpeg",
                bytes,
                isVideo)
        } else {
            saveToLegacyStorage(context, relPath, mime, bytes, isVideo)
        }
    }

    private fun saveToMediaStore(
        context: Context,
        relPath: String,
        mime: String,
        extension: String,
        bytes: ByteArray,
        isVideo: Boolean
    ): String {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, relPath.substringAfterLast('/'))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (isVideo) {
                    Environment.DIRECTORY_MOVIES + "/Instagram/"
                } else {
                    Environment.DIRECTORY_PICTURES + "/Instagram/"
                }
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(collection, values)
            ?: throw Exception("Could not create media store entry")

        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw Exception("Could not open output stream")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)

        return relPath
    }

    private fun saveToLegacyStorage(
        context: Context,
        relPath: String,
        mime: String,
        bytes: ByteArray,
        isVideo: Boolean
    ): String {
        val extDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(extDir, "Instagram")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, relPath.substringAfterLast('/'))
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "insta_${System.currentTimeMillis()}" }
    }
}
