package com.videoplayer

import android.util.Log
import com.github.luben.zstd.Zstd
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class AnkiWebClient {

    companion object {
        private const val TAG = "AnkiWeb"
        private const val SYNC_URL = "https://sync.ankiweb.net"
        private const val CLIENT_VER = "anki,25.02.5,lin"

        private const val DECK_ID = 1778789751000L
        private const val MODEL_ID = 1778789751001L
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private var baseUrl = SYNC_URL
    private var sessionKey = UUID.randomUUID().toString()
    var hkey: String = ""
        private set

    data class SyncResult(val success: Boolean, val error: String? = null)

    // ── Transport ────────────────────────────────────────────────────

    private fun syncHeader(): String = JSONObject().apply {
        put("v", 10)
        put("k", hkey)
        put("c", CLIENT_VER)
        put("s", sessionKey)
    }.toString()

    private fun syncRequest(method: String, payload: JSONObject): JSONObject? {
        val jsonBytes = payload.toString().toByteArray()
        val compressed = ByteArray(Zstd.compressBound(jsonBytes.size.toLong()).toInt())
        val compressedSize = Zstd.compress(compressed, jsonBytes, 3)
        val body = compressed.copyOf(compressedSize.toInt())

        val request = Request.Builder()
            .url("$baseUrl/sync/$method")
            .header("anki-sync", syncHeader())
            .header("Content-Type", "application/octet-stream")
            .post(body.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        val response = http.newCall(request).execute()

        if (response.code == 308) {
            val location = response.header("Location") ?: ""
            response.close()
            val idx = location.indexOf("/sync/")
            baseUrl = if (idx > 0) location.substring(0, idx) else location.trimEnd('/')
            sessionKey = UUID.randomUUID().toString()
            Log.d(TAG, "308 redirect → $baseUrl")
            return syncRequest(method, payload)
        }

        val responseBytes = response.body?.bytes() ?: byteArrayOf()
        response.close()

        if (response.code != 200) {
            val msg = String(responseBytes, Charsets.UTF_8).take(200)
            throw RuntimeException("$method: HTTP ${response.code} — $msg")
        }

        val decompressed = try {
            val origSize = Zstd.getFrameContentSize(responseBytes)
            val buf = ByteArray(if (origSize > 0) origSize.toInt() else responseBytes.size * 4)
            val n = Zstd.decompress(buf, responseBytes)
            buf.copyOf(n.toInt())
        } catch (_: Exception) {
            responseBytes
        }

        return try {
            JSONObject(String(decompressed, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    // ── Public API ───────────────────────────────────────────────────

    fun authenticate(email: String, password: String): String {
        val payload = JSONObject().apply {
            put("u", email)
            put("p", password)
        }
        val result = syncRequest("hostKey", payload)
            ?: throw RuntimeException("Empty hostKey response")
        hkey = result.getString("key")
        Log.d(TAG, "Authenticated: ${hkey.take(8)}...")
        return hkey
    }

    fun pushCards(cards: List<CardFields>): SyncResult {
        if (hkey.isEmpty()) return SyncResult(false, "Not authenticated")
        if (cards.isEmpty()) return SyncResult(true)

        try {
            // 1. Meta
            val meta = syncRequest("meta", JSONObject().apply {
                put("v", 10)
                put("cv", CLIENT_VER)
            }) ?: return SyncResult(false, "Meta failed")

            val serverUsn = meta.getInt("usn")
            Log.d(TAG, "Meta: usn=$serverUsn empty=${meta.optBoolean("empty")}")

            // 2. Start
            syncRequest("start", JSONObject().apply {
                put("minUsn", serverUsn)
                put("lnewer", false)
                put("graves", JSONObject().apply {
                    put("cards", JSONArray())
                    put("notes", JSONArray())
                    put("decks", JSONArray())
                })
            })

            // 3. Build notes + cards
            val now = System.currentTimeMillis() / 1000
            val nowMs = System.currentTimeMillis()

            val notesArray = JSONArray()
            val cardsArray = JSONArray()

            for ((i, card) in cards.withIndex()) {
                val noteId = nowMs + i * 2
                val cardId = nowMs + i * 2 + 1
                val guid = UUID.randomUUID().toString().replace("-", "").take(10)

                val fields = listOf(
                    card.word, card.reading, card.meaning,
                    card.sentence, card.source
                ).joinToString("")

                val csum = sha1First4(card.word)

                notesArray.put(JSONObject().apply {
                    put("id", noteId)
                    put("guid", guid)
                    put("mid", MODEL_ID)
                    put("mod", now)
                    put("usn", -1)
                    put("tags", "janus")
                    put("flds", fields)
                    put("sfld", card.word)
                    put("csum", csum)
                    put("flags", 0)
                    put("data", "")
                })

                cardsArray.put(JSONObject().apply {
                    put("id", cardId)
                    put("nid", noteId)
                    put("did", DECK_ID)
                    put("ord", 0)
                    put("mod", now)
                    put("usn", -1)
                    put("type", 0)
                    put("queue", 0)
                    put("due", noteId)
                    put("ivl", 0)
                    put("factor", 0)
                    put("reps", 0)
                    put("lapses", 0)
                    put("left", 0)
                    put("odue", 0)
                    put("odid", 0)
                    put("flags", 0)
                    put("data", "")
                })
            }

            // 4. applyChanges — model + deck + notes + cards in one shot
            syncRequest("applyChanges", JSONObject().apply {
                put("changes", JSONObject().apply {
                    put("models", JSONArray().put(buildModel(now)))
                    put("decks", JSONArray().apply {
                        put(JSONArray().put(buildDeck(now)))
                        put(JSONArray())
                    })
                    put("tags", JSONArray().put("janus"))
                    put("notes", notesArray)
                    put("cards", cardsArray)
                })
            })

            // 5. Finish (skip sanityCheck2)
            syncRequest("finish", JSONObject())

            Log.d(TAG, "Pushed ${cards.size} cards to AnkiWeb")
            return SyncResult(true)

        } catch (e: Exception) {
            Log.e(TAG, "Push failed: ${e.message}", e)
            try { syncRequest("abort", JSONObject()) } catch (_: Exception) {}
            return SyncResult(false, e.message)
        }
    }

    // ── Model + Deck Templates ───────────────────────────────────────

    private fun buildModel(mod: Long): JSONObject = JSONObject().apply {
        put("id", MODEL_ID)
        put("name", "Janus Mining")
        put("type", 0)
        put("mod", mod)
        put("usn", -1)
        put("sortf", 0)
        put("did", DECK_ID)
        put("tmpls", JSONArray().put(JSONObject().apply {
            put("name", "Recognition")
            put("ord", 0)
            put("did", JSONObject.NULL)
            put("bafmt", "")
            put("bqfmt", "")
            put("qfmt", "<div style='font-size:2em;color:#bb86fc'>{{Word}}</div>" +
                "<div style='color:#aaa'>{{Reading}}</div><br>" +
                "<div style='color:#ccc'>{{Sentence}}</div>")
            put("afmt", "{{FrontSide}}<hr>" +
                "<div style='font-size:1.3em'>{{Meaning}}</div>" +
                "<div style='color:#666;margin-top:10px'>{{Source}}</div>")
        }))
        put("flds", JSONArray().apply {
            for ((i, name) in listOf("Word", "Reading", "Meaning", "Sentence", "Source").withIndex()) {
                put(JSONObject().apply {
                    put("name", name)
                    put("ord", i)
                    put("sticky", false)
                    put("rtl", false)
                    put("font", if (name in listOf("Word", "Reading", "Sentence")) "Noto Sans JP" else "Arial")
                    put("size", when (name) { "Word" -> 20; "Reading" -> 16; "Meaning" -> 16; "Sentence" -> 14; else -> 12 })
                    put("media", JSONArray())
                })
            }
        })
        put("css", ".card{font-family:'Noto Sans JP',sans-serif;background:#1a1a2e;color:white;text-align:center;padding:20px}")
        put("tags", JSONArray())
        put("vers", JSONArray())
        put("req", JSONArray().put(JSONArray().apply { put(0); put("all"); put(JSONArray().put(0)) }))
    }

    private fun buildDeck(mod: Long): JSONObject = JSONObject().apply {
        put("id", DECK_ID)
        put("name", "Janus Mining")
        put("mod", mod)
        put("usn", -1)
        put("collapsed", false)
        put("desc", "")
        put("dyn", 0)
        put("conf", 1)
        put("extendNew", 0)
        put("extendRev", 0)
        put("lrnToday", JSONArray().apply { put(0); put(0) })
        put("newToday", JSONArray().apply { put(0); put(0) })
        put("revToday", JSONArray().apply { put(0); put(0) })
        put("timeToday", JSONArray().apply { put(0); put(0) })
    }

    private fun sha1First4(text: String): Long {
        val digest = MessageDigest.getInstance("SHA-1")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        val hex = hash.take(4).joinToString("") { "%02x".format(it) }
        return java.lang.Long.parseUnsignedLong(hex, 16)
    }

    data class CardFields(
        val word: String,
        val reading: String,
        val meaning: String,
        val sentence: String,
        val source: String,
    )
}
