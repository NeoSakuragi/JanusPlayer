package com.videoplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Main"
    }

    private val browserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { handleBrowserResult(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browserLauncher.launch(Intent(this, BrowserActivity::class.java))
    }

    private fun handleBrowserResult(data: Intent) {
        val mode = data.getStringExtra(BrowserActivity.RESULT_MODE) ?: return
        when (mode) {
            "local" -> {
                val uri = data.getStringExtra(BrowserActivity.RESULT_LOCAL_URI) ?: return
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_LOCAL_URI, uri)
                })
            }
            "smb" -> {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_SMB_SERVER, data.getStringExtra(BrowserActivity.RESULT_SMB_SERVER))
                    putExtra(PlayerActivity.EXTRA_SMB_SHARE, data.getStringExtra(BrowserActivity.RESULT_SMB_SHARE))
                    putExtra(PlayerActivity.EXTRA_SMB_PATH, data.getStringExtra(BrowserActivity.RESULT_SMB_PATH))
                    putExtra(PlayerActivity.EXTRA_SMB_USER, data.getStringExtra(BrowserActivity.RESULT_SMB_USER) ?: "")
                    putExtra(PlayerActivity.EXTRA_SMB_PASS, data.getStringExtra(BrowserActivity.RESULT_SMB_PASS) ?: "")
                })
            }
        }
    }
}
