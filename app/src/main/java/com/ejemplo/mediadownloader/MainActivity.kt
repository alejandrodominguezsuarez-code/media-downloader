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

    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initYoutubeDL()

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            sharedText?.let { rawText ->
                val extractedUrl = extractUrl(rawText)
                if (extractedUrl != null) {
                    descargarVideo(extractedUrl, finishOnComplete = true)
                } else {
                    Toast.makeText(this, "No se encontró un enlace válido", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "Introduce o pega una URL válida", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initYoutubeDL(): Boolean {
        return try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            true
        } catch (e: Exception) {
            Log.w("MediaDownloader", "Error al inicializar YoutubeDL: ${e.message}")
            false
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
                Toast.makeText(this, "Enlace detectado del portapapeles", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = "(https?://[^\\s]+)".toRegex()
        val rawUrl = regex.find(text)?.value?.trim() ?: return null
        val cleanUrl = rawUrl.split("?").first()
        return if (cleanUrl.contains("x.com")) {
            cleanUrl.replace("x.com", "twitter.com")
        } else {
            cleanUrl
        }
    }

    private fun descargarVideo(url: String, finishOnComplete: Boolean = true) {
        Toast.makeText(applicationContext, "Descargando vídeo...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
                var downloaded = false

                // 1. Extracción rápida para X/Twitter (FxTwitter / VxTwitter)
                val tweetId = getTweetId(url)
                if (tweetId != null) {
                    downloaded = descargarTwitterNativo(tweetId, tempFile)
                }

                // 2. Extracción para Instagram u otras plataformas mediante Cobalt API
                if (!downloaded && url.contains("instagram.com")) {
                    downloaded = descargarConCobalt(url, tempFile)
                    if (!downloaded) {
                        downloaded = descargarInstagramNativo(url, tempFile)
                    }
                }

                // 3. Extractor de respaldo YoutubeDL si el motor API falló o es otra web
                if (!downloaded) {
                    if (initYoutubeDL()) {
                        try {
                            val request = YoutubeDLRequest(url).apply {
                                addOption("-o", tempFile.absolutePath)
                                addOption("-f", "b/best")
                                addOption("--no-check-certificate")
                            }
                            YoutubeDL.getInstance().execute(request)
                            downloaded = tempFile.exists() && tempFile.length() > 0
                        } catch (e: Exception) {
                            Log.e("MediaDownloader", "Fallback YoutubeDL falló: ${e.message}")
                        }
                    }
                }

                if (downloaded && tempFile.exists() && tempFile.length() > 0) {
                    val fileName = "MediaDownloader_${System.currentTimeMillis()}.mp4"
                    saveToPublicDownloads(tempFile, fileName)
                    tempFile.delete()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "¡Vídeo guardado en Descargas!", Toast.LENGTH_LONG).show()
                        if (finishOnComplete) finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "No se pudo extraer el vídeo (cuenta privada o enlace no soportado)", Toast.LENGTH_LONG).show()
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

    private fun descargarConCobalt(targetUrl: String, tempFile: File): Boolean {
        return try {
            val apiUrl = "https://co.wuk.sh/api/json"
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.doOutput = true

            val jsonBody = JSONObject().apply {
                put("url", targetUrl)
                put("vQuality", "720")
            }

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseStr)
                val status = json.optString("status")
                if (status == "stream" || status == "redirect") {
                    val mediaUrl = json.getString("url")
                    return descargarArchivoDirecto(mediaUrl, tempFile)
                }
            }
            false
        } catch (e: Exception) {
            Log.e("MediaDownloader", "Cobalt API falló", e)
            false
        }
    }

    private fun descargarInstagramNativo(url: String, tempFile: File): Boolean {
        return try {
            val cleanInstaUrl = url.replace("instagram.com", "ddinstagram.com")
            val conn = URL(cleanInstaUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            if (conn.responseCode == 200) {
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                val regexVideo = "<meta\\s+property=\"og:video(?::secure_url)?\"\\s+content=\"([^\"]+)\"".toRegex()
                val match = regexVideo.find(html)
                val videoUrl = match?.groupValues?.get(1)?.replace("&", "&")

                if (videoUrl != null) {
                    return descargarArchivoDirecto(videoUrl, tempFile)
                }
            }
            false
        } catch (e: Exception) {
            Log.e("MediaDownloader", "Instagram nativo falló", e)
            false
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
