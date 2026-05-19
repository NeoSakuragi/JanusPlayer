package com.janusplus.v2

import android.graphics.Bitmap
import android.graphics.Typeface
import com.janusplus.*
import kotlin.concurrent.thread

class PlayerLoadingState(
    private val item: JanusApi.LibraryItem,
    private val episode: JanusApi.Episode,
    private val baseUrl: String,
) : GameState {

    @Volatile private var ready = false
    @Volatile private var page: PlayerPage? = null
    private var startTime = System.nanoTime()

    override fun init(app: App) {
        startTime = System.nanoTime()

        thread {
            try { loadPage(app) } catch (e: Exception) {
                android.util.Log.e("PlayerLoading", "Load failed: ${e.message}")
            }
        }
    }

    private fun loadPage(app: App) {
        val api = app.api ?: return
        val density = app.density
        val texW = app.texArray.size
        val token = api.token ?: ""
        val tf = app.defaultTypeface

        // 1. Load prefs
        val p = app.context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
        val prefs = PlayerPrefs(
            deltaFurigana = p.getFloat("df", 0.7f),
            deltaRow = p.getFloat("dr", 1.4f),
            deltaSpacing = p.getFloat("ds", 0f),
            deltaYShift = p.getFloat("dy", 0f),
            subFontSize = p.getInt("font_size", 32),
            readingMode = p.getInt("reading_mode", 3),
            condensedMode = p.getBoolean("condensed", false),
            debugBoxes = p.getBoolean("debug_boxes", false),
            einkMode = p.getBoolean("eink_mode", false),
        )

        // 2. Fetch subtitles — SuperSRT + SRT in parallel
        var superSRT: JanusApi.SuperSRT? = null
        var cues: List<SrtParser.Cue> = emptyList()

        val superThread = Thread {
            superSRT = try { api.fetchSuperSRT(item.id, episode.season, episode.episode) } catch (_: Exception) { null }
        }.also { it.start() }

        val srtThread = if (episode.subtitles.isNotEmpty()) {
            val jaTrack = episode.subtitles.firstOrNull { it.language == "ja" } ?: episode.subtitles.first()
            Thread {
                try {
                    val srtUrl = "$baseUrl/api/subs/${item.id}/${jaTrack.srtFile}"
                    val request = okhttp3.Request.Builder().url(srtUrl)
                        .header("Authorization", "Bearer $token").build()
                    val response = okhttp3.OkHttpClient().newCall(request).execute()
                    if (response.isSuccessful) cues = SrtParser.parse(response.body?.string() ?: "")
                    response.close()
                } catch (_: Exception) {}
            }.also { it.start() }
        } else null

        superThread.join()
        srtThread?.join()
        val superData = superSRT
        val cueData = cues

        // 3. Collect ALL text for glyph atlas building
        val allSubTexts = mutableListOf<String>()
        if (superData != null) {
            for (cue in superData.cues) {
                for (w in cue.words) {
                    allSubTexts.add(w.surface)
                    allSubTexts.add(w.reading)
                    if (w.inflection.isNotEmpty()) allSubTexts.add(w.inflection)
                    for (f in w.furigana) allSubTexts.add(f.reading)
                }
            }
            for (entry in superData.dict) {
                allSubTexts.add(entry.term); allSubTexts.add(entry.reading)
                allSubTexts.addAll(entry.meanings)
                allSubTexts.add(entry.jlpt)
            }
        } else {
            for (cue in cueData) allSubTexts.add(cue.text)
        }
        allSubTexts.add("0123456789. ")
        allSubTexts.add("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        allSubTexts.add("()[]{}「」、。！？…～ー;:/")
        allSubTexts.add(ReadingUtils.HIRAGANA)
        allSubTexts.add(ReadingUtils.KATAKANA)

        // 4. Build UI atlases — one base (18sp) + one icon (36sp)
        // All other UI sizes use RC.textScaled() from the 18sp atlas
        val uiTexts = listOf(
            "${episode.episode}. ${episode.title()}",
            "←", "▶", "⏮", "⏭", "●", Lang.s("settings"),
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            "0123456789:.%()/-+sp ",
            "Audio Subtitle Reading Mode Font Size Condensed Theme Debug Boxes",
            "DF DR DS DY Furigana Row Space Letter Y Offset E-Ink Dark",
            "PRO ADVANCED INTERMEDIATE NOVICE ON OFF Track Japanese",
            "Noto Sans Serif Shippori",
        )

        val uiAtlases = mutableListOf<Triple<Int, GlyphAtlas, Bitmap>>()
        val uiBase = 18
        val uiBasePx = (uiBase * density).toInt()
        val uiAtlas = GlyphAtlas(tf, uiBase * density)
        uiAtlases.add(Triple(uiBasePx, uiAtlas, uiAtlas.build(uiTexts, texW)))
        // Icon atlas at 36sp for play button (2× base, clean upscale)
        val iconAtlas = GlyphAtlas(tf, 36 * density)
        uiAtlases.add(Triple((36 * density).toInt(), iconAtlas, iconAtlas.build(listOf("▶"), texW)))

        // 5. Build ONE subtitle atlas — furigana + dict use GL scaling from this
        var subAtlas: GlyphAtlas? = null; var subBmp: Bitmap? = null
        if (allSubTexts.isNotEmpty()) {
            val sa = GlyphAtlas(tf, prefs.subFontSize * density)
            subBmp = sa.build(allSubTexts, texW); subAtlas = sa
        }
        val furiAtlas: GlyphAtlas? = null; val furiBmp: Bitmap? = null

        // 6. No separate dict atlases — dict popup reuses subAtlas with GL scaling
        val dictAtlases = mutableListOf<Triple<Int, GlyphAtlas, Bitmap>>()

        page = PlayerPage(item, episode, baseUrl, prefs, cueData, superData,
            uiAtlases, subAtlas, subBmp, furiAtlas, furiBmp, dictAtlases)
        ready = true
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        if (ready) {
            val p = page ?: return
            ready = false
            app.replace(Screen.PLAYER, PlayerState(p))
        }
        for (a in actions) { if (a == Action.BACK) app.goBack() }
    }

    override fun draw(app: App, rc: RC) {
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
        rc.spinner(rc.w / 2f, rc.h / 2f, elapsed)
    }

    override fun cleanup(app: App) {}
}
