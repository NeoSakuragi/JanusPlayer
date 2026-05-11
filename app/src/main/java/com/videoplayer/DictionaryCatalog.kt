package com.videoplayer

enum class DictType {
    TERMS,
    FREQUENCY,
    PITCH_ACCENT,
    KANJI
}

data class DictCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val type: DictType,
    val sizeMb: Float
)

object DictionaryCatalog {
    val entries = listOf(
        DictCatalogEntry(
            id = "jmdict_en",
            name = "JMdict English",
            description = "Main Japanese-English dictionary (~200k entries)",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip",
            type = DictType.TERMS,
            sizeMb = 15f
        ),
        DictCatalogEntry(
            id = "jitendex",
            name = "Jitendex",
            description = "Improved J-E dictionary with better definitions and examples",
            url = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
            type = DictType.TERMS,
            sizeMb = 38f
        ),
        DictCatalogEntry(
            id = "kanjium_pitch",
            name = "Kanjium Pitch Accent",
            description = "Pitch accent patterns for ~116k words",
            url = "https://github.com/toasted-nutbread/yomichan-pitch-accent-dictionary/releases/download/1.0.0/kanjium_pitch_accents.zip",
            type = DictType.PITCH_ACCENT,
            sizeMb = 1.0f
        ),
        DictCatalogEntry(
            id = "kanjidic_en",
            name = "KANJIDIC",
            description = "Kanji readings, meanings, stroke count, JLPT level",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip",
            type = DictType.KANJI,
            sizeMb = 0.7f
        ),
        DictCatalogEntry(
            id = "bccwj_freq",
            name = "BCCWJ Frequency",
            description = "Word frequency from Japan's 100M-word written corpus",
            url = "https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/BCCWJ_SUW_LUW_combined.zip",
            type = DictType.FREQUENCY,
            sizeMb = 18f
        ),
        DictCatalogEntry(
            id = "jmdict_forms",
            name = "JMdict Forms",
            description = "Maps conjugated/inflected forms to dictionary entries",
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_forms.zip",
            type = DictType.TERMS,
            sizeMb = 6f
        ),
        DictCatalogEntry(
            id = "jpdb_v2_freq",
            name = "JPDB v2.2 Frequency",
            description = "Improved frequency data + kana vs kanji usage",
            url = "https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/JPDB_v2.2_Frequency_Kana_2024-10-13.zip",
            type = DictType.FREQUENCY,
            sizeMb = 6f
        )
    )
}
