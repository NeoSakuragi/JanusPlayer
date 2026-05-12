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

class AppUpdater(private val activity: Activity, private val serverUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .cache(null)
        .build()

    data class UpdateInfo(val versionCode: Int, val versionName: String, val apkName: String)

    fun checkForUpdate(onResult: (UpdateInfo?) -> Unit) {
        Thread {
            try {
                val request = Request.Builder().url("$serverUrl/api/version").build()
                val response = client.newCall(request).execute()
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
        Thread {
            try {
                val request = Request.Builder().url("$serverUrl/api/update/$apkName").build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@Thread
                val totalBytes = response.body?.contentLength() ?: -1
                var downloaded = 0L
                val apkFile = File(activity.cacheDir, "janus-update.apk")
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
                activity.runOnUiThread { installApk(apkFile) }
            } catch (e: Exception) {
                Log.e("AppUpdater", "Download failed: ${e.message}")
                activity.runOnUiThread { onProgress?.invoke(-1) }
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
