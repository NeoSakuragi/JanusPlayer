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

class AnkiDroidClient(private val context: Context) {

    private val api = AddContentApi(context)

    companion object {
        private const val TAG = "AnkiDroid"
        private const val DECK_NAME = "Janus Mining"
        private const val MODEL_NAME = "Janus Japanese"
        private val FIELDS = arrayOf(
            "Expression", "Reading", "Meaning", "Sentence",
            "Screenshot", "Audio", "Source", "JLPT"
        )
        private val CARD_NAMES = arrayOf("Recognition")
        private val QFMT = arrayOf("""
            <div class="expression">{{Expression}}</div>
            <div class="sentence">{{Sentence}}</div>
            {{Screenshot}}
        """.trimIndent())
        private val AFMT = arrayOf("""
            {{FrontSide}}<hr id=answer>
            <div class="reading">{{furigana:Reading}}</div>
            <div class="meaning">{{Meaning}}</div>
            <div class="jlpt">{{JLPT}}</div>
            {{Audio}}
        """.trimIndent())
        private val CSS = """
            .card { font-family: "Noto Sans JP", "Yu Gothic", sans-serif; text-align: center; background: #1a1a2e; color: #eee; }
            .expression { font-size: 48px; margin: 20px 0; }
            .reading { font-size: 32px; color: #aaa; }
            .meaning { font-size: 22px; margin-top: 12px; }
            .sentence { font-size: 18px; color: #ccc; margin-top: 16px; }
            .jlpt { font-size: 14px; color: #bb86fc; margin-top: 8px; }
            img { max-width: 100%; border-radius: 8px; margin-top: 12px; }
        """.trimIndent()
        const val PERMISSION_REQUEST_CODE = 9001
    }

    fun isAvailable(): Boolean = AddContentApi.getAnkiDroidPackageName(context) != null

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, AddContentApi.READ_WRITE_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED

    fun requestPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity, arrayOf(AddContentApi.READ_WRITE_PERMISSION), PERMISSION_REQUEST_CODE
        )
    }

    private fun getOrCreateDeck(): Long? {
        val decks = api.deckList ?: return null
        for ((id, name) in decks) { if (name == DECK_NAME) return id }
        return api.addNewDeck(DECK_NAME)
    }

    private fun getOrCreateModel(): Long? {
        val models = api.getModelList(FIELDS.size) ?: return null
        for ((id, name) in models) { if (name == MODEL_NAME) return id }
        return api.addNewCustomModel(MODEL_NAME, FIELDS, CARD_NAMES, QFMT, AFMT, CSS, null, null)
    }

    data class CardInfo(
        val expression: String,
        val reading: String,
        val meaning: String,
        val sentence: String,
        val source: String,
        val jlpt: String = "",
        val screenshotFile: File? = null,
        val audioFile: File? = null,
    )

    fun addCard(card: CardInfo): Result {
        if (!isAvailable()) return Result.NotInstalled
        if (!hasPermission()) return Result.NoPermission

        val deckId = getOrCreateDeck() ?: return Result.Error("Failed to create deck")
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

        val fields = arrayOf(
            card.expression,
            card.reading,
            card.meaning,
            card.sentence,
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
