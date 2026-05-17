package com.videoplayer

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {

    companion object {
        private const val PREFS = "videoplayer_prefs"

        val FONTS = linkedMapOf(
            "noto_sans" to FontInfo("Noto Sans JP", "fonts/NotoSansJP-Regular.ttf"),
            "noto_serif" to FontInfo("Noto Serif JP", "fonts/NotoSerifJP-Regular.ttf"),
            "kosugi_maru" to FontInfo("Kosugi Maru", "fonts/KosugiMaru-Regular.ttf"),
            "shippori_mincho" to FontInfo("Shippori Mincho", "fonts/ShipporiMincho-Regular.ttf")
        )

        val FONT_SIZES = listOf(16, 18, 20, 22, 24, 28, 32, 38, 44)

        // Default AnkiConnect field names (matching common Yomitan/mining note types)
        val ANKI_FIELD_KEYS = listOf(
            "field_word" to "Word",
            "field_word_furigana" to "Word (furigana)",
            "field_reading" to "Reading",
            "field_meaning" to "Meaning",
            "field_sentence" to "Sentence",
            "field_sentence_furigana" to "Sentence (furigana)",
            "field_screenshot" to "Screenshot",
            "field_audio" to "Audio",
            "field_frequency" to "Frequency",
            "field_source" to "Source"
        )
    }

    data class FontInfo(val displayName: String, val assetPath: String)

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Subtitle settings ────────────────────────────────────────────

    var fontKey: String
        get() = prefs.getString("subtitle_font", "noto_sans") ?: "noto_sans"
        set(value) = prefs.edit().putString("subtitle_font", value).apply()

    val fontInfo: FontInfo get() = FONTS[fontKey] ?: FONTS["noto_sans"]!!

    var fontSize: Int
        get() = prefs.getInt("subtitle_font_size", 20)
        set(value) = prefs.edit().putInt("subtitle_font_size", value).apply()

    var fontBold: Boolean
        get() = prefs.getBoolean("subtitle_font_bold", false)
        set(value) = prefs.edit().putBoolean("subtitle_font_bold", value).apply()

    // ── Theme ─────────────────────────────────────────────────────────

    var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", true)
        set(value) = prefs.edit().putBoolean("dark_mode", value).apply()

    // ── Playback settings ────────────────────────────────────────────

    var hardwareDecoding: Boolean
        get() = prefs.getBoolean("hardware_decoding", false)
        set(value) = prefs.edit().putBoolean("hardware_decoding", value).apply()

    // ── Anki settings ────────────────────────────────────────────────

    var ankiEnabled: Boolean
        get() = prefs.getBoolean("anki_enabled", false)
        set(value) = prefs.edit().putBoolean("anki_enabled", value).apply()

    var ankiConnectUrl: String
        get() = prefs.getString("anki_connect_url", "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765"
        set(value) = prefs.edit().putString("anki_connect_url", value).apply()

    var ankiDeck: String
        get() = prefs.getString("anki_deck", "Default") ?: "Default"
        set(value) = prefs.edit().putString("anki_deck", value).apply()

    var ankiNoteType: String
        get() = prefs.getString("anki_note_type", "Basic") ?: "Basic"
        set(value) = prefs.edit().putString("anki_note_type", value).apply()

    var ankiTags: String
        get() = prefs.getString("anki_tags", "janus") ?: "janus"
        set(value) = prefs.edit().putString("anki_tags", value).apply()

    // ── Anki field mappings ──────────────────────────────────────────
    // Each field mapping maps our data to a field name in the Anki note type.
    // Empty string means "don't include this field".

    fun getFieldMapping(key: String): String {
        val default = when (key) {
            "field_word" -> "Word"
            "field_reading" -> "Reading"
            "field_meaning" -> "Meaning"
            "field_sentence" -> "Sentence"
            "field_screenshot" -> "Screenshot"
            "field_audio" -> "Audio"
            else -> ""
        }
        return prefs.getString("anki_$key", default) ?: default
    }

    fun setFieldMapping(key: String, value: String) {
        prefs.edit().putString("anki_$key", value).apply()
    }

    // Audio padding for card creation (seconds)
    var audioPadBefore: Float
        get() = prefs.getFloat("audio_pad_before", 0.5f)
        set(value) = prefs.edit().putFloat("audio_pad_before", value).apply()

    var audioPadAfter: Float
        get() = prefs.getFloat("audio_pad_after", 0.5f)
        set(value) = prefs.edit().putFloat("audio_pad_after", value).apply()
}
