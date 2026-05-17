package com.videoplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        localizeLabels()
        setupAccount()
        setupTheme()
        setupUpdates()
        setupDownloads()
        setupPlaybackSettings()
    }

    private fun localizeLabels() {
        fun labelOf(container: LinearLayout): TextView? = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<TextView>()
            .firstOrNull()

        fun sectionOf(id: Int): TextView? = findViewById(id)

        // Title and section headers
        findViewById<TextView>(R.id.tvSettingsTitle)?.text = Lang.s("settings")
        findViewById<TextView>(R.id.sectionUpdates)?.text = Lang.s("updates")
        findViewById<TextView>(R.id.sectionPlayback)?.text = Lang.s("playback")
        // Section labels
        labelOf(findViewById(R.id.settingLogout))?.text = Lang.s("logout")
        labelOf(findViewById(R.id.settingCheckAppUpdate))?.text = Lang.s("check_app_update")
        labelOf(findViewById(R.id.settingCheckLibrary))?.text = Lang.s("check_library")
        labelOf(findViewById(R.id.settingServerUrl))?.text = Lang.s("server_url")
        labelOf(findViewById(R.id.settingDownloads))?.text = Lang.s("downloads")
        findViewById<TextView>(R.id.tvDownloadsStatus)?.text = Lang.s("manage_downloads")
        labelOf(findViewById(R.id.settingHwdec))?.text = Lang.s("hw_decoding")
        findViewById<TextView>(R.id.tvAppUpdateStatus)?.text = Lang.s("tap_to_check")
        findViewById<TextView>(R.id.tvLibraryUpdateStatus)?.text = Lang.s("tap_to_check")
    }

    private fun setupAccount() {
        val prefs = getSharedPreferences("janus_settings", MODE_PRIVATE)
        val username = prefs.getString("username", "unknown") ?: "unknown"
        val tvStatus = findViewById<TextView>(R.id.tvLogoutStatus)
        tvStatus.text = Lang.s("logged_in_as", username)

        findViewById<LinearLayout>(R.id.settingLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(Lang.s("logout"))
                .setMessage("")
                .setPositiveButton(Lang.s("logout")) { _, _ ->
                    prefs.edit().remove("auth_token").remove("username").apply()
                    val intent = android.content.Intent(this, LibraryActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.putExtra("open_screen", "login")
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton(Lang.s("cancel"), null)
                .show()
        }
    }

    private fun setupDownloads() {
        DownloadManager.init(this)
        val tvStatus = findViewById<TextView>(R.id.tvDownloadsStatus)
        val count = DownloadManager.items.size
        val completed = DownloadManager.items.count { it.state == DownloadManager.State.COMPLETED }
        val active = DownloadManager.items.count { it.state == DownloadManager.State.DOWNLOADING || it.state == DownloadManager.State.QUEUED }
        val totalMb = DownloadManager.totalDiskUsage() / (1024 * 1024)
        tvStatus.text = when {
            count == 0 -> "No downloads"
            active > 0 -> "$active downloading, $completed completed ($totalMb MB)"
            else -> "$completed episodes ($totalMb MB)"
        }
        findViewById<LinearLayout>(R.id.settingDownloads).setOnClickListener {
            // Open downloads screen in LibraryActivity
            val intent = android.content.Intent(this, LibraryActivity::class.java)
            intent.putExtra("open_screen", "downloads")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        }
    }

    private fun setupUpdates() {
        val prefs = getSharedPreferences("janus_settings", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "http://10.0.2.2:8900") ?: "http://10.0.2.2:8900"

        // Server URL
        val tvServerUrl = findViewById<TextView>(R.id.tvServerUrlValue)
        tvServerUrl.text = serverUrl
        findViewById<LinearLayout>(R.id.settingServerUrl).setOnClickListener {
            showTextInput("Server URL", "http://192.168.1.29:8900", tvServerUrl.text.toString()) { url ->
                var newUrl = url.trim()
                if (newUrl.isNotEmpty() && !newUrl.startsWith("http")) newUrl = "http://$newUrl"
                prefs.edit().putString("server_url", newUrl).apply()
                tvServerUrl.text = newUrl
            }
        }

        // App update check
        val tvAppStatus = findViewById<TextView>(R.id.tvAppUpdateStatus)
        @Suppress("DEPRECATION")
        val currentCode = packageManager.getPackageInfo(packageName, 0).versionCode
        val currentName = packageManager.getPackageInfo(packageName, 0).versionName
        tvAppStatus.text = "Current: v$currentName (code $currentCode)"

        findViewById<LinearLayout>(R.id.settingCheckAppUpdate).setOnClickListener {
            tvAppStatus.text = "Checking..."
            val currentUrl = prefs.getString("server_url", serverUrl) ?: serverUrl
            val updater = AppUpdater(this, currentUrl)
            updater.checkForUpdate { info ->
                if (info != null) {
                    tvAppStatus.text = "Update available: v${info.versionName}"
                    tvAppStatus.setTextColor(0xFF81C784.toInt())
                    AlertDialog.Builder(this)
                        .setTitle("Update Available")
                        .setMessage("New version v${info.versionName} available.\nCurrent: v$currentName\n\nInstall now?")
                        .setPositiveButton("Install") { _, _ ->
                            tvAppStatus.text = "Downloading... 0%"
                            updater.downloadAndInstall(info) { pct ->
                                when {
                                    pct > 100 -> tvAppStatus.text = "Installing..."
                                    pct >= 0 -> tvAppStatus.text = "Downloading... $pct%"
                                    else -> tvAppStatus.text = "Download failed"
                                }
                            }
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    tvAppStatus.text = "Up to date (v$currentName)"
                    tvAppStatus.setTextColor(0xFF888888.toInt())
                }
            }
        }

        // Library update check
        val tvLibStatus = findViewById<TextView>(R.id.tvLibraryUpdateStatus)
        val lastSeenLibrary = prefs.getLong("library_last_modified", 0)
        tvLibStatus.text = if (lastSeenLibrary > 0) "Last synced: ${android.text.format.DateUtils.getRelativeTimeSpanString(lastSeenLibrary * 1000)}" else "Never synced"

        findViewById<LinearLayout>(R.id.settingCheckLibrary).setOnClickListener {
            tvLibStatus.text = "Checking..."
            val currentUrl = prefs.getString("server_url", serverUrl) ?: serverUrl
            Thread {
                try {
                    val reqBuilder = okhttp3.Request.Builder().url("$currentUrl/api/library")
                    prefs.getString("auth_token", null)?.let { reqBuilder.header("Authorization", "Bearer $it") }
                    val response = okhttp3.OkHttpClient().newCall(reqBuilder.build()).execute()
                    if (!response.isSuccessful) {
                        runOnUiThread { tvLibStatus.text = "Failed: server unreachable"; tvLibStatus.setTextColor(0xFFFF5252.toInt()) }
                        return@Thread
                    }
                    val json = org.json.JSONObject(response.body?.string() ?: "")
                    val serverTimestamp = json.optLong("last_modified", 0)
                    runOnUiThread {
                        if (serverTimestamp > lastSeenLibrary) {
                            tvLibStatus.text = "New content available"
                            tvLibStatus.setTextColor(0xFF81C784.toInt())
                            AlertDialog.Builder(this)
                                .setTitle("Library Updated")
                                .setMessage("New content is available on the server.\n\nRefresh library?")
                                .setPositiveButton("Refresh") { _, _ ->
                                    prefs.edit().putLong("library_last_modified", serverTimestamp).apply()
                                    tvLibStatus.text = "Library refreshed"
                                    setResult(RESULT_OK)
                                }
                                .setNegativeButton("Later", null)
                                .show()
                        } else {
                            tvLibStatus.text = "Library is current"
                            tvLibStatus.setTextColor(0xFF888888.toInt())
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { tvLibStatus.text = "Failed: ${e.message}"; tvLibStatus.setTextColor(0xFFFF5252.toInt()) }
                }
            }.start()
        }
    }

    private fun setupTheme() {
        val tvTheme = findViewById<TextView>(R.id.tvThemeValue)
        tvTheme.text = if (settings.darkMode) "Dark" else "Light"
        findViewById<LinearLayout>(R.id.settingTheme).setOnClickListener {
            settings.darkMode = !settings.darkMode
            tvTheme.text = if (settings.darkMode) "Dark" else "Light"
        }
    }

    private fun setupPlaybackSettings() {
        val tvHwdec = findViewById<TextView>(R.id.tvHwdecValue)
        tvHwdec.text = if (settings.hardwareDecoding) "On (hardware)" else "Off (software)"
        findViewById<LinearLayout>(R.id.settingHwdec).setOnClickListener {
            settings.hardwareDecoding = !settings.hardwareDecoding
            tvHwdec.text = if (settings.hardwareDecoding) "On (hardware)" else "Off (software)"
            Toast.makeText(this, "Restart app to apply", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTextInput(title: String, hint: String, current: String, onSet: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint; setText(current); setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> onSet(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }
}
