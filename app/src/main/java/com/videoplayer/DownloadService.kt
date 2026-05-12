package com.videoplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "janus_downloads"
        private const val NOTIFICATION_ID = 42
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            startForeground(NOTIFICATION_ID, buildNotification("Starting downloads..."))
            Thread { processQueue() }.start()
        }
        return START_STICKY
    }

    private fun processQueue() {
        while (true) {
            val item = DownloadManager.getNextQueued()
            if (item == null) {
                Log.d(TAG, "Queue empty, stopping")
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            val seriesDir = File(DownloadManager.getDownloadsDir(), item.seriesId)
            seriesDir.mkdirs()

            // Download SRT files first (small)
            for ((srtName, srtUrl) in item.srtFiles) {
                val srtFile = File(seriesDir, srtName)
                if (!srtFile.exists()) {
                    try {
                        downloadFile(srtUrl, srtFile, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "SRT download failed: $srtName: ${e.message}")
                    }
                }
            }

            // Download video
            val videoFile = File(seriesDir, item.videoFilename)
            val tmpFile = File(seriesDir, "${item.videoFilename}.tmp")

            try {
                val label = if (item.seriesTitleEn.isNotEmpty())
                    "${item.seriesTitleEn} - ${item.titleEn}"
                else item.titleEn

                updateNotification("Downloading $label")

                downloadFile(item.videoUrl, videoFile, tmpFile) { bytesRead, totalBytes ->
                    val progress = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt() else 0
                    DownloadManager.updateProgress(item.seriesId, item.episodeNum, progress, bytesRead, totalBytes)
                    if (bytesRead % (5 * 1024 * 1024) < 65536) {
                        updateNotification("$label — $progress%")
                    }
                }

                DownloadManager.markCompleted(item.seriesId, item.episodeNum)
                Log.d(TAG, "Completed: ${item.seriesId} ep${item.episodeNum}")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${item.seriesId} ep${item.episodeNum}: ${e.message}")
                DownloadManager.markFailed(item.seriesId, item.episodeNum)
            }
        }
    }

    private fun downloadFile(
        url: String, destFile: File, tmpFile: File?,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        if (destFile.exists() && destFile.length() > 0) return

        val workFile = tmpFile ?: destFile
        val existingBytes = if (workFile.exists()) workFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw Exception("HTTP ${response.code}")
        }

        val contentLength = response.body?.contentLength() ?: -1
        val totalBytes = if (response.code == 206) existingBytes + contentLength else contentLength
        var bytesRead = existingBytes

        val outputStream = if (response.code == 206) workFile.outputStream().apply {
            channel.position(existingBytes)
        } else workFile.outputStream()

        response.body?.byteStream()?.use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    bytesRead += read
                    onProgress?.invoke(bytesRead, totalBytes)
                }
            }
        }

        if (tmpFile != null && tmpFile.exists()) {
            tmpFile.renameTo(destFile)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Janus")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
