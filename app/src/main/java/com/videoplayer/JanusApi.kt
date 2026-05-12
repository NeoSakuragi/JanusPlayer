package com.videoplayer

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JanusApi(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var token: String? = null

    data class LoginResult(val token: String, val username: String, val role: String)

    fun login(username: String, password: String): LoginResult? {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val request = Request.Builder()
            .url("$baseUrl/api/login")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val json = JSONObject(response.body?.string() ?: return null)
        val result = LoginResult(
            token = json.getString("token"),
            username = json.getString("username"),
            role = json.getString("role")
        )
        token = result.token
        return result
    }

    private fun authRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder
    }

    data class LibraryItem(
        val id: String,
        val type: String,
        val titleEn: String,
        val titleJa: String,
        val cover: String,
        val episodeCount: Int,
        val seasonCount: Int,
        val durationMin: Int,
    )

    data class SeasonInfo(val season: Int, val episodeCount: Int)

    data class SeriesDetail(
        val id: String,
        val type: String,
        val titleEn: String,
        val titleJa: String,
        val cover: String,
        val episodeCount: Int,
        val seasons: List<SeasonInfo>,
    )

    data class MovieDetail(
        val id: String,
        val titleEn: String,
        val titleJa: String,
        val cover: String,
        val episode: Episode,
    )

    data class SeasonData(
        val season: Int,
        val episodeCount: Int,
        val episodes: List<Episode>,
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
        val completed: Boolean,
        val titleEn: String,
        val synopsisEn: String,
        val thumb: String?,
    )

    fun fetchLibrary(): List<LibraryItem> {
        val request = authRequest("$baseUrl/api/library").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e("JanusApi", "Library fetch failed: ${response.code}")
            return emptyList()
        }
        val json = JSONObject(response.body?.string() ?: return emptyList())
        val items = json.getJSONArray("items")
        return (0 until items.length()).map { i ->
            val obj = items.getJSONObject(i)
            LibraryItem(
                id = obj.getString("id"),
                type = obj.getString("type"),
                titleEn = obj.getString("title_en"),
                titleJa = obj.getString("title_ja"),
                cover = obj.optString("cover", ""),
                episodeCount = obj.optInt("episode_count", 1),
                seasonCount = obj.optInt("season_count", 1),
                durationMin = obj.optInt("duration_min", 0),
            )
        }
    }

    fun fetchSeriesDetail(seriesId: String): SeriesDetail? {
        val request = authRequest("$baseUrl/api/items/$seriesId/info.json").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val obj = JSONObject(response.body?.string() ?: return null)
        val seasons = obj.getJSONArray("seasons")
        return SeriesDetail(
            id = obj.getString("id"),
            type = obj.getString("type"),
            titleEn = obj.getString("title_en"),
            titleJa = obj.getString("title_ja"),
            cover = obj.optString("cover", ""),
            episodeCount = obj.getInt("episode_count"),
            seasons = (0 until seasons.length()).map { i ->
                val s = seasons.getJSONObject(i)
                SeasonInfo(s.getInt("season"), s.getInt("episode_count"))
            },
        )
    }

    fun fetchSeason(seriesId: String, seasonNum: Int): SeasonData? {
        val request = authRequest("$baseUrl/api/items/$seriesId/season-$seasonNum.json").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val obj = JSONObject(response.body?.string() ?: return null)
        val episodes = obj.getJSONArray("episodes")
        return SeasonData(
            season = obj.getInt("season"),
            episodeCount = obj.getInt("episode_count"),
            episodes = (0 until episodes.length()).map { parseEpisode(episodes.getJSONObject(it)) },
        )
    }

    fun fetchMovieDetail(movieId: String): MovieDetail? {
        val request = authRequest("$baseUrl/api/items/$movieId.json").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val obj = JSONObject(response.body?.string() ?: return null)
        return MovieDetail(
            id = obj.getString("id"),
            titleEn = obj.getString("title_en"),
            titleJa = obj.getString("title_ja"),
            cover = obj.optString("cover", ""),
            episode = parseEpisode(obj.getJSONObject("episode")),
        )
    }

    fun videoUrl(seriesId: String, filename: String): String =
        "$baseUrl/api/video/$seriesId/${java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")}"

    fun streamUrl(seriesId: String, season: Int, episode: Int): String =
        "$baseUrl/api/stream/$seriesId/$season/$episode"

    fun streamSubsUrl(seriesId: String, season: Int, episode: Int, lang: String): String =
        "$baseUrl/api/stream/$seriesId/$season/$episode/subs/$lang"

    fun subsUrl(seriesId: String, srtFile: String): String =
        "$baseUrl/api/subs/$seriesId/$srtFile"

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
        completed = obj.optBoolean("completed", false),
        titleEn = obj.optString("title_en", ""),
        synopsisEn = obj.optString("synopsis_en", ""),
        thumb = obj.optString("thumb", null),
    )
}
