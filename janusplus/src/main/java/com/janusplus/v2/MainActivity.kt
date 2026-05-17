package com.janusplus.v2

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.janusplus.JanusApi
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var app: App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        app = App(this, assets, resources.displayMetrics.density)

        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(3)
        glView.setRenderer(app)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setContentView(glView)

        // Load library on background thread
        thread {
            val api = JanusApi("https://canneji.duckdns.org/janus")
            api.cacheDir = cacheDir
            val result = api.login("bruno", "janus2026")
            if (result != null) {
                app.api = api
                val library = api.fetchLibrary()
                app.library = library
                loadCovers(api, library)
            }
        }
    }

    private fun loadCovers(api: JanusApi, items: List<JanusApi.LibraryItem>) {
        val client = okhttp3.OkHttpClient()
        thread {
            val entries = mutableListOf<Pair<String, android.graphics.Bitmap>>()
            for (item in items) {
                try {
                    val request = okhttp3.Request.Builder().url(api.coverUrl(item.id))
                        .header("Authorization", "Bearer ${api.token}").build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) entries.add("cover_${item.id}" to bmp)
                        }
                    }
                    response.close()
                } catch (_: Exception) {}
            }
            if (entries.isNotEmpty()) app.coverAtlas.pack(entries, app.coverAtlas.layerIndex)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        app.onTouchEvent(event)
        return true
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!app.goBack()) super.onBackPressed()
    }

    override fun onResume() { super.onResume(); if (::glView.isInitialized) glView.onResume() }
    override fun onPause() { super.onPause(); if (::glView.isInitialized) glView.onPause() }
}
