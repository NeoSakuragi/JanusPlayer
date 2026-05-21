package com.janusplus

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
        .readTimeout(120, TimeUnit.SECONDS)
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

    data class Locale(val title: String, val synopsis: String)

    data class LibraryItem(
        val id: String, val type: String,
        val titleEn: String, val titleJa: String,
        val cover: String, val episodeCount: Int,
        val seasonCount: Int, val durationMin: Int,
        val locales: Map<String, Locale> = emptyMap(),
        val posterPath: String = "",
    ) {
        fun title(): String {
            val lang = Lang.current
            locales[lang]?.title?.takeIf { it.isNotEmpty() }?.let { return it }
            return when (lang) { "ja" -> titleJa.ifEmpty { titleEn }; else -> titleEn }
        }
        fun synopsis(): String {
            val lang = Lang.current
            return locales[lang]?.synopsis?.takeIf { it.isNotEmpty() } ?: locales["en"]?.synopsis ?: ""
        }
        fun coverUrl(): String {
            if (posterPath.isNotEmpty()) return "https://image.tmdb.org/t/p/w500$posterPath"
            return ""
        }
    }

    data class SeasonInfo(val season: Int, val episodeCount: Int, val names: Map<String, String> = emptyMap()) {
        fun name(): String = names[Lang.current] ?: names["en"] ?: ""
    }

    data class SubTrack(val language: String, val label: String, val srtFile: String)

    data class Episode(
        val season: Int, val episode: Int, val filename: String,
        val durationSec: Double, val watchProgressMs: Long, val watchDurationMs: Long,
        val watchUpdatedAt: Long = 0,
        val openingSec: Double = 0.0, val endingSec: Double = 0.0,
        val titleEn: String, val synopsisEn: String, val synopsisJa: String, val synopsisFr: String,
        val thumb: String?, val subtitles: List<SubTrack>,
        val locales: Map<String, Locale> = emptyMap(),
    ) {
        fun hasSubs(lang: String) = subtitles.any { it.language == lang }
        fun title(): String {
            val lang = Lang.current
            locales[lang]?.title?.takeIf { it.isNotEmpty() }?.let { return it }
            return titleEn
        }
        fun progressFraction(): Float {
            if (watchDurationMs <= 0) return 0f
            return (watchProgressMs.toFloat() / watchDurationMs).coerceIn(0f, 1f)
        }
    }

    data class HeroBlob(
        val id: String, val type: String,
        val titleEn: String, val titleJa: String,
        val episodeCount: Int, val seasonCount: Int, val durationMin: Int,
        val seasons: List<SeasonInfo>, val locales: Map<String, Locale>,
        val synopsisEn: String, val synopsisFr: String, val synopsisJa: String,
        val episode: Episode?,
        val bannerBytes: ByteArray?, val coverBytes: ByteArray?,
    )

    data class CardEpisode(
        val episode: Int, val titleEn: String, val durationSec: Double,
        val titles: Map<String, String> = emptyMap(),
    ) {
        fun title(): String = titles[Lang.current]?.takeIf { it.isNotEmpty() } ?: titleEn
    }

    data class SeasonCards(val season: Int, val episodeCount: Int, val episodes: List<CardEpisode>)

    data class ThumbEntry(val episode: Int, val data: ByteArray)

    var libraryEtag: String? = null

    fun fetchLibrary(): List<LibraryItem> {
        val rb = authRequest("$baseUrl/api/library")
        libraryEtag?.let { rb.header("If-None-Match", it) }
        val response = client.newCall(rb.build()).execute()
        if (response.code == 304) return emptyList()
        if (!response.isSuccessful) return emptyList()
        libraryEtag = response.header("ETag")
        val json = JSONObject(response.body?.string() ?: return emptyList())
        val items = json.getJSONArray("items")
        return (0 until items.length()).map { i ->
            val obj = items.getJSONObject(i)
            LibraryItem(
                id = obj.getString("id"), type = obj.getString("type"),
                titleEn = obj.getString("title_en"), titleJa = obj.getString("title_ja"),
                cover = obj.optString("cover", ""),
                episodeCount = obj.optInt("episode_count", 1),
                seasonCount = obj.optInt("season_count", 1),
                durationMin = obj.optInt("duration_min", 0),
                locales = parseLocales(obj.optJSONObject("locales")),
                posterPath = obj.optString("poster_path", ""),
            )
        }
    }

    fun hasLibraryChanged(): Boolean {
        if (libraryEtag == null) return true
        try {
            val rb = authRequest("$baseUrl/api/library")
            rb.header("If-None-Match", libraryEtag!!)
            val response = client.newCall(rb.build()).execute()
            response.close()
            return response.code != 304
        } catch (_: Exception) { return false }
    }

    private fun parseLocales(obj: JSONObject?): Map<String, Locale> {
        if (obj == null) return emptyMap()
        return obj.keys().asSequence().associate { lang ->
            val l = obj.getJSONObject(lang)
            lang to Locale(l.optString("title", ""), l.optString("synopsis", ""))
        }
    }

    fun fetchLibraryCovers(): Map<String, android.graphics.Bitmap> {
        val request = authRequest("$baseUrl/api/library/covers").build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return emptyMap() }
        if (!response.isSuccessful) return emptyMap()
        val bytes = response.body?.bytes() ?: return emptyMap()
        if (bytes.size < 4) return emptyMap()

        val result = mutableMapOf<String, android.graphics.Bitmap>()
        var off = 0
        val count = readInt(bytes, off); off += 4
        for (i in 0 until count) {
            if (off + 4 > bytes.size) break
            val idLen = readInt(bytes, off); off += 4
            if (off + idLen > bytes.size) break
            val id = String(bytes, off, idLen, Charsets.UTF_8); off += idLen
            if (off + 4 > bytes.size) break
            val jpegLen = readInt(bytes, off); off += 4
            if (jpegLen > 0 && off + jpegLen <= bytes.size) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, off, jpegLen)
                if (bmp != null) result["cover_$id"] = bmp
                off += jpegLen
            }
        }
        return result
    }

    fun fetchHeroBlob(itemId: String): HeroBlob? {
        val request = authRequest("$baseUrl/api/blob/$itemId/hero").build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return null }
        if (!response.isSuccessful) return null
        val bytes = response.body?.bytes() ?: return null
        if (bytes.size < 4) return null

        val jsonLen = readInt(bytes, 0)
        if (bytes.size < 4 + jsonLen) return null
        val obj = JSONObject(String(bytes, 4, jsonLen))

        var offset = 4 + jsonLen
        var bannerBytes: ByteArray? = null
        var coverBytes: ByteArray? = null
        if (offset + 4 <= bytes.size) {
            val bannerLen = readInt(bytes, offset)
            offset += 4
            if (bannerLen > 0 && offset + bannerLen <= bytes.size) {
                bannerBytes = bytes.copyOfRange(offset, offset + bannerLen)
                offset += bannerLen
            }
            if (offset < bytes.size) coverBytes = bytes.copyOfRange(offset, bytes.size)
        }

        val seasonsArr = obj.optJSONArray("seasons")
        val seasons = if (seasonsArr != null) (0 until seasonsArr.length()).map { i ->
            val s = seasonsArr.getJSONObject(i)
            val namesObj = s.optJSONObject("names")
            val names = namesObj?.keys()?.asSequence()?.associate { it to namesObj.getString(it) } ?: emptyMap()
            SeasonInfo(s.getInt("season"), s.getInt("episode_count"), names)
        } else emptyList()

        val ep = obj.optJSONObject("episode")?.let { parseEpisode(it) }
        return HeroBlob(
            id = obj.getString("id"), type = obj.getString("type"),
            titleEn = obj.optString("title_en", ""), titleJa = obj.optString("title_ja", ""),
            episodeCount = obj.optInt("episode_count", 1),
            seasonCount = obj.optInt("season_count", 1),
            durationMin = obj.optInt("duration_min", 0),
            seasons = seasons, locales = parseLocales(obj.optJSONObject("locales")),
            synopsisEn = obj.optString("synopsis_en", ""),
            synopsisFr = obj.optString("synopsis_fr", ""),
            synopsisJa = obj.optString("synopsis_ja", ""),
            episode = ep, bannerBytes = bannerBytes, coverBytes = coverBytes,
        )
    }

    fun fetchSeasonCards(itemId: String, seasonNum: Int): SeasonCards? {
        val request = authRequest("$baseUrl/api/blob/$itemId/season/$seasonNum").build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return null }
        if (!response.isSuccessful) return null
        val obj = JSONObject(response.body?.string() ?: return null)
        val episodes = obj.getJSONArray("episodes")
        return SeasonCards(
            season = obj.getInt("season"), episodeCount = obj.getInt("episode_count"),
            episodes = (0 until episodes.length()).map { i ->
                val e = episodes.getJSONObject(i)
                val titlesObj = e.optJSONObject("titles")
                val titles = titlesObj?.keys()?.asSequence()?.associate { it to titlesObj.getString(it) } ?: emptyMap()
                CardEpisode(e.getInt("episode"), e.optString("title_en", ""), e.getDouble("duration_sec"), titles)
            },
        )
    }

    data class SeasonData(val season: Int, val episodeCount: Int, val episodes: List<Episode>)

    fun fetchSeason(itemId: String, seasonNum: Int): SeasonData? {
        val request = authRequest("$baseUrl/api/items/$itemId/season-$seasonNum.json").build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return null }
        if (!response.isSuccessful) return null
        val obj = org.json.JSONObject(response.body?.string() ?: return null)
        val episodes = obj.getJSONArray("episodes")
        return SeasonData(
            season = obj.getInt("season"), episodeCount = obj.getInt("episode_count"),
            episodes = (0 until episodes.length()).map { parseEpisode(episodes.getJSONObject(it)) },
        )
    }

fun fetchSeasonBlob(itemId: String, seasonNum: Int): SeasonData? {
        val request = authRequest("$baseUrl/api/blob/$itemId/season/$seasonNum").build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return null }
        if (!response.isSuccessful) return null
        val obj = org.json.JSONObject(response.body?.string() ?: return null)
        val episodes = obj.getJSONArray("episodes")
        return SeasonData(
            season = obj.getInt("season"), episodeCount = obj.getInt("episode_count"),
            episodes = (0 until episodes.length()).map { parseEpisode(episodes.getJSONObject(it)) },
        )
    }

    fun fetchThumbsBlob(itemId: String, seasonNum: Int): List<ThumbEntry> {
        val request = authRequest("$baseUrl/api/blob/$itemId/season/$seasonNum/thumbs").build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return emptyList() }
        if (!response.isSuccessful) return emptyList()
        val bytes = response.body?.bytes() ?: return emptyList()
        if (bytes.size < 4) return emptyList()

        val count = readInt(bytes, 0)
        val entries = mutableListOf<ThumbEntry>()
        var offset = 4
        for (i in 0 until count) {
            if (offset + 8 > bytes.size) break
            val epNum = readInt(bytes, offset)
            val size = readInt(bytes, offset + 4)
            offset += 8
            if (offset + size > bytes.size) break
            entries.add(ThumbEntry(epNum, bytes.copyOfRange(offset, offset + size)))
            offset += size
        }
        return entries
    }

    data class PageHeader(
        val metadataJson: String,
        val bannerW: Int, val bannerH: Int, val bannerJpeg: ByteArray?,
        val atlasW: Int, val atlasH: Int, val atlasCols: Int, val thumbCount: Int,
        val thumbW: Int, val thumbH: Int,
        val coverJpeg: ByteArray? = null,
    )

    var cacheDir: java.io.File? = null

    private fun fetchCached(url: String, cacheFile: java.io.File?, etagFile: java.io.File?): ByteArray? {
        // Cache hit → return immediately, zero network
        if (cacheFile != null && cacheFile.exists()) {
            return cacheFile.readBytes()
        }
        // Cache miss → fetch from network
        val request = authRequest(url).build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return null }
        if (!response.isSuccessful) return null
        val fetched = response.body?.bytes() ?: return null
        if (cacheFile != null) {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(fetched)
            response.header("ETag")?.let { etagFile?.writeText(it) }
        }
        return fetched
    }

    fun fetchPageHeader(itemId: String, seasonNum: Int): PageHeader? {
        val cache = cacheDir?.let { java.io.File(it, "pages/${itemId}_s${seasonNum}.hdr") }
        val etag = cacheDir?.let { java.io.File(it, "pages/${itemId}_s${seasonNum}.hdr.etag") }
        var bytes = fetchCached("$baseUrl/api/page/$itemId/$seasonNum/header", cache, etag) ?: return null
        return try {
            parsePageHeader(bytes)
        } catch (e: Exception) {
            Log.e("JanusApi", "Header parse failed, refetching: ${e.message}")
            cache?.delete(); etag?.delete()
            // Also delete stale atlas cache
            cacheDir?.let {
                java.io.File(it, "pages/${itemId}_s${seasonNum}.atlas").delete()
                java.io.File(it, "pages/${itemId}_s${seasonNum}.atlas.etag").delete()
            }
            bytes = fetchCached("$baseUrl/api/page/$itemId/$seasonNum/header", cache, etag) ?: return null
            try { parsePageHeader(bytes) } catch (_: Exception) { null }
        }
    }

    private fun parsePageHeader(bytes: ByteArray): PageHeader {
        var off = 0
        val metaLen = readInt(bytes, off); off += 4
        val metaJson = String(bytes, off, metaLen, Charsets.UTF_8); off += metaLen
        val bannerW = readInt(bytes, off); off += 4
        val bannerH = readInt(bytes, off); off += 4
        val bannerLen = readInt(bytes, off); off += 4
        val bannerJpeg = if (bannerLen > 0) bytes.copyOfRange(off, off + bannerLen) else null
        off += bannerLen
        val atlasW = readInt(bytes, off); off += 4
        val atlasH = readInt(bytes, off); off += 4
        val atlasCols = readInt(bytes, off); off += 4
        val thumbCount = readInt(bytes, off); off += 4
        val thumbW = readInt(bytes, off); off += 4
        val thumbH = readInt(bytes, off); off += 4
        var coverJpeg: ByteArray? = null
        if (off + 4 <= bytes.size) {
            val coverLen = readInt(bytes, off); off += 4
            if (coverLen > 0 && off + coverLen <= bytes.size) {
                coverJpeg = bytes.copyOfRange(off, off + coverLen)
            }
        }
        return PageHeader(metaJson, bannerW, bannerH, bannerJpeg, atlasW, atlasH, atlasCols, thumbCount, thumbW, thumbH, coverJpeg)
    }

    fun fetchPageAtlas(itemId: String, seasonNum: Int): ByteArray? {
        val cache = cacheDir?.let { java.io.File(it, "pages/${itemId}_s${seasonNum}.atlas") }
        val etag = cacheDir?.let { java.io.File(it, "pages/${itemId}_s${seasonNum}.atlas.etag") }
        return fetchCached("$baseUrl/api/page/$itemId/$seasonNum/atlas", cache, etag)
    }

    fun coverUrl(itemId: String) = "$baseUrl/api/covers/$itemId.jpg"
    fun bannerUrl(itemId: String) = "$baseUrl/api/covers/$itemId-banner.jpg"

    // ── Supercharged SRT ──────────────────────────────────

    data class DictEntry(val term: String, val reading: String, val meanings: List<String>, val jlpt: String, val freq: Int)
    data class FuriganaSpan(val charIdx: Int, val reading: String)
    data class SuperWord(val surface: String, val dictIdx: Int, val inflection: String,
                         val reading: String, val subReadings: List<String>, val furigana: List<FuriganaSpan>)
    data class SuperCue(val startMs: Long, val endMs: Long, val words: List<SuperWord>)
    data class SuperSRT(val version: Int, val dict: List<DictEntry>, val cues: List<SuperCue>)

    fun fetchSuperSRT(itemId: String, season: Int, episode: Int): SuperSRT? {
        val cache = cacheDir?.let { java.io.File(it, "ssrt/${itemId}_s${season}e${episode}.json") }
        val etagFile = cacheDir?.let { java.io.File(it, "ssrt/${itemId}_s${season}e${episode}.etag") }
        val body = fetchCached("$baseUrl/api/super-srt/$itemId/$season/$episode", cache, etagFile)
            ?: return null
        val obj = JSONObject(String(body, Charsets.UTF_8))

        val dictArr = obj.getJSONArray("dict")
        val dict = (0 until dictArr.length()).map { i ->
            val d = dictArr.getJSONObject(i)
            val meanings = d.optJSONArray("m")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            DictEntry(d.getString("t"), d.optString("r", ""), meanings, d.optString("jlpt", ""), d.optInt("freq", 0))
        }

        val cuesArr = obj.getJSONArray("cues")
        val cues = (0 until cuesArr.length()).map { i ->
            val c = cuesArr.getJSONObject(i)
            val wordsArr = c.getJSONArray("w")
            val words = (0 until wordsArr.length()).map { j ->
                val w = wordsArr.getJSONObject(j)
                val sr = w.optJSONArray("sr")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                val fArr = w.optJSONArray("f")
                val furigana = if (fArr != null) (0 until fArr.length()).map { fi ->
                    val fa = fArr.getJSONArray(fi)
                    FuriganaSpan(fa.getInt(0), fa.getString(1))
                } else emptyList()
                SuperWord(w.getString("s"), w.optInt("d", -1), w.optString("i", ""), w.optString("r", ""), sr, furigana)
            }
            SuperCue(c.getLong("s"), c.getLong("e"), words)
        }

        return SuperSRT(obj.optInt("v", 1), dict, cues)
    }

    private fun readInt(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
        ((bytes[off + 1].toInt() and 0xFF) shl 8) or
        ((bytes[off + 2].toInt() and 0xFF) shl 16) or
        ((bytes[off + 3].toInt() and 0xFF) shl 24)

    data class WatchProgress(val episode: Int, val positionMs: Long, val durationMs: Long)
    data class ResumeInfo(val episode: Int, val positionMs: Long)
    data class ProgressResponse(val episodes: List<WatchProgress>, val resume: ResumeInfo?)

    fun fetchProgress(itemId: String): ProgressResponse {
        val request = authRequest("$baseUrl/api/progress/$itemId").build()
        val response = try { client.newCall(request).execute() } catch (_: Exception) { return ProgressResponse(emptyList(), null) }
        if (!response.isSuccessful) return ProgressResponse(emptyList(), null)
        val obj = JSONObject(response.body?.string() ?: return ProgressResponse(emptyList(), null))
        val eps = obj.optJSONArray("episodes") ?: return ProgressResponse(emptyList(), null)
        val list = (0 until eps.length()).map { i ->
            val e = eps.getJSONObject(i)
            WatchProgress(e.getInt("episode"), e.getLong("position_ms"), e.getLong("duration_ms"))
        }
        val resumeObj = obj.optJSONObject("resume")
        val resume = if (resumeObj != null) ResumeInfo(resumeObj.getInt("episode"), resumeObj.getLong("position_ms")) else null
        return ProgressResponse(list, resume)
    }

    fun saveProgress(itemId: String, episode: Int, positionMs: Long, durationMs: Long) {
        val body = JSONObject()
            .put("episode", episode)
            .put("position_ms", positionMs)
            .put("duration_ms", durationMs).toString()
        val request = authRequest("$baseUrl/api/progress/$itemId")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        try { client.newCall(request).execute().close() } catch (_: Exception) {}
    }

    private fun parseEpisode(obj: JSONObject): Episode = Episode(
        season = obj.optInt("season", 1), episode = obj.getInt("episode"),
        filename = obj.getString("filename"), durationSec = obj.getDouble("duration_sec"),
        watchProgressMs = obj.optLong("watch_progress_ms", 0),
        watchDurationMs = obj.optLong("watch_duration_ms", 0),
        watchUpdatedAt = obj.optLong("watch_updated_at", 0),
        openingSec = obj.optDouble("opening_sec", 0.0),
        endingSec = obj.optDouble("ending_sec", 0.0),
        titleEn = obj.optString("title_en", ""),
        synopsisEn = obj.optString("synopsis_en", ""),
        synopsisJa = obj.optString("synopsis_ja", ""),
        synopsisFr = obj.optString("synopsis_fr", ""),
        thumb = obj.optString("thumb", "").ifEmpty { null },
        subtitles = obj.optJSONArray("subtitles")?.let { arr ->
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                SubTrack(s.getString("language"), s.getString("label"), s.getString("srt_file"))
            }
        } ?: emptyList(),
        locales = parseLocales(obj.optJSONObject("locales")),
    )
}
