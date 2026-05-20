package com.janusplus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.api.AddContentApi
import java.io.File

class AnkiDroidClient(val context: Context) {

    private val api = AddContentApi(context)

    companion object {
        private const val TAG = "AnkiDroid"
        const val DEFAULT_DECK = "Janus Mining"
        const val MODEL_NAME = "Janus+ Immersion"
        val FIELDS = arrayOf(
            "Expression", "Reading", "Meaning", "Sentence", "SentenceFurigana",
            "SentenceNoWord", "Screenshot", "Audio", "Source", "JLPT"
        )
        private val CARD_NAMES = arrayOf("Recognition")
        private val QFMT = arrayOf("""
<div class="image-wrapper">{{Screenshot}}</div>

<div class="reading">{{furigana:Reading}}</div>
<div class="meaning">{{Meaning}}</div>

<hr>

<div id="sentence" class="sentence"></div>
<div id="source_sentence">{{furigana:SentenceFurigana}}</div>

<div class="source">{{Source}}</div>
<div class="jlpt">{{JLPT}}</div>

<script>
var raw = document.getElementById("source_sentence").innerHTML;
var clean = raw.replace(/<br\s*\/?>/gi, '');
document.getElementById("sentence").innerHTML = clean;
</script>
        """.trimIndent())
        private val AFMT = arrayOf("""
{{FrontSide}}

{{Audio}}

<style>
.image-wrapper, .meaning {
  visibility: visible;
}
</style>
        """.trimIndent())
        private val CSS = """
@font-face { font-family: "KleeOne"; src: url("_KleeOne-Regular.ttf"); }

.card {
  font-size: 20px;
  text-align: center;
}

#source_sentence { display: none; }

.card, div, html {
  margin: 0;
  padding: 0;
  font-family: "KleeOne", "Noto Sans JP", serif !important;
}

.image-wrapper {
  visibility: hidden;
  margin: 0;
  padding: 0;
}

.image-wrapper img {
  max-height: 29vh;
}

hr {
  display: block !important;
  border: none !important;
  border-top: 1px solid #888 !important;
  margin: 5px auto !important;
  width: 95% !important;
}

.reading {
  font-size: 70px;
  min-height: 80px;
  display: block;
}

.meaning {
  visibility: hidden;
  font-size: 24px;
  min-height: 40px;
  display: block;
}

.sentence {
  font-size: 32px;
  text-align: left;
}

.source {
  font-size: 13px;
  color: #888;
  margin-top: 10px;
}

.jlpt {
  font-size: 14px;
  color: #bb86fc;
}

rt {
  visibility: visible;
}
        """.trimIndent()
        const val PERMISSION_REQUEST_CODE = 9001
    }

    fun isAvailable(): Boolean = AddContentApi.getAnkiDroidPackageName(context) != null

    fun listModels(): Map<Long, String> {
        val all = api.getModelList(1) ?: return emptyMap()
        for ((id, name) in all) {
            Log.d(TAG, "model: id=$id name=\"$name\"")
            // Get field names for this model
            try {
                val fields = api.getFieldList(id)
                if (fields != null) {
                    Log.d(TAG, "  fields: ${fields.joinToString(", ")}")
                }
            } catch (_: Exception) {}
        }
        return all
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, AddContentApi.READ_WRITE_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED

    fun requestPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity, arrayOf(AddContentApi.READ_WRITE_PERMISSION), PERMISSION_REQUEST_CODE
        )
    }

    fun getOrCreateDeck(deckName: String = DEFAULT_DECK): Long? {
        val decks = api.deckList ?: return null
        for ((id, name) in decks) { if (name == deckName) return id }
        return api.addNewDeck(deckName)
    }

    fun getDeckId(deckName: String): Long? {
        val decks = api.deckList ?: return null
        return decks.entries.firstOrNull { it.value == deckName }?.key
    }

    private fun getOrCreateModel(): Long? {
        val models = api.getModelList(1) ?: return null
        for ((id, name) in models) { if (name == MODEL_NAME) return id }
        return api.addNewCustomModel(MODEL_NAME, FIELDS, CARD_NAMES, QFMT, AFMT, CSS, null, null)
    }

    data class CardInfo(
        val expression: String,
        val reading: String,
        val meaning: String,
        val sentence: String,
        val sentenceFurigana: String = "",
        val source: String,
        val jlpt: String = "",
        val screenshotFile: File? = null,
        val audioFile: File? = null,
    )

    fun addCard(card: CardInfo, deckName: String = DEFAULT_DECK): Result {
        if (!isAvailable()) return Result.NotInstalled
        if (!hasPermission()) return Result.NoPermission

        val deckId = getOrCreateDeck(deckName) ?: return Result.Error("Failed to create deck")
        val modelId = getOrCreateModel() ?: return Result.Error("Failed to create model")

        var screenshotRef = ""
        var audioRef = ""

        card.screenshotFile?.let { file ->
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.grantUriPermission("com.ichi2.anki", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                screenshotRef = api.addMediaFromUri(uri, "janus_${System.currentTimeMillis()}.jpg", "image") ?: ""
            } catch (e: Exception) {
                Log.w(TAG, "Screenshot attach failed: ${e.message}")
            }
        }

        card.audioFile?.let { file ->
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.grantUriPermission("com.ichi2.anki", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                audioRef = api.addMediaFromUri(uri, "janus_${System.currentTimeMillis()}.mp3", "audio") ?: ""
            } catch (e: Exception) {
                Log.w(TAG, "Audio attach failed: ${e.message}")
            }
        }

        // Janus+ Immersion fields:
        // Expression, Reading, Meaning, Sentence, SentenceFurigana,
        // SentenceNoWord, Screenshot, Audio, Source, JLPT
        val sentenceNoWord = card.sentence.replace(card.expression, "___")
        val fields = arrayOf(
            card.expression,
            card.reading,
            card.meaning,
            card.sentence,
            card.sentenceFurigana.ifEmpty { card.sentence },
            sentenceNoWord,
            screenshotRef,
            audioRef,
            card.source,
            card.jlpt,
        )

        val tags = mutableSetOf("janus")
        if (card.jlpt.isNotEmpty()) tags.add(card.jlpt.lowercase())

        val dupes = api.findDuplicateNotes(modelId, card.expression)
        if (dupes != null && dupes.isNotEmpty()) return Result.Duplicate

        val noteId = api.addNote(modelId, deckId, fields, tags)
        return if (noteId != null && noteId > 0) {
            Log.d(TAG, "Card added: ${card.expression} → noteId=$noteId")
            Result.Success(noteId)
        } else {
            Result.Error("addNote returned null")
        }
    }

    sealed class Result {
        data class Success(val noteId: Long) : Result()
        data class Error(val message: String) : Result()
        object Duplicate : Result()
        object NotInstalled : Result()
        object NoPermission : Result()
    }
}
