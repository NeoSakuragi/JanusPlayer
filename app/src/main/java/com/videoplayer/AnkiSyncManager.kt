package com.videoplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class AnkiSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "AnkiSync"
        private const val PREFS = "anki_sync"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = AnkiWebClient(context)
    private val cardQueueDir = File(context.filesDir, "anki_queue").also { it.mkdirs() }
    private val mediaDir = File(context.cacheDir, "anki_media").also { it.mkdirs() }

    var hkey: String?
        get() = prefs.getString("hkey", null)
        private set(value) = prefs.edit().putString("hkey", value).apply()

    val isLoggedIn: Boolean get() = hkey != null

    data class PendingCard(
        val word: String,
        val reading: String,
        val meaning: String,
        val sentence: String,
        val sentenceFurigana: String,
        val source: String,
        val screenshotFile: File?,
        val audioFile: File?,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ── Auth ─────────────────────────────────────────────────────────

    fun login(email: String, password: String): Boolean {
        return try {
            val key = client.authenticate(email, password)
            hkey = key
            prefs.edit().putString("email", email).apply()
            Log.d(TAG, "Logged in: ${key.take(8)}...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Login failed: ${e.message}")
            false
        }
    }

    fun logout() {
        hkey = null
        prefs.edit().remove("email").remove("hkey").apply()
    }

    // ── Card Queue ───────────────────────────────────────────────────

    fun queueCard(card: PendingCard) {
        val json = JSONObject().apply {
            put("word", card.word)
            put("reading", card.reading)
            put("meaning", card.meaning)
            put("sentence", card.sentence)
            put("sentenceFurigana", card.sentenceFurigana)
            put("source", card.source)
            put("timestamp", card.timestamp)
            card.screenshotFile?.let { put("screenshot", it.absolutePath) }
            card.audioFile?.let { put("audio", it.absolutePath) }
        }
        val file = File(cardQueueDir, "${card.timestamp}.json")
        file.writeText(json.toString())
        Log.d(TAG, "Card queued: ${card.word} (${pendingCount()} pending)")
    }

    fun pendingCount(): Int = cardQueueDir.listFiles()?.count { it.extension == "json" } ?: 0

    fun pendingCards(): List<PendingCard> {
        return (cardQueueDir.listFiles() ?: emptyArray())
            .filter { it.extension == "json" }
            .sortedBy { it.name }
            .mapNotNull { file ->
                try {
                    val json = JSONObject(file.readText())
                    PendingCard(
                        word = json.getString("word"),
                        reading = json.optString("reading", ""),
                        meaning = json.getString("meaning"),
                        sentence = json.optString("sentence", ""),
                        sentenceFurigana = json.optString("sentenceFurigana", ""),
                        source = json.optString("source", ""),
                        screenshotFile = json.optString("screenshot", "").let { if (it.isNotEmpty()) File(it) else null },
                        audioFile = json.optString("audio", "").let { if (it.isNotEmpty()) File(it) else null },
                        timestamp = json.getLong("timestamp")
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Bad queue file ${file.name}: ${e.message}")
                    null
                }
            }
    }

    fun clearQueue() {
        cardQueueDir.listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() }
    }

    // ── Sync ─────────────────────────────────────────────────────────

    data class SyncProgress(val stage: String, val detail: String = "")

    fun sync(onProgress: (SyncProgress) -> Unit = {}): AnkiWebClient.SyncResult {
        val key = hkey ?: return AnkiWebClient.SyncResult(false, "Not logged in")
        val cards = pendingCards()
        if (cards.isEmpty()) return AnkiWebClient.SyncResult(true)

        try {
            // 1. Download current collection
            onProgress(SyncProgress("Downloading collection..."))
            val dbBytes = client.downloadCollection(key)
            val dbFile = File(context.cacheDir, "anki_collection.db")
            dbFile.writeBytes(dbBytes)
            Log.d(TAG, "Downloaded collection: ${dbBytes.size} bytes")

            // 2. Open DB, find note type + deck
            onProgress(SyncProgress("Adding ${cards.size} cards..."))
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            val (modelId, deckId) = findOrCreateModel(db)

            // 3. Add notes + cards
            val mediaFiles = mutableMapOf<String, ByteArray>()
            val now = System.currentTimeMillis()

            for ((idx, card) in cards.withIndex()) {
                val noteId = now + idx
                val cardId = noteId + 100_000
                val guid = generateGuid()
                val mod = (now / 1000).toInt()

                // Build fields
                val screenshotRef = card.screenshotFile?.let { f ->
                    if (f.exists()) {
                        val hash = md5(f.readBytes())
                        val ext = f.extension.ifEmpty { "jpg" }
                        val mediaName = "$hash.$ext"
                        mediaFiles[mediaName] = f.readBytes()
                        "<img src=\"$mediaName\">"
                    } else null
                } ?: ""

                val audioRef = card.audioFile?.let { f ->
                    if (f.exists()) {
                        val hash = md5(f.readBytes())
                        val ext = f.extension.ifEmpty { "mp3" }
                        val mediaName = "$hash.$ext"
                        mediaFiles[mediaName] = f.readBytes()
                        "[sound:$mediaName]"
                    } else null
                } ?: ""

                val fields = buildFieldString(
                    modelId, db,
                    card.word, card.reading, card.meaning,
                    card.sentence, card.sentenceFurigana,
                    card.source, screenshotRef, audioRef
                )
                val sfld = card.word
                val csum = fieldChecksum(sfld)

                db.execSQL(
                    "INSERT INTO notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) VALUES (?, ?, ?, ?, -1, ?, ?, ?, ?, 0, '')",
                    arrayOf(noteId, guid, modelId, mod, "janus", fields, sfld, csum)
                )

                db.execSQL(
                    "INSERT INTO cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data) VALUES (?, ?, ?, 0, ?, -1, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                    arrayOf(cardId, noteId, deckId, mod, nextDuePosition(db))
                )
            }

            // Update collection mod time
            db.execSQL("UPDATE col SET mod = ?", arrayOf(now))
            db.close()

            // 4. Upload modified collection
            onProgress(SyncProgress("Uploading collection..."))
            val modifiedBytes = dbFile.readBytes()
            val uploadResult = client.uploadCollection(key, modifiedBytes)
            if (!uploadResult.success) {
                return uploadResult
            }

            // 5. Upload media files
            if (mediaFiles.isNotEmpty()) {
                onProgress(SyncProgress("Uploading ${mediaFiles.size} media files..."))
                val mediaResult = client.uploadMedia(key, mediaFiles)
                if (!mediaResult.success) {
                    Log.w(TAG, "Media upload failed: ${mediaResult.error}")
                }
            }

            // 6. Clear queue
            clearQueue()
            dbFile.delete()

            Log.d(TAG, "Sync complete: ${cards.size} cards, ${mediaFiles.size} media files")
            return AnkiWebClient.SyncResult(true)

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
            return AnkiWebClient.SyncResult(false, e.message)
        }
    }

    // ── DB Helpers ───────────────────────────────────────────────────

    private fun findOrCreateModel(db: SQLiteDatabase): Pair<Long, Long> {
        val cursor = db.rawQuery("SELECT models, decks FROM col", null)
        cursor.moveToFirst()
        val modelsJson = JSONObject(cursor.getString(0))
        val decksJson = JSONObject(cursor.getString(1))
        cursor.close()

        // Find existing "Immersion Sentences" model, or first model
        var modelId = 0L
        var fieldNames = listOf<String>()
        for (key in modelsJson.keys()) {
            val m = modelsJson.getJSONObject(key)
            val name = m.getString("name")
            if (name == "Immersion Sentences" || name == "Janus Immersion Card") {
                modelId = key.toLong()
                val flds = m.getJSONArray("flds")
                fieldNames = (0 until flds.length()).map { flds.getJSONObject(it).getString("name") }
                break
            }
        }
        if (modelId == 0L) {
            // Use first available model
            val firstKey = modelsJson.keys().next()
            modelId = firstKey.toLong()
        }

        // Find "Janus Mining" or "Immersion" deck, or use default
        var deckId = 1L
        for (key in decksJson.keys()) {
            val d = decksJson.getJSONObject(key)
            val name = d.getString("name")
            if (name == "Janus Mining" || name == "Immersion") {
                deckId = key.toLong()
                break
            }
        }

        Log.d(TAG, "Using model=$modelId, deck=$deckId")
        return Pair(modelId, deckId)
    }

    private fun buildFieldString(
        modelId: Long, db: SQLiteDatabase,
        word: String, reading: String, meaning: String,
        sentence: String, sentenceFurigana: String,
        source: String, screenshotRef: String, audioRef: String
    ): String {
        val cursor = db.rawQuery("SELECT models FROM col", null)
        cursor.moveToFirst()
        val modelsJson = JSONObject(cursor.getString(0))
        cursor.close()

        val model = modelsJson.getJSONObject(modelId.toString())
        val flds = model.getJSONArray("flds")
        val fieldCount = flds.length()
        val fieldNames = (0 until fieldCount).map { flds.getJSONObject(it).getString("name") }

        val values = Array(fieldCount) { "" }
        for (i in fieldNames.indices) {
            values[i] = when (fieldNames[i].lowercase()) {
                "front", "word" -> word
                "back", "meaning" -> meaning
                "reading" -> if (sentenceFurigana.isNotEmpty()) sentenceFurigana else reading
                "sentence" -> sentence
                "sentence no word" -> sentence
                "kanji" -> word
                "screenshot" -> screenshotRef
                "audio" -> audioRef
                "source" -> source
                "tags" -> ""
                else -> ""
            }
        }
        return values.joinToString("")
    }

    private fun nextDuePosition(db: SQLiteDatabase): Int {
        val cursor = db.rawQuery("SELECT conf FROM col", null)
        cursor.moveToFirst()
        val conf = JSONObject(cursor.getString(0))
        cursor.close()
        val pos = conf.optInt("nextPos", 1)
        db.execSQL("UPDATE col SET conf = ?", arrayOf(
            conf.put("nextPos", pos + 1).toString()
        ))
        return pos
    }

    private fun generateGuid(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val r = java.security.SecureRandom()
        return (1..10).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun fieldChecksum(field: String): Long {
        val digest = MessageDigest.getInstance("SHA-1")
        val hash = digest.digest(field.toByteArray(Charsets.UTF_8))
        // First 4 bytes as unsigned int
        return ((hash[0].toLong() and 0xFF) shl 24) or
               ((hash[1].toLong() and 0xFF) shl 16) or
               ((hash[2].toLong() and 0xFF) shl 8) or
               (hash[3].toLong() and 0xFF)
    }

    private fun md5(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
