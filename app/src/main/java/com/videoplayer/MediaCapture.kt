package com.videoplayer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class MediaCapture(private val context: Context) {

    companion object {
        private const val TAG = "MediaCapture"
    }

    /**
     * Save a bitmap to a JPEG file in cache.
     */
    fun saveBitmap(bitmap: Bitmap): File? {
        return try {
            val file = File(context.cacheDir, "anki_screenshot_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "Screenshot saved: ${file.length()} bytes")
                file
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Save bitmap failed: ${e.message}")
            null
        }
    }

    fun getSubtitleTiming(): Pair<Double, Double>? {
        // TODO: implement with ExoPlayer subtitle cue timing
        return null
    }

    fun extractAudio(sourceUrl: String, startSec: Double, endSec: Double): File? {
        val outFile = File(context.cacheDir, "anki_audio_${System.currentTimeMillis()}.m4a")
        return try {
            val extractor = android.media.MediaExtractor()
            if (sourceUrl.startsWith("http")) {
                extractor.setDataSource(sourceUrl, mapOf<String, String>())
            } else {
                extractor.setDataSource(sourceUrl.removePrefix("file://"))
            }

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrackIndex = i; break }
            }
            if (audioTrackIndex < 0) { extractor.release(); return null }

            extractor.selectTrack(audioTrackIndex)
            val audioFormat = extractor.getTrackFormat(audioTrackIndex)
            val startUs = (startSec * 1_000_000).toLong()
            val endUs = (endSec * 1_000_000).toLong()
            extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val muxer = android.media.MediaMuxer(outFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                if (time >= startUs) {
                    info.offset = 0; info.size = size
                    info.presentationTimeUs = time - startUs
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrack, buffer, info)
                }
                extractor.advance()
            }
            muxer.stop(); muxer.release(); extractor.release()

            if (outFile.exists() && outFile.length() > 100) {
                Log.d(TAG, "Audio extracted: ${outFile.length()} bytes")
                outFile
            } else { Log.w(TAG, "Audio empty"); null }
        } catch (e: Exception) {
            Log.w(TAG, "Audio extraction failed: ${e.message}")
            null
        }
    }
}
