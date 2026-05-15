package com.janusplus

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AppUpdater(private val activity: Activity, private val serverUrl: String) {

    data class UpdateInfo(val versionCode: Int, val versionName: String, val size: Long, val sha256: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val downloading = AtomicBoolean(false)
    var token: String? = null

    fun checkForUpdate(onResult: (UpdateInfo?) -> Unit) {
        thread {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/version/plus")
                    .header("Authorization", "Bearer ${token ?: ""}")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) { onResult(null); return@thread }
                val json = JSONObject(response.body?.string() ?: "")
                val remoteCode = json.optInt("version_code", 0)
                val localCode = activity.packageManager
                    .getPackageInfo(activity.packageName, 0).versionCode

                if (remoteCode > localCode) {
                    onResult(UpdateInfo(
                        versionCode = remoteCode,
                        versionName = json.optString("version_name", ""),
                        size = json.optLong("size", 0),
                        sha256 = json.optString("sha256", ""),
                    ))
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                Log.e("AppUpdater", "Check failed: ${e.message}")
                onResult(null)
            }
        }
    }

    fun downloadAndInstall(info: UpdateInfo, onProgress: ((Int) -> Unit)? = null) {
        if (!downloading.compareAndSet(false, true)) return
        thread {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/update/plus")
                    .header("Authorization", "Bearer ${token ?: ""}")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) { onProgress?.invoke(-1); downloading.set(false); return@thread }

                val apkFile = File(activity.cacheDir, "janusplus-update.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                val body = response.body ?: run { onProgress?.invoke(-1); downloading.set(false); return@thread }
                val total = body.contentLength()
                var downloaded = 0L

                apkFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            downloaded += n
                            if (total > 0) onProgress?.invoke((downloaded * 100 / total).toInt())
                        }
                    }
                }

                // Verify
                if (info.size > 0 && apkFile.length() != info.size) {
                    Log.e("AppUpdater", "Size mismatch: ${apkFile.length()} != ${info.size}")
                    apkFile.delete()
                    onProgress?.invoke(-1)
                    downloading.set(false)
                    return@thread
                }
                if (info.sha256.isNotEmpty()) {
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hash != info.sha256) {
                        Log.e("AppUpdater", "SHA mismatch: $hash != ${info.sha256}")
                        apkFile.delete()
                        onProgress?.invoke(-1)
                        downloading.set(false)
                        return@thread
                    }
                }

                onProgress?.invoke(101)
                activity.runOnUiThread { installApk(apkFile) }
            } catch (e: Exception) {
                Log.e("AppUpdater", "Download failed: ${e.message}")
                onProgress?.invoke(-1)
            }
            downloading.set(false)
        }
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
