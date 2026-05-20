package com.janusplus

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class MineQueue(private val context: Context) {

    data class MineRequest(
        val itemId: String,
        val season: Int,
        val episode: Int,
        val wordIndex: Int,
        val startMs: Double,
        val endMs: Double,
        val screenshotMs: Double,
        // Pre-filled from SuperSRT (so we don't lose it)
        val expression: String,
        val reading: String,
        val meaning: String,
        val sentence: String,
        val jlpt: String,
        val source: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("item_id", itemId); put("season", season); put("episode", episode)
            put("word_index", wordIndex)
            put("start_ms", startMs); put("end_ms", endMs); put("screenshot_ms", screenshotMs)
            put("expression", expression); put("reading", reading)
            put("meaning", meaning); put("sentence", sentence)
            put("jlpt", jlpt); put("source", source)
        }

        companion object {
            fun fromJson(j: JSONObject) = MineRequest(
                itemId = j.getString("item_id"), season = j.getInt("season"), episode = j.getInt("episode"),
                wordIndex = j.getInt("word_index"),
                startMs = j.getDouble("start_ms"), endMs = j.getDouble("end_ms"),
                screenshotMs = j.getDouble("screenshot_ms"),
                expression = j.optString("expression", ""), reading = j.optString("reading", ""),
                meaning = j.optString("meaning", ""), sentence = j.optString("sentence", ""),
                jlpt = j.optString("jlpt", ""), source = j.optString("source", ""),
            )
        }
    }

    private val pending = ConcurrentLinkedQueue<MineRequest>()
    @Volatile var processing = false; private set
    @Volatile var lastResult = ""; private set
    @Volatile var queueSize = 0; private set

    var baseUrl = ""
    var token = ""
    var ankiClient: AnkiDroidClient? = null

    fun enqueue(req: MineRequest) {
        pending.add(req)
        queueSize = pending.size
        save()
        if (!processing) processNext()
    }

    private fun processNext() {
        val req = pending.peek() ?: run { processing = false; return }
        processing = true

        thread {
            try {
                val cardBlob = fetchCardBlob(req)
                if (cardBlob != null) {
                    val result = pushToAnki(cardBlob, req)
                    lastResult = when (result) {
                        is AnkiDroidClient.Result.Success -> "mined: ${req.expression}"
                        is AnkiDroidClient.Result.Duplicate -> "dupe: ${req.expression}"
                        else -> "anki err: ${req.expression}"
                    }
                } else {
                    // Server unavailable — use local data only (no audio/screenshot)
                    val result = pushLocalCard(req)
                    lastResult = when (result) {
                        is AnkiDroidClient.Result.Success -> "mined (local): ${req.expression}"
                        is AnkiDroidClient.Result.Duplicate -> "dupe: ${req.expression}"
                        else -> "anki err: ${req.expression}"
                    }
                }
                pending.poll()
                queueSize = pending.size
                save()
            } catch (e: Exception) {
                Log.e("MineQueue", "Process failed: ${e.message}")
                lastResult = "err: ${e.message}"
            }
            processing = false
            if (pending.isNotEmpty()) processNext()
        }
    }

    private fun fetchCardBlob(req: MineRequest): CardBlob? {
        if (baseUrl.isEmpty() || token.isEmpty()) return null
        try {
            val json = org.json.JSONObject().apply {
                put("item_id", req.itemId)
                put("season", req.season)
                put("episode", req.episode)
                put("start_ms", req.startMs)
                put("end_ms", req.endMs)
                put("word", req.expression)
            }
            val body = okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(), json.toString())
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/api/card-resolve")
                .header("Authorization", "Bearer $token")
                .post(body).build()
            val response = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build().newCall(request).execute()
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            return parseCardBlob(bytes)
        } catch (e: Exception) {
            Log.w("MineQueue", "Server fetch failed: ${e.message}")
            return null
        }
    }

    data class CardBlob(
        val expression: String, val reading: String, val meaning: String,
        val sentence: String, val jlpt: String, val source: String,
        val audioData: ByteArray?, val imageData: ByteArray?,
    )

    private fun parseCardBlob(bytes: ByteArray): CardBlob {
        var off = 0
        fun readInt(): Int {
            val v = (bytes[off].toInt() and 0xFF) or ((bytes[off+1].toInt() and 0xFF) shl 8) or
                    ((bytes[off+2].toInt() and 0xFF) shl 16) or ((bytes[off+3].toInt() and 0xFF) shl 24)
            off += 4; return v
        }
        val metaLen = readInt()
        val meta = JSONObject(String(bytes, off, metaLen, Charsets.UTF_8)); off += metaLen
        val audioLen = readInt()
        val audio = if (audioLen > 0) bytes.copyOfRange(off, off + audioLen) else null; off += audioLen
        val imageLen = readInt()
        val image = if (imageLen > 0) bytes.copyOfRange(off, off + imageLen) else null

        return CardBlob(
            expression = meta.optString("expression", ""),
            reading = meta.optString("reading", ""),
            meaning = meta.optString("meaning", ""),
            sentence = meta.optString("sentence", ""),
            jlpt = meta.optString("jlpt", ""),
            source = meta.optString("source", ""),
            audioData = audio, imageData = image,
        )
    }

    private fun pushToAnki(blob: CardBlob, req: MineRequest): AnkiDroidClient.Result {
        val client = ankiClient ?: return AnkiDroidClient.Result.NotInstalled

        var imgFile: File? = null
        var audioFile: File? = null
        if (blob.imageData != null) {
            imgFile = File(context.cacheDir, "mine_${System.currentTimeMillis()}.jpg")
            imgFile.writeBytes(blob.imageData)
        }
        if (blob.audioData != null) {
            audioFile = File(context.cacheDir, "mine_${System.currentTimeMillis()}.mp3")
            audioFile.writeBytes(blob.audioData)
        }

        val card = AnkiDroidClient.CardInfo(
            expression = blob.expression.ifEmpty { req.expression },
            reading = blob.reading.ifEmpty { req.reading },
            meaning = blob.meaning.ifEmpty { req.meaning },
            sentence = blob.sentence.ifEmpty { req.sentence },
            jlpt = blob.jlpt.ifEmpty { req.jlpt },
            source = blob.source.ifEmpty { req.source },
            screenshotFile = imgFile,
            audioFile = audioFile,
        )
        val result = client.addCard(card)
        imgFile?.delete()
        audioFile?.delete()
        return result
    }

    private fun pushLocalCard(req: MineRequest): AnkiDroidClient.Result {
        val client = ankiClient ?: return AnkiDroidClient.Result.NotInstalled
        return client.addCard(AnkiDroidClient.CardInfo(
            expression = req.expression, reading = req.reading,
            meaning = req.meaning, sentence = req.sentence,
            jlpt = req.jlpt, source = req.source,
        ))
    }

    private fun queueFile() = File(context.filesDir, "mine_queue.json")

    private fun save() {
        try {
            val arr = JSONArray()
            for (r in pending) arr.put(r.toJson())
            queueFile().writeText(arr.toString())
        } catch (_: Exception) {}
    }

    fun load() {
        try {
            val text = queueFile().readText()
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                pending.add(MineRequest.fromJson(arr.getJSONObject(i)))
            }
            queueSize = pending.size
            if (pending.isNotEmpty()) processNext()
        } catch (_: Exception) {}
    }
}
