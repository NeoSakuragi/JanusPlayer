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

        setupAccount()
        setupUpdates()
        setupDownloads()
        setupPlaybackSettings()
        setupDictionaries()
        setupAnkiSettings()
        setupFieldMappings()
    }

    private fun setupAccount() {
        val prefs = getSharedPreferences("janus_settings", MODE_PRIVATE)
        val username = prefs.getString("username", "unknown") ?: "unknown"
        val tvStatus = findViewById<TextView>(R.id.tvLogoutStatus)
        tvStatus.text = "Logged in as $username"

        findViewById<LinearLayout>(R.id.settingLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("This will clear your session and cached library.")
                .setPositiveButton("Logout") { _, _ ->
                    prefs.edit().remove("auth_token").remove("username").apply()
                    val intent = android.content.Intent(this, LibraryActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.putExtra("open_screen", "login")
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
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
                            updater.downloadAndInstall(info.apkName) { pct ->
                                if (pct >= 0) tvAppStatus.text = "Downloading... $pct%"
                                else tvAppStatus.text = "Download failed"
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

    private fun setupPlaybackSettings() {
        val tvHwdec = findViewById<TextView>(R.id.tvHwdecValue)
        tvHwdec.text = if (settings.hardwareDecoding) "On (hardware)" else "Off (software)"
        findViewById<LinearLayout>(R.id.settingHwdec).setOnClickListener {
            settings.hardwareDecoding = !settings.hardwareDecoding
            tvHwdec.text = if (settings.hardwareDecoding) "On (hardware)" else "Off (software)"
            Toast.makeText(this, "Restart app to apply", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAnkiSettings() {
        // Enable/disable
        val tvAnki = findViewById<TextView>(R.id.tvAnkiValue)
        tvAnki.text = if (settings.ankiEnabled) "Enabled" else "Disabled"
        findViewById<LinearLayout>(R.id.settingAnkiEnabled).setOnClickListener {
            settings.ankiEnabled = !settings.ankiEnabled
            tvAnki.text = if (settings.ankiEnabled) "Enabled" else "Disabled"
        }

        // URL
        val tvUrl = findViewById<TextView>(R.id.tvAnkiUrlValue)
        tvUrl.text = settings.ankiConnectUrl
        findViewById<LinearLayout>(R.id.settingAnkiUrl).setOnClickListener {
            showTextInput("AnkiConnect URL", "http://127.0.0.1:8765", settings.ankiConnectUrl) {
                settings.ankiConnectUrl = it; tvUrl.text = it
            }
        }

        // Test connection
        val tvTest = findViewById<TextView>(R.id.tvAnkiTestValue)
        findViewById<LinearLayout>(R.id.settingAnkiTest).setOnClickListener {
            tvTest.text = "Testing..."
            Thread {
                val client = AnkiConnectClient(settings.ankiConnectUrl)
                val result = client.testConnection()
                runOnUiThread {
                    tvTest.text = if (result.success) "Connected (v${result.data})" else "Failed: ${result.message}"
                }
            }.start()
        }

        // Deck - try to fetch from AnkiConnect, fallback to text input
        val tvDeck = findViewById<TextView>(R.id.tvAnkiDeckValue)
        tvDeck.text = settings.ankiDeck
        findViewById<LinearLayout>(R.id.settingAnkiDeck).setOnClickListener {
            Thread {
                val decks = AnkiConnectClient(settings.ankiConnectUrl).getDeckNames()
                runOnUiThread {
                    if (decks.isNotEmpty()) {
                        val selected = decks.indexOf(settings.ankiDeck).coerceAtLeast(0)
                        AlertDialog.Builder(this).setTitle("Select deck")
                            .setSingleChoiceItems(decks.toTypedArray(), selected) { d, w ->
                                settings.ankiDeck = decks[w]; tvDeck.text = decks[w]; d.dismiss()
                            }.setNegativeButton("Cancel", null).show()
                    } else {
                        showTextInput("Deck name", "Default", settings.ankiDeck) {
                            settings.ankiDeck = it; tvDeck.text = it
                        }
                    }
                }
            }.start()
        }

        // Note type - try to fetch from AnkiConnect
        val tvNoteType = findViewById<TextView>(R.id.tvAnkiNoteTypeValue)
        tvNoteType.text = settings.ankiNoteType
        findViewById<LinearLayout>(R.id.settingAnkiNoteType).setOnClickListener {
            Thread {
                val models = AnkiConnectClient(settings.ankiConnectUrl).getModelNames()
                runOnUiThread {
                    if (models.isNotEmpty()) {
                        val selected = models.indexOf(settings.ankiNoteType).coerceAtLeast(0)
                        AlertDialog.Builder(this).setTitle("Select note type")
                            .setSingleChoiceItems(models.toTypedArray(), selected) { d, w ->
                                settings.ankiNoteType = models[w]; tvNoteType.text = models[w]; d.dismiss()
                                // Refresh field mappings when note type changes
                                setupFieldMappings()
                            }.setNegativeButton("Cancel", null).show()
                    } else {
                        showTextInput("Note type", "Basic", settings.ankiNoteType) {
                            settings.ankiNoteType = it; tvNoteType.text = it
                        }
                    }
                }
            }.start()
        }

        // Tags
        val tvTags = findViewById<TextView>(R.id.tvAnkiTagsValue)
        tvTags.text = settings.ankiTags
        findViewById<LinearLayout>(R.id.settingAnkiTags).setOnClickListener {
            showTextInput("Tags (space-separated)", "janus mining", settings.ankiTags) {
                settings.ankiTags = it; tvTags.text = it
            }
        }
    }

    private fun setupFieldMappings() {
        val container = findViewById<LinearLayout>(R.id.fieldMappingsContainer)
        container.removeAllViews()

        for ((key, label) in AppSettings.ANKI_FIELD_KEYS) {
            val row = layoutInflater.inflate(android.R.layout.simple_list_item_2, container, false)
            val tv1 = row.findViewById<TextView>(android.R.id.text1)
            val tv2 = row.findViewById<TextView>(android.R.id.text2)

            tv1.text = label
            tv1.setTextColor(0xFFEEEEEE.toInt())
            tv1.textSize = 14f
            tv2.text = settings.getFieldMapping(key).ifEmpty { "(not mapped)" }
            tv2.setTextColor(0xFF888888.toInt())
            tv2.textSize = 12f

            row.setBackgroundResource(R.drawable.focus_highlight)
            row.isFocusable = true
            row.isClickable = true
            row.setPadding(32, 24, 32, 24)

            row.setOnClickListener {
                // Try to get field names from AnkiConnect for the current note type
                Thread {
                    val fields = AnkiConnectClient(settings.ankiConnectUrl)
                        .getModelFieldNames(settings.ankiNoteType)
                    runOnUiThread {
                        if (fields.isNotEmpty()) {
                            val options = listOf("(not mapped)") + fields
                            val current = settings.getFieldMapping(key)
                            val selected = if (current.isEmpty()) 0 else {
                                val idx = fields.indexOf(current)
                                if (idx >= 0) idx + 1 else 0
                            }
                            AlertDialog.Builder(this).setTitle("Map: $label")
                                .setSingleChoiceItems(options.toTypedArray(), selected) { d, w ->
                                    val value = if (w == 0) "" else fields[w - 1]
                                    settings.setFieldMapping(key, value)
                                    tv2.text = value.ifEmpty { "(not mapped)" }
                                    d.dismiss()
                                }.setNegativeButton("Cancel", null).show()
                        } else {
                            showTextInput("Field name for: $label", "", settings.getFieldMapping(key)) {
                                settings.setFieldMapping(key, it)
                                tv2.text = it.ifEmpty { "(not mapped)" }
                            }
                        }
                    }
                }.start()
            }

            container.addView(row)
        }
    }

    private fun setupDictionaries() {
        val container = findViewById<LinearLayout>(R.id.dictionariesContainer)
        container.removeAllViews()

        val dictDb = DictionaryDatabase.getInstance(this)
        val installed = dictDb.getInstalledDicts()
        val downloader = DictionaryDownloader(this)

        // Quick Setup button — downloads prebuilt DB with everything
        if (installed.isEmpty()) {
            val quickBtn = MaterialButton(this).apply {
                text = "Quick Setup (JMdict + Freq + Pitch + Kanji — 63MB)"
                setBackgroundColor(0xFF7986CB.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                isFocusable = true
            }
            val statusText = TextView(this).apply {
                setTextColor(0xFF81C784.toInt())
                textSize = 12f
                setPadding(0, 8, 0, 16)
                visibility = View.GONE
            }
            quickBtn.setOnClickListener {
                quickBtn.isEnabled = false
                quickBtn.text = "Downloading..."
                statusText.visibility = View.VISIBLE
                dictDb.downloadPrebuilt(
                    onProgress = { phase, pct ->
                        mainHandler.post { statusText.text = "$phase $pct%" }
                    },
                    onComplete = { success, msg ->
                        mainHandler.post {
                            if (success) {
                                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                                setupDictionaries()
                            } else {
                                statusText.text = "Failed: $msg"
                                quickBtn.isEnabled = true
                                quickBtn.text = "Retry Quick Setup"
                            }
                        }
                    }
                )
            }
            container.addView(quickBtn)
            container.addView(statusText)
        }

        for (entry in DictionaryCatalog.entries) {
            val row = layoutInflater.inflate(R.layout.item_dictionary, container, false)
            val tvName = row.findViewById<TextView>(R.id.tvDictName)
            val tvDesc = row.findViewById<TextView>(R.id.tvDictDesc)
            val tvSize = row.findViewById<TextView>(R.id.tvDictSize)
            val tvStatus = row.findViewById<TextView>(R.id.tvDictStatus)
            val progress = row.findViewById<ProgressBar>(R.id.dictProgress)
            val btnAction = row.findViewById<MaterialButton>(R.id.btnDictAction)

            tvName.text = entry.name
            tvDesc.text = entry.description
            tvSize.text = if (entry.sizeMb >= 1f) "%.0f MB".format(entry.sizeMb) else "%.1f MB".format(entry.sizeMb)

            val isInstalled = installed.contains(entry.id)

            if (isInstalled) {
                btnAction.text = "Delete"
                btnAction.setBackgroundColor(0xFF442222.toInt())
            } else {
                btnAction.text = "Download"
            }

            val clickAction = android.view.View.OnClickListener {
                if (installed.contains(entry.id) || dictDb.getInstalledDicts().contains(entry.id)) {
                    // Delete
                    AlertDialog.Builder(this)
                        .setTitle("Delete ${entry.name}?")
                        .setMessage("This will remove the dictionary data.")
                        .setPositiveButton("Delete") { _, _ ->
                            Thread {
                                dictDb.deleteDictionary(entry.id)
                                mainHandler.post { setupDictionaries() }
                            }.start()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    // Download
                    btnAction.isEnabled = false
                    btnAction.text = "..."
                    progress.visibility = View.VISIBLE
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "Starting..."

                    downloader.download(entry, object : DictionaryDownloader.ProgressListener {
                        override fun onProgress(phase: String, percent: Int) {
                            mainHandler.post {
                                tvStatus.text = phase
                                progress.progress = percent
                            }
                        }

                        override fun onComplete(success: Boolean, message: String) {
                            mainHandler.post {
                                progress.visibility = View.GONE
                                if (success) {
                                    tvStatus.text = message
                                    Toast.makeText(this@SettingsActivity, "${entry.name} installed", Toast.LENGTH_SHORT).show()
                                    setupDictionaries()
                                } else {
                                    tvStatus.text = "Failed: $message"
                                    tvStatus.setTextColor(0xFFFF5555.toInt())
                                    btnAction.isEnabled = true
                                    btnAction.text = "Retry"
                                }
                            }
                        }
                    })
                }
            }

            btnAction.setOnClickListener(clickAction)
            row.setOnClickListener(clickAction)
            container.addView(row)
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
