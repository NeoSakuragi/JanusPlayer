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
        private const val DECK_NAME = "Immersion"
        private const val MODEL_NAME = "Immersion Sentences"
        // Fields: Front, Back, Add Reverse, Sentence, Sentence No Word, Reading,
        //         Kanji, Screenshot, Audio, tags, chatgpt, qwen-translate, qwen-nuance
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

    private fun getOrCreateDeck(): Long? {
        val decks = api.deckList ?: return null
        for ((id, name) in decks) { if (name == DECK_NAME) return id }
        return api.addNewDeck(DECK_NAME)
    }

    private fun getOrCreateModel(): Long? {
        val models = api.getModelList(1) ?: return null
        for ((id, name) in models) { if (name == MODEL_NAME) return id }
        return null
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

        // Immersion Sentences fields:
        // Front, Back, Add Reverse, Sentence, Sentence No Word,
        // Reading, Kanji, Screenshot, Audio, tags, chatgpt, qwen-translate, qwen-nuance
        val sentenceNoWord = card.sentence.replace(card.expression, "___")
        val fields = arrayOf(
            card.expression,                       // Front
            card.meaning,                          // Back
            "",                                    // Add Reverse
            card.sentence,                         // Sentence
            sentenceNoWord,                        // Sentence No Word
            card.reading,                          // Reading
            card.expression,                       // Kanji
            screenshotRef,                         // Screenshot
            audioRef,                              // Audio
            "janus ${card.jlpt}".trim(),          // tags
            "",                                    // chatgpt
            "",                                    // qwen-translate
            "",                                    // qwen-nuance
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
