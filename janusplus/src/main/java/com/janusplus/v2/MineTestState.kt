package com.janusplus.v2

import com.janusplus.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Mine test harness. Run via: adb shell am start -n com.janusplus/.v2.MainActivity --es test mine -S
 *
 * Test input: item=saint-seiya, season=1, episode=1, word="俺"
 * Everything else is resolved from real APIs.
 */
class MineTestState : GameState {

    private var debugAtlas: GlyphAtlas? = null
    private val lines = mutableListOf<String>()
    @Volatile private var running = false

    override fun init(app: App) {
        val tf = app.defaultTypeface
        val atlas = GlyphAtlas(tf, 12f * app.density)
        app.uploadGlyphAtlas(atlas, atlas.build(
            listOf("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.|0123456789ms% :=-_[](){}+/"), app.texArray.size))
        debugAtlas = atlas
    }

    fun runTest(app: App) {
        if (running) return
        running = true
        lines.clear()
        lines.add("=== mine test harness ===")

        thread {
            try {
                val baseUrl = "https://canneji.duckdns.org/janus"
                data class TestWord(val label: String, val itemId: String, val season: Int, val episode: Int, val word: String)
                val testWords = listOf(
                    TestWord("basic",           "saint-seiya", 1, 1,  "俺"),
                    TestWord("inflected_verb",  "saint-seiya", 1, 1,  "吹っ飛ばされたくなかったら"),  // ← 吹っ飛ぶ passive+desid+neg+cond
                    TestWord("inflected_adj",   "saint-seiya", 1, 1,  "強くっ"),                    // ← 強い contracted ku-form
                    TestWord("2kanji",          "saint-seiya", 1, 1,  "邪悪"),                      // じゃあく "wicked"
                    TestWord("4kanji_ateji",    "saint-seiya", 1, 1,  "黄金聖衣"),                  // ゴールドクロス "Gold Cloth"
                    TestWord("ateji",           "saint-seiya", 1, 1,  "小宇宙"),                    // コスモ "Cosmo"
                    TestWord("character",       "saint-seiya", 1, 1,  "星矢"),                      // セイヤ "Seiya"
                    TestWord("attack",          "saint-seiya", 1, 1,  "流星拳"),                    // りゅうせいけん "Meteor Fist"
                )

                // Step 0: Prerequisites
                log("0. checking prerequisites...")
                val client = app.ankiClient
                if (!client.isAvailable()) { log("FAIL: ankidroid not installed"); return@thread }
                if (!client.hasPermission()) { log("FAIL: no permission"); return@thread }
                for (i in 1..30) { if (app.api != null) break; Thread.sleep(500) }
                val api = app.api ?: run { log("FAIL: no api after 15s"); return@thread }
                log("0. OK")

                // Step 1: Clean test deck — delete only janus-test tagged notes
                log("1. cleaning test notes...")
                val cr = app.context.contentResolver
                val noteUri = android.net.Uri.parse("content://com.ichi2.anki.flashcards/notes")
                val cursor = cr.query(noteUri, arrayOf("_id", "tags"), null, null, null)
                var deleted = 0
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        val noteId = cursor.getLong(0)
                        val tags = cursor.getString(1) ?: ""
                        if (tags.contains("janus-test")) {
                            try {
                                cr.delete(android.net.Uri.withAppendedPath(noteUri, noteId.toString()), null, null)
                                deleted++
                            } catch (_: Exception) {}
                        }
                    }
                    cursor.close()
                }
                log("1. OK deleted $deleted old test notes")

                // Cache SuperSRT per episode
                val ssrtCache = HashMap<String, JSONObject>()
                fun getSuperSRT(iid: String, s: Int, e: Int): JSONObject? {
                    val key = "$iid/$s/$e"
                    ssrtCache[key]?.let { return it }
                    val result = fetchSuperSRT(baseUrl, api.token ?: "", iid, s, e) ?: return null
                    ssrtCache[key] = result
                    return result
                }

                // Prepare Anki deck + model once
                val ankiApi = com.ichi2.anki.api.AddContentApi(app.context)
                val deckName = "Janus-TestHarness"
                val decks = ankiApi.deckList ?: run { log("FAIL: can't list decks"); return@thread }
                var deckId = decks.entries.firstOrNull { it.value == deckName }?.key
                if (deckId == null) deckId = ankiApi.addNewDeck(deckName)
                if (deckId == null) { log("FAIL: can't create deck"); return@thread }
                val models = ankiApi.getModelList(1) ?: run { log("FAIL: can't list models"); return@thread }
                val modelId = models.entries.firstOrNull { it.value == AnkiDroidClient.MODEL_NAME }?.key
                if (modelId == null) { log("FAIL: ${AnkiDroidClient.MODEL_NAME} not found"); return@thread }
                val fields = ankiApi.getFieldList(modelId)
                log("2. OK deck=$deckId model=$modelId fields=${fields?.size}")

                var passCount = 0
                var failCount = 0

                for ((ti, tw) in testWords.withIndex()) {
                val testNum = ti + 1
                log("")
                log("--- [$testNum/${testWords.size}] ${tw.label}: ${tw.word} ---")

                // Fetch SuperSRT
                val ssrtJson = getSuperSRT(tw.itemId, tw.season, tw.episode)
                if (ssrtJson == null) { log("FAIL: super-srt null"); failCount++; continue }
                val cues = ssrtJson.getJSONArray("cues")
                val dict = ssrtJson.getJSONArray("dict")

                // Find word in cues
                val targetWord = tw.word
                var foundCueIdx = -1
                var foundWordIdx = -1
                var startMs = 0.0
                var endMs = 0.0
                var sentence = ""
                var dictIdx = -1

                for (ci in 0 until cues.length()) {
                    val cue = cues.getJSONObject(ci)
                    val words = cue.getJSONArray("w")
                    val sb = StringBuilder()
                    for (wi in 0 until words.length()) {
                        val w = words.getJSONObject(wi)
                        val surface = w.getString("s")
                        sb.append(surface)
                        if (surface == targetWord && foundCueIdx < 0) {
                            foundCueIdx = ci
                            foundWordIdx = wi
                            dictIdx = w.optInt("d", -1)
                            startMs = cue.getDouble("s")
                            endMs = cue.getDouble("e")
                        }
                    }
                    if (foundCueIdx == ci) sentence = sb.toString()
                }
                if (foundCueIdx < 0) { log("FAIL: '$targetWord' not found"); failCount++; continue }
                log("3. OK cue=$foundCueIdx word=$foundWordIdx start=${startMs.toLong()}ms")
                log("3. sentence=$sentence")

                // Step 4: Get dict entry
                log("3. looking up dict entry (idx=$dictIdx)...")
                var expression = targetWord
                var reading = ""
                var meaning = ""
                var jlpt = ""

                if (dictIdx >= 0 && dictIdx < dict.length()) {
                    val entry = dict.getJSONObject(dictIdx)
                    expression = entry.optString("t", targetWord)
                    reading = entry.optString("r", "")
                    jlpt = entry.optString("jlpt", "")
                    val meanings = entry.optJSONArray("m")
                    if (meanings != null && meanings.length() > 0) {
                        val parts = mutableListOf<String>()
                        for (i in 0 until meanings.length()) parts.add(meanings.getString(i))
                        meaning = parts.joinToString("; ")
                    }
                    if (expression.isEmpty()) { log("FAIL: no expression"); failCount++; continue }
                    if (meaning.isEmpty()) { log("FAIL: no meaning"); failCount++; continue }

                    // Build furigana reading from word data: [[charIdx, reading], ...]
                    val cue = cues.getJSONObject(foundCueIdx)
                    val w = cue.getJSONArray("w").getJSONObject(foundWordIdx)
                    val furis = w.optJSONArray("f")
                    if (furis != null && furis.length() > 0) {
                        val fr = StringBuilder()
                        var lastIdx = 0
                        for (fi in 0 until furis.length()) {
                            val f = furis.getJSONArray(fi)
                            val charIdx = f.getInt(0)
                            val furiReading = f.getString(1)
                            if (charIdx > lastIdx && charIdx <= expression.length) {
                                fr.append(expression.substring(lastIdx, charIdx))
                            }
                            val end = (charIdx + 1).coerceAtMost(expression.length)
                            fr.append(expression.substring(charIdx, end))
                            fr.append("[").append(furiReading).append("]")
                            lastIdx = end
                        }
                        if (lastIdx < expression.length) fr.append(expression.substring(lastIdx))
                        reading = fr.toString()
                    }
                    if (reading.isEmpty()) { log("FAIL: no reading"); failCount++; continue }
                    log("4. OK expr=$expression reading=$reading jlpt=$jlpt")
                    log("4. meaning=$meaning")
                } else {
                    log("FAIL: no dict entry (dictIdx=$dictIdx)")
                    failCount++; continue
                }

                // Resolve media from server
                val media = resolveMedia(baseUrl, api.token ?: "", tw.itemId, tw.season, tw.episode, startMs, endMs)
                if (media == null) { log("FAIL: server null"); failCount++; continue }
                if (media.first.isEmpty()) { log("FAIL: no audio"); failCount++; continue }
                if (media.second.isEmpty()) { log("FAIL: no image"); failCount++; continue }
                log("media: audio=${media.first.size}B img=${media.second.size}B")

                // Attach media
                var screenshotRef = ""
                var audioRef = ""
                val ts = System.currentTimeMillis()
                val imgFile = java.io.File(app.context.cacheDir, "mine_test_$ts.jpg")
                imgFile.writeBytes(media.second)
                val imgUri = androidx.core.content.FileProvider.getUriForFile(
                    app.context, "${app.context.packageName}.fileprovider", imgFile)
                app.context.grantUriPermission("com.ichi2.anki", imgUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                screenshotRef = ankiApi.addMediaFromUri(imgUri, "janus_$ts.jpg", "image") ?: ""
                imgFile.delete()
                val audioFile = java.io.File(app.context.cacheDir, "mine_test_$ts.mp3")
                audioFile.writeBytes(media.first)
                val audioUri = androidx.core.content.FileProvider.getUriForFile(
                    app.context, "${app.context.packageName}.fileprovider", audioFile)
                app.context.grantUriPermission("com.ichi2.anki", audioUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                audioRef = ankiApi.addMediaFromUri(audioUri, "janus_$ts.mp3", "audio") ?: ""
                audioFile.delete()
                if (screenshotRef.isEmpty()) { log("FAIL: screenshot attach"); failCount++; continue }
                if (audioRef.isEmpty()) { log("FAIL: audio attach"); failCount++; continue }

                // Build sentenceFurigana from cue words
                val cueObj = cues.getJSONObject(foundCueIdx)
                val cueWords = cueObj.getJSONArray("w")
                val sentenceFurigana = buildString {
                    for (wi in 0 until cueWords.length()) {
                        val w = cueWords.getJSONObject(wi)
                        val surface = w.getString("s")
                        val furis = w.optJSONArray("f")
                        if (furis != null && furis.length() > 0) {
                            var lastIdx = 0
                            for (fi in 0 until furis.length()) {
                                val f = furis.getJSONArray(fi)
                                val charIdx = f.getInt(0)
                                val furiReading = f.getString(1)
                                if (charIdx > lastIdx && charIdx <= surface.length) append(surface.substring(lastIdx, charIdx))
                                val end = (charIdx + 1).coerceAtMost(surface.length)
                                append(surface.substring(charIdx, end))
                                append("[").append(furiReading).append("]")
                                lastIdx = end
                            }
                            if (lastIdx < surface.length) append(surface.substring(lastIdx))
                        } else {
                            append(surface)
                        }
                    }
                }

                // Create card — Janus+ Immersion field order:
                // Expression, Reading, Meaning, Sentence, SentenceFurigana,
                // SentenceNoWord, Screenshot, Audio, Source, JLPT
                val sentenceNoWord = sentence.replace(expression, "___")
                val source = "Saint Seiya E${tw.episode}"
                val cardFields = arrayOf(
                    expression, reading, meaning, sentence, sentenceFurigana,
                    sentenceNoWord, screenshotRef, audioRef,
                    source, jlpt,
                )
                val noteId = ankiApi.addNote(modelId, deckId, cardFields, setOf("janus-test"))
                if (noteId == null || noteId <= 0) { log("FAIL: addNote=$noteId"); failCount++; continue }

                // Verify required fields
                val required = mapOf("Expression" to expression, "Reading" to reading,
                    "Meaning" to meaning, "Sentence" to sentence,
                    "Screenshot" to screenshotRef, "Audio" to audioRef)
                var fieldOk = true
                for ((name, value) in required) {
                    if (value.isEmpty()) { log("FAIL: $name empty"); fieldOk = false }
                }
                if (fieldOk) {
                    log("OK $expression [$reading] noteId=$noteId")
                    passCount++
                } else failCount++

                } // end for loop

                // Summary + sync
                log("")
                log("=== $passCount/${testWords.size} passed, $failCount failed ===")
                if (failCount == 0) {
                    log("sync 1/2 cards...")
                    Thread.sleep(2000)
                    app.onMainThread?.invoke {
                        app.context.startActivity(
                            android.content.Intent("com.ichi2.anki.DO_SYNC")
                                .setPackage("com.ichi2.anki")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    Thread.sleep(8000)
                    log("sync 2/2 media...")
                    app.onMainThread?.invoke {
                        app.context.startActivity(
                            android.content.Intent("com.ichi2.anki.DO_SYNC")
                                .setPackage("com.ichi2.anki")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    log("=== ALL PASSED ===")
                } else log("=== SOME FAILED ===")

            } catch (e: Exception) {
                log("EXCEPTION: ${e.message}")
                android.util.Log.e("MineTest", "Test failed", e)
            } finally {
                running = false
            }
        }
    }

    private fun log(msg: String) {
        lines.add(msg)
        android.util.Log.d("MineTest", msg)
    }

    private fun fetchSuperSRT(baseUrl: String, token: String, itemId: String, season: Int, episode: Int): JSONObject? {
        try {
            val req = okhttp3.Request.Builder()
                .url("$baseUrl/api/super-srt/$itemId/$season/$episode")
                .header("Authorization", "Bearer $token").build()
            val resp = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build().newCall(req).execute()
            if (!resp.isSuccessful) { log("super-srt ${resp.code}"); return null }
            val body = resp.body?.string() ?: return null
            return JSONObject(body)
        } catch (e: Exception) { log("super-srt err: ${e.message}"); return null }
    }

    private fun resolveMedia(baseUrl: String, token: String, itemId: String, season: Int, episode: Int,
                             startMs: Double, endMs: Double): Pair<ByteArray, ByteArray>? {
        try {
            val json = JSONObject().apply {
                put("item_id", itemId); put("season", season); put("episode", episode)
                put("start_ms", startMs); put("end_ms", endMs)
            }
            val body = okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
            val req = okhttp3.Request.Builder()
                .url("$baseUrl/api/card-resolve")
                .header("Authorization", "Bearer $token")
                .post(body).build()
            val resp = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build().newCall(req).execute()
            if (!resp.isSuccessful) { log("resolve ${resp.code}"); return null }
            val bytes = resp.body?.bytes() ?: return null

            var off = 0
            fun readInt(): Int {
                val v = (bytes[off].toInt() and 0xFF) or ((bytes[off+1].toInt() and 0xFF) shl 8) or
                        ((bytes[off+2].toInt() and 0xFF) shl 16) or ((bytes[off+3].toInt() and 0xFF) shl 24)
                off += 4; return v
            }
            val metaLen = readInt(); off += metaLen // skip meta (empty)
            val audioLen = readInt()
            val audio = if (audioLen > 0) bytes.copyOfRange(off, off + audioLen) else ByteArray(0)
            off += audioLen
            val imageLen = readInt()
            val image = if (imageLen > 0) bytes.copyOfRange(off, off + imageLen) else ByteArray(0)
            return Pair(audio, image)
        } catch (e: Exception) { log("resolve err: ${e.message}"); return null }
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        for (a in actions) { if (a == Action.BACK) app.goBack() }
    }

    override fun draw(app: App, rc: RC) {
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)
        val atlas = debugAtlas ?: return
        val pad = 2f
        val lineH = atlas.lineHeight + rc.dp(3f)
        val snapshot = lines.toList()
        for ((i, line) in snapshot.withIndex()) {
            var cx = rc.dp(16f)
            val y = rc.dp(20f) + i * lineH
            if (y > rc.h) break
            val color = when {
                line.startsWith("FAIL") || line.startsWith("EXCEPTION") -> floatArrayOf(1f, 0.3f, 0.3f)
                line.startsWith("=== ALL") -> floatArrayOf(0.3f, 1f, 0.3f)
                line.contains("OK") -> floatArrayOf(0.5f, 0.9f, 0.5f)
                line.startsWith("3. meaning") || line.startsWith("2. sentence") -> floatArrayOf(0.7f, 0.7f, 0.9f)
                else -> floatArrayOf(0.7f, 0.7f, 0.7f)
            }
            for (ch in line) {
                val g = atlas.glyphs[ch.code] ?: continue
                rc.batch.addQuad(cx - pad, y - g.ascent, g.w, g.h,
                    g.u0, g.v0, g.u1, g.v1, color[0], color[1], color[2], 1f, layer = g.page.toFloat())
                cx += g.advance
            }
        }
    }

    override fun cleanup(app: App) {}

    companion object {
        fun runFromAdb(app: App) {
            val state = MineTestState()
            app.transition(Screen.SETTINGS, state)
            Thread.sleep(500)
            state.runTest(app)
        }
    }
}
