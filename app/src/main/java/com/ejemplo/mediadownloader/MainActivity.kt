package com.ejemplo.mediadownloader

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initYoutubeDL()

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            sharedText?.let { rawText ->
                val extractedUrl = extractUrl(rawText)
                if (extractedUrl != null) {
                    descargarVideo(extractedUrl)
                } else {
                    Toast.makeText(this, "No se encontró un enlace válido", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } ?: finish()
        } else {
            finish()
        }
    }

    private fun initYoutubeDL() {
        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
        } catch (_: Exception) {}
    }

    private fun extractUrl(text: String): String? {
        val regex = "(https?://[^\\s]+)".toRegex()
        val rawUrl = regex.find(text)?.value?.trim() ?: return null
        val cleanUrl = rawUrl.split("?").first()
        return cleanUrl.replace("x.com", "twitter.com")
    }

    private fun descargarVideo(url: String) {
        Toast.makeText(applicationContext, "Descargando vídeo...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
                var downloaded = false

                // 1. Descarga directa vía API de Twitter (bypassea los fallos de yt-dlp en X)
                val tweetId = getTweetId(url)
                if (tweetId != null) {
                    downloaded = descargarTwitterDirecto(tweetId, tempFile)
                }

                // 2. Si no es Twitter o falló la API directa, usar YoutubeDL como motor secundario
                if (!downloaded) {
                    val request = YoutubeDLRequest(url).apply {
                        addOption("-o", tempFile.absolutePath)
                        addOption("-f", "b/best")
                        addOption("--no-check-certificate")
                    }
                    YoutubeDL.getInstance().execute(request)
                    downloaded = tempFile.exists() && tempFile.length() > 0
                }

                if (downloaded && tempFile.exists() && tempFile.length() > 0) {
                    val fileName = "MediaDownloader_${System.currentTimeMillis()}.mp4"
                    saveToPublicDownloads(tempFile, fileName)
                    tempFile.delete()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "¡Vídeo guardado en Descargas!", Toast.LENGTH_LONG).show()
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "No se pudo extraer el vídeo del post", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                val rawError = e.localizedMessage ?: e.message ?: "Error desconocido"
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Error: ${rawError.take(60)}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun getTweetId(url: String): String? {
        val regex = "status/(\\d+)".toRegex()
        return regex.find(url)?.groupValues?.get(1)
    }

    private fun descargarTwitterDirecto(tweetId: String, tempFile: File): Boolean {
        return try {
            val apiUrl = "https://api.vxtwitter.com/i/status/$tweetId"
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)

                var mediaUrl: String? = null
                if (json.has("media_urls")) {
                    val mediaArray = json.getJSONArray("media_urls")
                    for (i in 0 until mediaArray.length()) {
                        val mUrl = mediaArray.getString(i)
                        if (mUrl.contains(".mp4") || mUrl.contains("video.twimg.com")) {
                            mediaUrl = mUrl
                            break
                        }
                    }
                }

                if (mediaUrl != null) {
                    URL(mediaUrl).openStream().use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    return tempFile.exists() && tempFile.length() > 0
                }
            }
            false
        } catch (e: Exception) {
            Log.e("MediaDownloader", "Fallback vxtwitter falló", e)
            false
        }
    }

    private fun saveToPublicDownloads(sourceFile: File, fileName: String) {
        val resolver = contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { destinationUri ->
            resolver.openOutputStream(destinationUri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }
}
