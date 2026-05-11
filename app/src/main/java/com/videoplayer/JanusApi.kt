package com.videoplayer

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Client for the Janus media server API.
 */
class JanusApi(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Series(
        val id: String,
        val type: String,
        val titleEn: String,
        val titleJa: String,
        val cover: String,
        val episodeCount: Int,
        val episodes: List<Episode>,
        val lastWatchedEpisode: Int?,
        val overallProgress: Double
    )

    data class Episode(
        val season: Int,
        val episode: Int,
        val filename: String,
        val durationSec: Double,
        val hasJaSubs: Boolean,
        val hasFrSubs: Boolean,
        val hasEnSubs: Boolean,
        val jaSrtFile: String?,
        val frSrtFile: String?,
        val enSrtFile: String?,
        val jaSubLines: Int,
        val watchProgressSec: Double,
        val completed: Boolean
    )

    fun fetchLibrary(): List<Series> {
        val request = Request.Builder().url("$baseUrl/api/library").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e("JanusApi", "Library fetch failed: ${response.code}")
            return emptyList()
        }
        val json = JSONObject(response.body?.string() ?: return emptyList())
        val items = json.getJSONArray("items")
        return (0 until items.length()).map { parseItem(items.getJSONObject(it)) }
    }

    fun videoUrl(seriesId: String, filename: String): String =
        "$baseUrl/api/video/$seriesId/$filename"

    fun subsUrl(seriesId: String, srtFile: String): String =
        "$baseUrl/api/subs/$seriesId/$srtFile"

    fun coverUrl(seriesId: String): String =
        "$baseUrl/api/cover/$seriesId.jpg"

    fun fetchSrt(seriesId: String, srtFile: String): String? {
        val url = subsUrl(seriesId, srtFile)
        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.string() else null
        } catch (e: Exception) {
            Log.e("JanusApi", "SRT fetch failed: ${e.message}")
            null
        }
    }

    private fun parseItem(obj: JSONObject): Series {
        val episodes = obj.getJSONArray("episodes")
        return Series(
            id = obj.getString("id"),
            type = obj.getString("type"),
            titleEn = obj.getString("title_en"),
            titleJa = obj.getString("title_ja"),
            cover = obj.optString("cover", ""),
            episodeCount = obj.getInt("episode_count"),
            episodes = (0 until episodes.length()).map { parseEpisode(episodes.getJSONObject(it)) },
            lastWatchedEpisode = obj.opt("last_watched_episode") as? Int,
            overallProgress = obj.optDouble("overall_progress", 0.0)
        )
    }

    private fun parseEpisode(obj: JSONObject): Episode = Episode(
        season = obj.getInt("season"),
        episode = obj.getInt("episode"),
        filename = obj.getString("filename"),
        durationSec = obj.getDouble("duration_sec"),
        hasJaSubs = obj.getBoolean("has_ja_subs"),
        hasFrSubs = obj.optBoolean("has_fr_subs", false),
        hasEnSubs = obj.optBoolean("has_en_subs", false),
        jaSrtFile = obj.optString("ja_srt_file", null),
        frSrtFile = obj.optString("fr_srt_file", null),
        enSrtFile = obj.optString("en_srt_file", null),
        jaSubLines = obj.optInt("ja_sub_lines", 0),
        watchProgressSec = obj.optDouble("watch_progress_sec", 0.0),
        completed = obj.optBoolean("completed", false)
    )
}
