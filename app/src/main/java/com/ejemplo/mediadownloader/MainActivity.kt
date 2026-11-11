package com.ejemplo.mediadownloader

import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
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
        return regex.find(text)?.value
    }

    private fun descargarVideo(url: String) {
        Toast.makeText(applicationContext, "Descargando vídeo...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val downloadDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) 
                    ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

                if (!downloadDir.exists()) downloadDir.mkdirs()

                val outputFile = File(downloadDir, "video_${System.currentTimeMillis()}.mp4")

                val request = YoutubeDLRequest(url).apply {
                    addOption("-o", outputFile.absolutePath)
                    addOption("-f", "b")
                }

                YoutubeDL.getInstance().execute(request)

                // Forzar al sistema Android a registrar el archivo en la galería / Google Fotos
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "¡Vídeo guardado correctamente!", Toast.LENGTH_LONG).show()
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
}
