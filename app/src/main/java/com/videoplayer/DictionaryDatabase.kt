package com.videoplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class DictionaryDatabase private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DictDB"
        private const val DB_NAME = "dictionary.db"
        const val PREBUILT_URL = "https://neomobiles.duckdns.org/dict/dictionary_lite.db.gz"

        @Volatile
        private var instance: DictionaryDatabase? = null

        fun getInstance(context: Context): DictionaryDatabase {
            return instance ?: synchronized(this) {
                instance ?: DictionaryDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    private val dbFile = context.getDatabasePath(DB_NAME)
    private var db: SQLiteDatabase? = null

    fun isReady(): Boolean = dbFile.exists() && dbFile.length() > 1000

    fun ensureReady() {
        if (isReady()) {
            openDb()
            ensureExtraTables()
            return
        }
        // Try to copy from assets as fallback
        try {
            Log.d(TAG, "Copying pre-built dictionary from assets...")
            dbFile.parentFile?.mkdirs()
            context.assets.open(DB_NAME).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output, 8192)
                }
            }
            Log.d(TAG, "Dictionary copied: ${dbFile.length() / 1_048_576} MB")
        } catch (_: Exception) {
            Log.d(TAG, "No asset dictionary, creating empty DB")
            dbFile.parentFile?.mkdirs()
            val newDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            newDb.execSQL("CREATE TABLE IF NOT EXISTS dict_entries (term TEXT NOT NULL, reading TEXT NOT NULL, tags TEXT DEFAULT '', score INTEGER DEFAULT 0, meanings TEXT DEFAULT '', sequence INTEGER DEFAULT 0, source TEXT DEFAULT '')")
            newDb.execSQL("CREATE TABLE IF NOT EXISTS frequencies (term TEXT NOT NULL, reading TEXT, freq INTEGER NOT NULL, source TEXT DEFAULT '')")
            newDb.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_term ON dict_entries(term)")
            newDb.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_reading ON dict_entries(reading)")
            newDb.execSQL("CREATE INDEX IF NOT EXISTS idx_freq_term ON frequencies(term)")
            newDb.close()
        }
        openDb()
        ensureExtraTables()
    }

    fun downloadPrebuilt(onProgress: (String, Int) -> Unit, onComplete: (Boolean, String) -> Unit) {
        Thread {
            try {
                onProgress("Downloading...", 0)
                val tempGz = java.io.File(context.cacheDir, "dict_prebuilt.db.gz")
                val tempDb = java.io.File(context.cacheDir, "dict_prebuilt.db")

                var currentUrl = PREBUILT_URL
                var redirects = 0
                while (redirects < 5) {
                    val conn = java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 60_000
                    conn.instanceFollowRedirects = false
                    conn.connect()
                    val code = conn.responseCode
                    if (code in 301..303 || code == 307 || code == 308) {
                        currentUrl = conn.getHeaderField("Location") ?: throw Exception("Redirect without Location")
                        conn.disconnect()
                        redirects++
                        continue
                    }
                    if (code != 200) throw Exception("HTTP $code")

                    val total = conn.contentLength.toLong()
                    var downloaded = 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(tempGz).use { output ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) onProgress("Downloading...", ((downloaded * 100) / total).toInt())
                            }
                        }
                    }
                    conn.disconnect()
                    break
                }

                onProgress("Decompressing...", 0)
                java.util.zip.GZIPInputStream(java.io.FileInputStream(tempGz)).use { gis ->
                    FileOutputStream(tempDb).use { out -> gis.copyTo(out, 65536) }
                }
                tempGz.delete()

                // Close existing DB, replace
                close()
                dbFile.parentFile?.mkdirs()
                tempDb.renameTo(dbFile)

                onProgress("Done", 100)
                openDb()
                ensureExtraTables()

                val entryCount = try {
                    db?.rawQuery("SELECT COUNT(*) FROM dict_entries", null)?.use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    } ?: 0
                } catch (_: Exception) { 0 }

                onComplete(true, "$entryCount dictionary entries ready")
            } catch (e: Exception) {
                Log.e(TAG, "Prebuilt download failed: ${e.message}", e)
                onComplete(false, e.message ?: "Unknown error")
            }
        }.start()
    }

    fun close() {
        try { db?.close() } catch (_: Exception) {}
        db = null
    }

    private fun openDb() {
        if (db == null || !db!!.isOpen) {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE)
        }
    }

    private fun ensureExtraTables() {
        val db = db ?: return

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS installed_dicts (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                entry_count INTEGER DEFAULT 0,
                installed_at INTEGER DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pitch_accents (
                term TEXT NOT NULL,
                reading TEXT,
                pitch_data TEXT NOT NULL,
                source TEXT NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kanji_entries (
                character TEXT NOT NULL,
                onyomi TEXT,
                kunyomi TEXT,
                tags TEXT,
                meanings TEXT,
                stats TEXT,
                source TEXT NOT NULL
            )
        """)

        // Add source column to existing tables if not present
        try {
            db.execSQL("ALTER TABLE dict_entries ADD COLUMN source TEXT DEFAULT 'bundled'")
        } catch (_: Exception) {} // column already exists

        try {
            db.execSQL("ALTER TABLE frequencies ADD COLUMN source TEXT DEFAULT 'bundled'")
        } catch (_: Exception) {}

        // Create indexes for new tables if they don't exist
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pitch_term ON pitch_accents(term)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_kanji_char ON kanji_entries(character)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_entries_source ON dict_entries(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_freq_source ON frequencies(source)")
    }

    // ── Query methods ────────────────────────────────────────────────

    data class DictEntry(
        val term: String,
        val reading: String,
        val tags: String,
        val score: Int,
        val meanings: List<String>,
        val frequency: Int?
    )

    data class PitchAccent(
        val term: String,
        val reading: String?,
        val pitchData: String
    )

    data class KanjiEntry(
        val character: String,
        val onyomi: String,
        val kunyomi: String,
        val tags: String,
        val meanings: List<String>,
        val stats: String
    )

    fun lookup(term: String): List<DictEntry> {
        val db = db ?: return emptyList()
        val cursor = db.rawQuery("""
            SELECT e.term, e.reading, e.tags, e.score, e.meanings,
                   (SELECT f.freq FROM frequencies f WHERE f.term = e.term LIMIT 1) as freq
            FROM dict_entries e
            WHERE e.term = ? OR e.reading = ?
            ORDER BY e.score DESC
            LIMIT 20
        """, arrayOf(term, term))

        val results = mutableListOf<DictEntry>()
        while (cursor.moveToNext()) {
            results.add(DictEntry(
                term = cursor.getString(0),
                reading = cursor.getString(1),
                tags = cursor.getString(2) ?: "",
                score = cursor.getInt(3),
                meanings = cursor.getString(4)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                frequency = if (cursor.isNull(5)) null else cursor.getInt(5)
            ))
        }
        cursor.close()
        return results
    }

    fun lookupPitchAccent(term: String): List<PitchAccent> {
        val db = db ?: return emptyList()
        val cursor = db.rawQuery(
            "SELECT term, reading, pitch_data FROM pitch_accents WHERE term = ?",
            arrayOf(term)
        )
        val results = mutableListOf<PitchAccent>()
        while (cursor.moveToNext()) {
            results.add(PitchAccent(
                term = cursor.getString(0),
                reading = cursor.getString(1),
                pitchData = cursor.getString(2)
            ))
        }
        cursor.close()
        return results
    }

    fun lookupKanji(character: String): KanjiEntry? {
        val db = db ?: return null
        val cursor = db.rawQuery(
            "SELECT character, onyomi, kunyomi, tags, meanings, stats FROM kanji_entries WHERE character = ? LIMIT 1",
            arrayOf(character)
        )
        val entry = if (cursor.moveToFirst()) {
            KanjiEntry(
                character = cursor.getString(0),
                onyomi = cursor.getString(1) ?: "",
                kunyomi = cursor.getString(2) ?: "",
                tags = cursor.getString(3) ?: "",
                meanings = cursor.getString(4)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                stats = cursor.getString(5) ?: ""
            )
        } else null
        cursor.close()
        return entry
    }

    fun getFrequency(term: String): Int? {
        val db = db ?: return null
        val cursor = db.rawQuery("SELECT freq FROM frequencies WHERE term = ? LIMIT 1", arrayOf(term))
        val freq = if (cursor.moveToFirst()) cursor.getInt(0) else null
        cursor.close()
        return freq
    }

    fun hasEntry(term: String): Boolean {
        val db = db ?: return false
        val cursor = db.rawQuery("SELECT 1 FROM dict_entries WHERE term = ? LIMIT 1", arrayOf(term))
        val found = cursor.moveToFirst()
        cursor.close()
        return found
    }

    fun getAllTerms(): HashSet<String> {
        val set = HashSet<String>(500000)
        val db = db ?: return set
        val cursor = db.rawQuery("SELECT DISTINCT term FROM dict_entries UNION SELECT DISTINCT reading FROM dict_entries WHERE reading != term", null)
        while (cursor.moveToNext()) {
            set.add(cursor.getString(0))
        }
        cursor.close()
        return set
    }

    fun getEntryCount(): Int {
        val db = db ?: return 0
        val cursor = db.rawQuery("SELECT COUNT(*) FROM dict_entries", null)
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    fun getFrequencyCount(): Int {
        val db = db ?: return 0
        val cursor = db.rawQuery("SELECT COUNT(*) FROM frequencies", null)
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return count
    }

    // ── Import methods (called by DictionaryDownloader) ──────────────

    fun getWritableDb(): SQLiteDatabase? {
        openDb()
        ensureExtraTables()
        return db
    }

    fun markInstalled(id: String, name: String, type: String, entryCount: Int) {
        val db = db ?: return
        val cv = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("type", type)
            put("entry_count", entryCount)
            put("installed_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("installed_dicts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getInstalledDicts(): Set<String> {
        val db = db ?: return emptySet()
        val cursor = db.rawQuery("SELECT id FROM installed_dicts", null)
        val ids = mutableSetOf<String>()
        while (cursor.moveToNext()) ids.add(cursor.getString(0))
        cursor.close()
        return ids
    }

    fun deleteDictionary(dictId: String) {
        val db = db ?: return
        db.beginTransaction()
        try {
            db.delete("dict_entries", "source = ?", arrayOf(dictId))
            db.delete("frequencies", "source = ?", arrayOf(dictId))
            db.delete("pitch_accents", "source = ?", arrayOf(dictId))
            db.delete("kanji_entries", "source = ?", arrayOf(dictId))
            db.delete("installed_dicts", "id = ?", arrayOf(dictId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.d(TAG, "Deleted dictionary: $dictId")
    }
}
