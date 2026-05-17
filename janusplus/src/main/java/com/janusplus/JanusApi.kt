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

    data class Locale(val title: String, val synopsis: String)

    data class LibraryItem(
        val id: String, val type: String,
        val titleEn: String, val titleJa: String,
        val cover: String, val episodeCount: Int,
        val seasonCount: Int, val durationMin: Int,
        val locales: Map<String, Locale> = emptyMap(),
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
    }

    data class SeasonInfo(val season: Int, val episodeCount: Int, val names: Map<String, String> = emptyMap()) {
        fun name(): String = names[Lang.current] ?: names["en"] ?: ""
    }

    data class SubTrack(val language: String, val label: String, val srtFile: String)

    data class Episode(
        val season: Int, val episode: Int, val filename: String,
        val durationSec: Double, val watchProgressSec: Double, val completed: Boolean,
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

    fun fetchLibrary(): List<LibraryItem> {
        val request = authRequest("$baseUrl/api/library").build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()
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
            )
        }
    }

    private fun parseLocales(obj: JSONObject?): Map<String, Locale> {
        if (obj == null) return emptyMap()
        return obj.keys().asSequence().associate { lang ->
            val l = obj.getJSONObject(lang)
            lang to Locale(l.optString("title", ""), l.optString("synopsis", ""))
        }
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

    data class PageBlob(
        val metadataJson: String,
        val bannerW: Int, val bannerH: Int, val bannerEtc2: ByteArray?,
        val atlasW: Int, val atlasH: Int, val atlasCols: Int, val thumbCount: Int,
        val atlasEtc2: ByteArray?
    )

    var cacheDir: java.io.File? = null

    fun fetchPageBlob(itemId: String, seasonNum: Int): PageBlob? {
        val cacheFile = cacheDir?.let { java.io.File(it, "pages/${itemId}_s${seasonNum}.bin") }
        val etagFile = cacheDir?.let { java.io.File(it, "pages/${itemId}_s${seasonNum}.etag") }

        var bytes: ByteArray
        if (cacheFile != null && cacheFile.exists()) {
            val storedEtag = if (etagFile?.exists() == true) etagFile.readText() else null
            if (storedEtag != null) {
                bytes = try {
                    val req = authRequest("$baseUrl/api/page/$itemId/$seasonNum")
                        .header("If-None-Match", storedEtag).build()
                    val resp = client.newCall(req).execute()
                    if (resp.code == 304) {
                        cacheFile.readBytes()
                    } else if (resp.isSuccessful) {
                        val fetched = resp.body?.bytes() ?: cacheFile.readBytes()
                        cacheFile.writeBytes(fetched)
                        resp.header("ETag")?.let { etagFile?.writeText(it) }
                        fetched
                    } else {
                        cacheFile.readBytes()
                    }
                } catch (_: Exception) {
                    cacheFile.readBytes()
                }
            } else {
                bytes = cacheFile.readBytes()
            }
        } else {
            val request = authRequest("$baseUrl/api/page/$itemId/$seasonNum").build()
            val response = try { client.newCall(request).execute() } catch (_: Exception) { return null }
            if (!response.isSuccessful) return null
            val fetched = response.body?.bytes() ?: return null
            if (cacheFile != null) {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeBytes(fetched)
                response.header("ETag")?.let { etagFile?.writeText(it) }
            }
            bytes = fetched
        }
        if (bytes.size < 4) return null

        var off = 0
        val metaLen = readInt(bytes, off); off += 4
        val metaJson = String(bytes, off, metaLen, Charsets.UTF_8); off += metaLen

        val bannerW = readInt(bytes, off); off += 4
        val bannerH = readInt(bytes, off); off += 4
        val bannerLen = readInt(bytes, off); off += 4
        val bannerEtc2 = if (bannerLen > 0) bytes.copyOfRange(off, off + bannerLen) else null
        off += bannerLen

        val atlasW = readInt(bytes, off); off += 4
        val atlasH = readInt(bytes, off); off += 4
        val atlasCols = readInt(bytes, off); off += 4
        val thumbCount = readInt(bytes, off); off += 4
        val atlasLen = readInt(bytes, off); off += 4
        val atlasEtc2 = if (atlasLen > 0) bytes.copyOfRange(off, off + atlasLen) else null

        return PageBlob(metaJson, bannerW, bannerH, bannerEtc2, atlasW, atlasH, atlasCols, thumbCount, atlasEtc2)
    }

    fun coverUrl(itemId: String) = "$baseUrl/api/covers/$itemId.jpg"
    fun bannerUrl(itemId: String) = "$baseUrl/api/covers/$itemId-banner.jpg"

    private fun readInt(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
        ((bytes[off + 1].toInt() and 0xFF) shl 8) or
        ((bytes[off + 2].toInt() and 0xFF) shl 16) or
        ((bytes[off + 3].toInt() and 0xFF) shl 24)

    private fun parseEpisode(obj: JSONObject): Episode = Episode(
        season = obj.getInt("season"), episode = obj.getInt("episode"),
        filename = obj.getString("filename"), durationSec = obj.getDouble("duration_sec"),
        watchProgressSec = obj.optDouble("watch_progress_sec", 0.0),
        completed = obj.optBoolean("completed", false),
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
