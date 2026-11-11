package com.ejemplo.mediadownloader

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
        val regex = "(https?://[\\w-]+(\\.[\\w-]+)+[/#?]?.*)".toRegex()
        return regex.find(text)?.value?.trim()
    }

    private fun descargarVideo(url: String) {
        Toast.makeText(applicationContext, "Descargando vídeo...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Actualizar motor yt-dlp para soportar cambios en X/Twitter
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                } catch (_: Exception) {}

                // Archivo temporal en la caché interna de la app
                val tempFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")

                val request = YoutubeDLRequest(url).apply {
                    addOption("-o", tempFile.absolutePath)
                    addOption("-f", "best")
                }

                YoutubeDL.getInstance().execute(request)

                // Exportar a la carpeta pública de Descargas usando MediaStore
                val fileName = "MediaDownloader_${System.currentTimeMillis()}.mp4"
                saveToPublicDownloads(tempFile, fileName)

                tempFile.delete()

                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "¡Vídeo guardado en Descargas!", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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
