package com.ejemplo.mediadownloader

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            sharedText?.let { rawText ->
                val extractedUrl = extractUrl(rawText)
                if (extractedUrl != null) {
                    descargarVideo(extractedUrl, finishOnComplete = true)
                } else {
                    Toast.makeText(this, "No se encontró un enlace de X válido", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } ?: finish()
        } else {
            setContentView(R.layout.activity_main)
            etUrl = findViewById(R.id.etUrl)
            btnDownload = findViewById(R.id.btnDownload)

            comprobarPortapapeles()

            btnDownload.setOnClickListener {
                val inputUrl = etUrl.text.toString().trim()
                val extractedUrl = extractUrl(inputUrl)
                if (extractedUrl != null) {
                    descargarVideo(extractedUrl, finishOnComplete = false)
                } else {
                    Toast.makeText(this, "Introduce o pega una URL de X válida", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun comprobarPortapapeles() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = clipboard?.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString() ?: ""
            val extracted = extractUrl(text)
            if (extracted != null) {
                etUrl.setText(extracted)
                Toast.makeText(this, "Enlace de X detectado del portapapeles", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = "(https?://[^\\s]+)".toRegex()
        return regex.find(text)?.value?.trim()
    }

    private fun resolveShortUrl(url: String): String {
        if (!url.contains("t.co/")) return url
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val location = conn.getHeaderField("Location")
            location ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun descargarVideo(url: String, finishOnComplete: Boolean = true) {
        Toast.makeText(applicationContext, "Descargando vídeo de X...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resolvedUrl = resolveShortUrl(url)
                val cleanUrl = resolvedUrl.split("?").first().replace("x.com", "twitter.com")

                val tempFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
                var downloaded = false

                val tweetId = getTweetId(cleanUrl)
                if (tweetId != null) {
                    downloaded = descargarTwitterNativo(tweetId, tempFile)
                }

                if (downloaded && tempFile.exists() && tempFile.length() > 0) {
                    val fileName = "X_Download_${System.currentTimeMillis()}.mp4"
                    saveToPublicDownloads(tempFile, fileName)
                    tempFile.delete()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "¡Vídeo de X guardado en Descargas!", Toast.LENGTH_LONG).show()
                        if (finishOnComplete) finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "No se pudo extraer el vídeo de este tuit", Toast.LENGTH_LONG).show()
                        if (finishOnComplete) finish()
                    }
                }
            } catch (e: Exception) {
                val rawError = e.localizedMessage ?: e.message ?: "Error desconocido"
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Error: ${rawError.take(60)}", Toast.LENGTH_LONG).show()
                    if (finishOnComplete) finish()
                }
            }
        }
    }

    private fun getTweetId(url: String): String? {
        val regex = "status/(\\d+)".toRegex()
        return regex.find(url)?.groupValues?.get(1)
    }

    private fun descargarTwitterNativo(tweetId: String, tempFile: File): Boolean {
        var videoUrl = obtenerUrlFxTwitter(tweetId)
        if (videoUrl == null) {
            videoUrl = obtenerUrlVxTwitter(tweetId)
        }
        return if (videoUrl != null) descargarArchivoDirecto(videoUrl, tempFile) else false
    }

    private fun obtenerUrlFxTwitter(tweetId: String): String? {
        return try {
            val apiUrl = "https://api.fxtwitter.com/status/$tweetId"
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                if (json.optInt("code") == 200 && json.has("tweet")) {
                    val tweet = json.getJSONObject("tweet")
                    if (tweet.has("media")) {
                        val media = tweet.getJSONObject("media")
                        if (media.has("videos")) {
                            val videos = media.getJSONArray("videos")
                            if (videos.length() > 0) {
                                return videos.getJSONObject(0).getString("url")
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e("MediaDownloader", "FxTwitter falló", e)
            null
        }
    }

    private fun obtenerUrlVxTwitter(tweetId: String): String? {
        return try {
            val apiUrl = "https://api.vxtwitter.com/status/$tweetId"
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                if (json.has("media_urls")) {
                    val mediaArray = json.getJSONArray("media_urls")
                    for (i in 0 until mediaArray.length()) {
                        val mUrl = mediaArray.getString(i)
                        if (mUrl.contains(".mp4") || mUrl.contains("video.twimg.com")) {
                            return mUrl
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e("MediaDownloader", "VxTwitter falló", e)
            null
        }
    }

    private fun descargarArchivoDirecto(fileUrl: String, tempFile: File): Boolean {
        return try {
            val conn = URL(fileUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.exists() && tempFile.length() > 0
        } catch (e: Exception) {
            Log.e("MediaDownloader", "Descarga de stream directo falló", e)
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
