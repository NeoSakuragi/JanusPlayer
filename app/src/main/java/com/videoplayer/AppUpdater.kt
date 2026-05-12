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

class AppUpdater(private val activity: Activity) {

    companion object {
        private const val UPDATE_BASE = "https://canneji.duckdns.org/janus"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(val versionCode: Int, val versionName: String, val apkName: String)

    fun checkForUpdate(onResult: (UpdateInfo?) -> Unit) {
        Thread {
            try {
                val request = Request.Builder().url("$UPDATE_BASE/version.json").build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) { activity.runOnUiThread { onResult(null) }; return@Thread }
                val json = JSONObject(response.body?.string() ?: "")
                val remoteCode = json.getInt("version_code")
                val remoteName = json.optString("version_name", "")
                val apkName = json.optString("apk", "janus.apk")
                val localCode = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode.toInt()
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

    fun downloadAndInstall(apkName: String) {
        Thread {
            try {
                val request = Request.Builder().url("$UPDATE_BASE/$apkName").build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@Thread
                val apkFile = File(activity.cacheDir, "janus-update.apk")
                apkFile.outputStream().use { out -> response.body?.byteStream()?.copyTo(out) }
                activity.runOnUiThread { installApk(apkFile) }
            } catch (e: Exception) {
                Log.e("AppUpdater", "Download failed: ${e.message}")
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
