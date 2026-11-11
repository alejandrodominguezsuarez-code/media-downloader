package com.ejemplo.mediadownloader

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Log.d("MediaDownloader", "YoutubeDL inicializado correctamente")
        } catch (e: Exception) {
            Log.e("MediaDownloader", "Error al inicializar YoutubeDL", e)
        }
    }
}
