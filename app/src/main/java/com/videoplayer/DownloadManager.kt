package com.videoplayer

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val STATE_FILE = "downloads.json"

    enum class State { QUEUED, DOWNLOADING, COMPLETED, FAILED }

    data class DownloadItem(
        val seriesId: String,
        val episodeNum: Int,
        val videoFilename: String,
        val videoUrl: String,
        val srtFiles: List<Pair<String, String>>,
        var state: State = State.QUEUED,
        var progress: Int = 0,
        var bytesDownloaded: Long = 0,
        var totalBytes: Long = 0,
        var titleEn: String = "",
        var seriesTitleEn: String = "",
    )

    val items = mutableStateListOf<DownloadItem>()
    private lateinit var downloadsDir: File

    fun init(context: Context) {
        downloadsDir = File(context.getExternalFilesDir(null), "downloads")
        downloadsDir.mkdirs()
        loadState()
    }

    fun getDownloadsDir(): File = downloadsDir

    fun enqueueEpisode(
        seriesId: String, episodeNum: Int, videoFilename: String,
        videoUrl: String, srtFiles: List<Pair<String, String>>,
        titleEn: String = "", seriesTitleEn: String = ""
    ) {
        if (items.any { it.seriesId == seriesId && it.episodeNum == episodeNum }) return
        items.add(DownloadItem(
            seriesId = seriesId, episodeNum = episodeNum,
            videoFilename = videoFilename, videoUrl = videoUrl,
            srtFiles = srtFiles, titleEn = titleEn, seriesTitleEn = seriesTitleEn
        ))
        saveState()
        Log.d(TAG, "Enqueued $seriesId ep$episodeNum")
    }

    fun enqueueSeries(series: JanusApi.Series, api: JanusApi) {
        for (ep in series.episodes) {
            val srtFiles = mutableListOf<Pair<String, String>>()
            if (ep.hasJaSubs && ep.jaSrtFile != null)
                srtFiles.add(ep.jaSrtFile to api.subsUrl(series.id, ep.jaSrtFile))
            if (ep.hasEnSubs && ep.enSrtFile != null)
                srtFiles.add(ep.enSrtFile to api.subsUrl(series.id, ep.enSrtFile))
            if (ep.hasFrSubs && ep.frSrtFile != null)
                srtFiles.add(ep.frSrtFile to api.subsUrl(series.id, ep.frSrtFile))
            enqueueEpisode(
                series.id, ep.episode, ep.filename,
                api.videoUrl(series.id, ep.filename), srtFiles,
                titleEn = "Episode ${ep.episode}", seriesTitleEn = series.titleEn
            )
        }
    }

    fun getNextQueued(): DownloadItem? = items.firstOrNull { it.state == State.QUEUED }

    fun getLocalVideoPath(seriesId: String, filename: String): String? {
        val item = items.firstOrNull {
            it.seriesId == seriesId && it.videoFilename == filename && it.state == State.COMPLETED
        } ?: return null
        val file = File(downloadsDir, "$seriesId/$filename")
        return if (file.exists()) file.absolutePath else null
    }

    fun getLocalSubsPath(seriesId: String, srtFile: String): String? {
        val file = File(downloadsDir, "$seriesId/$srtFile")
        return if (file.exists()) file.absolutePath else null
    }

    fun getItemState(seriesId: String, episodeNum: Int): DownloadItem? =
        items.firstOrNull { it.seriesId == seriesId && it.episodeNum == episodeNum }

    fun updateProgress(seriesId: String, episodeNum: Int, progress: Int, bytesDownloaded: Long, totalBytes: Long) {
        val item = items.firstOrNull { it.seriesId == seriesId && it.episodeNum == episodeNum } ?: return
        val idx = items.indexOf(item)
        items[idx] = item.copy(state = State.DOWNLOADING, progress = progress, bytesDownloaded = bytesDownloaded, totalBytes = totalBytes)
    }

    fun markCompleted(seriesId: String, episodeNum: Int) {
        val item = items.firstOrNull { it.seriesId == seriesId && it.episodeNum == episodeNum } ?: return
        val idx = items.indexOf(item)
        val file = File(downloadsDir, "$seriesId/${item.videoFilename}")
        items[idx] = item.copy(state = State.COMPLETED, progress = 100, bytesDownloaded = file.length(), totalBytes = file.length())
        saveState()
    }

    fun markFailed(seriesId: String, episodeNum: Int) {
        val item = items.firstOrNull { it.seriesId == seriesId && it.episodeNum == episodeNum } ?: return
        val idx = items.indexOf(item)
        items[idx] = item.copy(state = State.FAILED)
        saveState()
    }

    fun delete(seriesId: String, episodeNum: Int) {
        val item = items.firstOrNull { it.seriesId == seriesId && it.episodeNum == episodeNum } ?: return
        File(downloadsDir, "$seriesId/${item.videoFilename}").delete()
        File(downloadsDir, "$seriesId/${item.videoFilename}.tmp").delete()
        for ((srtFile, _) in item.srtFiles) {
            File(downloadsDir, "$seriesId/$srtFile").delete()
        }
        items.remove(item)
        saveState()
    }

    fun deleteSeries(seriesId: String) {
        val toRemove = items.filter { it.seriesId == seriesId }
        for (item in toRemove) {
            File(downloadsDir, "$seriesId/${item.videoFilename}").delete()
            File(downloadsDir, "$seriesId/${item.videoFilename}.tmp").delete()
            for ((srtFile, _) in item.srtFiles) {
                File(downloadsDir, "$seriesId/$srtFile").delete()
            }
        }
        items.removeAll(toRemove)
        File(downloadsDir, seriesId).let { if (it.isDirectory) it.delete() }
        saveState()
    }

    fun deleteAll() {
        for (item in items.toList()) {
            delete(item.seriesId, item.episodeNum)
        }
    }

    fun totalDiskUsage(): Long {
        if (!downloadsDir.exists()) return 0
        return downloadsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun hasActiveDownloads(): Boolean = items.any { it.state == State.QUEUED || it.state == State.DOWNLOADING }

    private fun saveState() {
        try {
            val arr = JSONArray()
            for (item in items) {
                arr.put(JSONObject().apply {
                    put("seriesId", item.seriesId)
                    put("episodeNum", item.episodeNum)
                    put("videoFilename", item.videoFilename)
                    put("videoUrl", item.videoUrl)
                    put("state", item.state.name)
                    put("progress", item.progress)
                    put("bytesDownloaded", item.bytesDownloaded)
                    put("totalBytes", item.totalBytes)
                    put("titleEn", item.titleEn)
                    put("seriesTitleEn", item.seriesTitleEn)
                    val srtArr = JSONArray()
                    for ((name, url) in item.srtFiles) {
                        srtArr.put(JSONObject().apply { put("name", name); put("url", url) })
                    }
                    put("srtFiles", srtArr)
                })
            }
            File(downloadsDir, STATE_FILE).writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Save state failed: ${e.message}")
        }
    }

    private fun loadState() {
        try {
            val file = File(downloadsDir, STATE_FILE)
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            items.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val srtArr = obj.optJSONArray("srtFiles") ?: JSONArray()
                val srtFiles = (0 until srtArr.length()).map {
                    val s = srtArr.getJSONObject(it)
                    s.getString("name") to s.getString("url")
                }
                val state = try { State.valueOf(obj.getString("state")) } catch (_: Exception) { State.FAILED }
                items.add(DownloadItem(
                    seriesId = obj.getString("seriesId"),
                    episodeNum = obj.getInt("episodeNum"),
                    videoFilename = obj.getString("videoFilename"),
                    videoUrl = obj.getString("videoUrl"),
                    srtFiles = srtFiles,
                    state = if (state == State.DOWNLOADING) State.QUEUED else state,
                    progress = obj.optInt("progress", 0),
                    bytesDownloaded = obj.optLong("bytesDownloaded", 0),
                    totalBytes = obj.optLong("totalBytes", 0),
                    titleEn = obj.optString("titleEn", ""),
                    seriesTitleEn = obj.optString("seriesTitleEn", ""),
                ))
            }
            Log.d(TAG, "Loaded ${items.size} download items")
        } catch (e: Exception) {
            Log.e(TAG, "Load state failed: ${e.message}")
        }
    }
}
