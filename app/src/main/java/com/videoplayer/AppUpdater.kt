package com.videoplayer

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AppUpdater(private val activity: Activity, private val serverUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .cache(null)
        .build()

    private val downloading = AtomicBoolean(false)

    data class UpdateInfo(val versionCode: Int, val versionName: String, val apkName: String)

    private fun authRequest(url: String): Request {
        val token = activity.getSharedPreferences("janus_settings", Activity.MODE_PRIVATE)
            .getString("auth_token", null)
        val builder = Request.Builder().url(url)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder.build()
    }

    fun checkForUpdate(onResult: (UpdateInfo?) -> Unit) {
        Thread {
            try {
                val response = client.newCall(authRequest("$serverUrl/api/version")).execute()
                if (!response.isSuccessful) { activity.runOnUiThread { onResult(null) }; return@Thread }
                val json = JSONObject(response.body?.string() ?: "")
                val remoteCode = json.getInt("version_code")
                val remoteName = json.optString("version_name", "")
                val apkName = json.optString("apk", "janus.apk")
                @Suppress("DEPRECATION")
                val localCode = activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
                if (remoteCode > localCode) {
                    activity.runOnUiThread { onResult(UpdateInfo(remoteCode, remoteName, apkName)) }
                } else {
                    activity.runOnUiThread { onResult(null) }
                }
            } catch (e: Exception) {
                Log.d("AppUpdater", "Update check failed: ${e.message}")
                activity.runOnUiThread { onResult(null) }
            }
        }.start()
    }

    fun downloadAndInstall(apkName: String, onProgress: ((Int) -> Unit)? = null) {
        if (!downloading.compareAndSet(false, true)) {
            Log.d("AppUpdater", "Download already in progress")
            return
        }
        Thread {
            try {
                val apkFile = File(activity.cacheDir, "janus-update.apk")
                apkFile.delete()

                val response = client.newCall(authRequest("$serverUrl/api/update/$apkName")).execute()
                if (!response.isSuccessful) {
                    Log.e("AppUpdater", "Download failed: ${response.code}")
                    activity.runOnUiThread { onProgress?.invoke(-1) }
                    return@Thread
                }
                val totalBytes = response.body?.contentLength() ?: -1
                var downloaded = 0L
                apkFile.outputStream().use { out ->
                    response.body?.byteStream()?.let { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100) / totalBytes).toInt()
                                activity.runOnUiThread { onProgress?.invoke(pct) }
                            }
                        }
                    }
                }
                if (totalBytes > 0 && apkFile.length() != totalBytes) {
                    Log.e("AppUpdater", "Incomplete: ${apkFile.length()}/$totalBytes")
                    apkFile.delete()
                    activity.runOnUiThread { onProgress?.invoke(-1) }
                    return@Thread
                }
                Log.d("AppUpdater", "Downloaded ${apkFile.length() / 1024 / 1024}MB")
                activity.runOnUiThread { installApk(apkFile) }
            } catch (e: Exception) {
                Log.e("AppUpdater", "Download failed: ${e.message}")
                activity.runOnUiThread { onProgress?.invoke(-1) }
            } finally {
                downloading.set(false)
            }
        }.start()
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
