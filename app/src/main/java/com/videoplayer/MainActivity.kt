package com.videoplayer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Legacy launcher — redirects to LibraryActivity.
 * Kept for backward compatibility with intent filters.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, LibraryActivity::class.java))
        finish()
    }
}
