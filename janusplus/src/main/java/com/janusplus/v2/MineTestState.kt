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
                // Test input — only what a user would provide
                val itemId = "saint-seiya"
                val season = 1
                val episode = 1
                val targetWord = "俺"
                val baseUrl = "https://canneji.duckdns.org/janus"

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

                // Step 2: Fetch SuperSRT
                log("2. fetching super-srt $itemId s${season}e${episode}...")
                val ssrtJson = fetchSuperSRT(baseUrl, api.token ?: "", itemId, season, episode)
                if (ssrtJson == null) { log("FAIL: super-srt returned null"); return@thread }
                val cues = ssrtJson.getJSONArray("cues")
                val dict = ssrtJson.getJSONArray("dict")
                log("2. OK ${cues.length()} cues, ${dict.length()} dict entries")

                // Step 3: Find the word in the cues
                log("3. looking up '$targetWord' in cues...")
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
                if (foundCueIdx < 0) { log("FAIL: word '$targetWord' not found in any cue"); return@thread }
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
                    if (expression.isEmpty()) { log("FAIL: dict entry has no expression"); return@thread }
                    if (meaning.isEmpty()) { log("FAIL: dict entry has no meaning"); return@thread }

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
                    if (reading.isEmpty()) { log("FAIL: no reading"); return@thread }
                    log("4. OK expr=$expression reading=$reading jlpt=$jlpt")
                    log("4. meaning=$meaning")
                } else {
                    log("FAIL: no dict entry for '$targetWord' (dictIdx=$dictIdx)")
                    return@thread
                }

                // Step 5: Resolve media from server
                log("5. resolving media from server...")
                val media = resolveMedia(baseUrl, api.token ?: "", itemId, season, episode, startMs, endMs)
                if (media == null) { log("FAIL: server returned null"); return@thread }
                if (media.first.isEmpty()) { log("FAIL: no audio"); return@thread }
                if (media.second.isEmpty()) { log("FAIL: no image"); return@thread }
                log("5. OK audio=${media.first.size}B img=${media.second.size}B")

                // Step 6: Prepare Anki deck + model
                log("6. preparing anki...")
                val ankiApi = com.ichi2.anki.api.AddContentApi(app.context)
                val deckName = "Janus-TestHarness"
                val decks = ankiApi.deckList ?: run { log("FAIL: can't list decks"); return@thread }
                var deckId = decks.entries.firstOrNull { it.value == deckName }?.key
                if (deckId == null) deckId = ankiApi.addNewDeck(deckName)
                if (deckId == null) { log("FAIL: can't create deck"); return@thread }

                val models = ankiApi.getModelList(1) ?: run { log("FAIL: can't list models"); return@thread }
                val modelId = models.entries.firstOrNull { it.value == "Immersion Sentences" }?.key
                if (modelId == null) { log("FAIL: Immersion Sentences model not found"); return@thread }
                val fields = ankiApi.getFieldList(modelId)
                log("6. OK deck=$deckId model=$modelId fields=${fields?.size}")

                // Step 7: Attach media
                log("7. attaching media...")
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

                if (screenshotRef.isEmpty()) { log("FAIL: screenshot attach failed"); return@thread }
                if (audioRef.isEmpty()) { log("FAIL: audio attach failed"); return@thread }
                log("7. imgRef=$screenshotRef")
                log("7. audioRef=$audioRef")
                log("7. OK screenshot + audio attached")

                // Step 8: Create card
                log("8. creating card...")
                val sentenceNoWord = sentence.replace(expression, "___")
                val source = "$itemId s${season}e${episode}"
                // Immersion Sentences fields:
                // Front, Back, Add Reverse, Sentence, Sentence No Word, Reading,
                // Kanji, Screenshot, Audio, tags, chatgpt, qwen-translate, qwen-nuance
                val cardFields = arrayOf(
                    expression, meaning, "", sentence, sentenceNoWord,
                    reading, expression, screenshotRef, audioRef,
                    "janus $jlpt".trim(), "", "", ""
                )
                val noteId = ankiApi.addNote(modelId, deckId, cardFields, setOf("janus-test"))
                if (noteId == null || noteId <= 0) { log("FAIL: addNote returned $noteId"); return@thread }
                log("8. OK noteId=$noteId")

                // Step 9: Verify all required fields
                log("9. verifying fields...")
                val required = mapOf(
                    "Front" to expression, "Back" to meaning,
                    "Sentence" to sentence, "Reading" to reading,
                    "Kanji" to expression, "Screenshot" to screenshotRef, "Audio" to audioRef
                )
                var allOk = true
                for ((name, value) in required) {
                    if (value.isEmpty()) { log("FAIL: $name is empty"); allOk = false }
                }
                if (allOk) log("9. OK all fields filled")

                // Step 10: Sync
                log("10. triggering sync...")
                app.onMainThread?.invoke {
                    app.context.sendBroadcast(android.content.Intent("com.ichi2.anki.DO_SYNC"))
                }
                log("10. OK")
                log("=== ALL PASSED ===")

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
