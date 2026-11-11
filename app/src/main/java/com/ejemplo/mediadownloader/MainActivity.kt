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
import java.io.File
import java.io.FileInputStream

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
        val rawUrl = regex.find(text)?.value?.trim()

        // Normalizar enlaces x.com a twitter.com para asegurar compatibilidad con extractores
        return rawUrl?.replace("x.com", "twitter.com")
    }

    private fun descargarVideo(url: String) {
        Toast.makeText(applicationContext, "Descargando vídeo...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Actualizar binarios de yt-dlp al canal estable si hay parches de red
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                } catch (e: Exception) {
                    Log.w("MediaDownloader", "No se pudo actualizar el ejecutable de yt-dlp", e)
                }

                val tempFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")

                val request = YoutubeDLRequest(url).apply {
                    addOption("-o", tempFile.absolutePath)
                    addOption("-f", "b/best")
                    addOption("--no-check-certificate")
                    addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    addOption("--referer", "https://twitter.com/")
                }

                YoutubeDL.getInstance().execute(request)

                if (tempFile.exists() && tempFile.length() > 0) {
                    val fileName = "MediaDownloader_${System.currentTimeMillis()}.mp4"
                    saveToPublicDownloads(tempFile, fileName)
                    tempFile.delete()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "¡Vídeo guardado en Descargas!", Toast.LENGTH_LONG).show()
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Error: El archivo procesado está vacío", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                val rawError = e.localizedMessage ?: e.message ?: "Error desconocido"
                val cleanError = if (rawError.contains("Traceback")) {
                    rawError.lines().lastOrNull { it.isNotBlank() } ?: "Fallo en el extractor de X"
                } else {
                    rawError
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Error: $cleanError", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
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
