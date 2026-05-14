package com.videoplayer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object JitendexDict {
    private const val TAG = "JitendexDict"
    private const val ASSET_NAME = "jitendex.bin"
    private const val FNV_OFFSET = 0x811c9dc5.toInt()
    private const val FNV_PRIME = 0x01000193

    private var buf: ByteBuffer? = null
    private var tableSize = 0
    private var mask = 0
    private var dataOffset = 0

    data class Entry(
        val term: String,
        val reading: String,
        val tagBits: Long,
        val jlpt: Int,
        val freqBccwj: Int,
        val freqJpdb: Int,
        val freqInnocent: Int,
        val freqAnime: Int,
        val meanings: List<String>,
        val examples: List<Pair<String, String>>,
        val pitchAccent: String,
        val nameType: String,
    ) {
        fun isName(): Boolean = nameType.isNotEmpty()
        fun jlptLabel(): String = when (jlpt) {
            5 -> "N5"; 4 -> "N4"; 3 -> "N3"; 2 -> "N2"; 1 -> "N1"; else -> ""
        }

        fun hasTag(bit: Int): Boolean = tagBits and (1L shl bit) != 0L

        fun tagLabels(): List<String> {
            val labels = mutableListOf<String>()
            for ((bit, name) in TAG_NAMES) {
                if (tagBits and (1L shl bit) != 0L) labels.add(name)
            }
            return labels
        }

        fun bestFreq(): Int {
            val candidates = listOf(freqAnime, freqInnocent, freqBccwj, freqJpdb)
            return candidates.filter { it > 0 }.minOrNull() ?: 0
        }
    }

    fun init(context: Context) {
        if (buf != null) return
        try {
            val file = File(context.filesDir, ASSET_NAME)
            if (!file.exists() || file.length() < 16) {
                context.assets.open(ASSET_NAME).use { input ->
                    file.outputStream().use { output -> input.copyTo(output, 65536) }
                }
                Log.d(TAG, "Extracted ${file.length() / 1024 / 1024}MB")
            }
            val raf = RandomAccessFile(file, "r")
            buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(4).also { buf!!.position(0); buf!!.get(it) }
            if (String(magic) != "JDC4") { Log.e(TAG, "Bad magic"); buf = null; return }
            tableSize = buf!!.getInt(4)
            mask = buf!!.getInt(8)
            dataOffset = buf!!.getInt(12)
            Log.d(TAG, "Ready: table=$tableSize, mask=0x${mask.toString(16)}, data=$dataOffset")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
        }
    }

    fun lookup(term: String): Entry? {
        val b = buf ?: return null
        val termBytes = term.toByteArray(Charsets.UTF_8)
        var h = FNV_OFFSET
        for (byte in termBytes) {
            h = h xor (byte.toInt() and 0xFF)
            h = (h.toLong() * FNV_PRIME and 0xFFFFFFFFL).toInt()
        }
        var slot = h and mask
        while (true) {
            val off = b.getInt(16 + slot * 4)
            if (off == 0) return null
            val pos = dataOffset + off
            if (matchTerm(b, pos, termBytes)) {
                return readEntry(b, pos)
            }
            slot = (slot + 1) and mask
        }
    }

    fun hasEntry(term: String): Boolean {
        val b = buf ?: return false
        val termBytes = term.toByteArray(Charsets.UTF_8)
        var h = FNV_OFFSET
        for (byte in termBytes) {
            h = h xor (byte.toInt() and 0xFF)
            h = (h.toLong() * FNV_PRIME and 0xFFFFFFFFL).toInt()
        }
        var slot = h and mask
        while (true) {
            val off = b.getInt(16 + slot * 4)
            if (off == 0) return false
            if (matchTerm(b, dataOffset + off, termBytes)) return true
            slot = (slot + 1) and mask
        }
    }

    private fun matchTerm(b: ByteBuffer, pos: Int, termBytes: ByteArray): Boolean {
        for (i in termBytes.indices) {
            if (b.get(pos + i) != termBytes[i]) return false
        }
        return b.get(pos + termBytes.size) == 0.toByte()
    }

    private fun readString(b: ByteBuffer, start: Int): Pair<String, Int> {
        var end = start
        while (b.get(end) != 0.toByte()) end++
        val bytes = ByteArray(end - start)
        for (i in bytes.indices) bytes[i] = b.get(start + i)
        return String(bytes, Charsets.UTF_8) to (end + 1)
    }

    private fun readEntry(b: ByteBuffer, pos: Int): Entry {
        val (term, p1) = readString(b, pos)
        val (reading, p2) = readString(b, p1)
        var p = p2
        val tagBits = b.getLong(p); p += 8
        val jlpt = b.get(p).toInt() and 0xFF; p += 1
        val fb = b.getShort(p).toInt() and 0xFFFF; p += 2
        val fj = b.getShort(p).toInt() and 0xFFFF; p += 2
        val fi = b.getShort(p).toInt() and 0xFFFF; p += 2
        val fa = b.getShort(p).toInt() and 0xFFFF; p += 2

        val mc = b.get(p).toInt() and 0xFF; p += 1
        val meanings = mutableListOf<String>()
        for (i in 0 until mc) {
            val (m, np) = readString(b, p); p = np
            meanings.add(m)
        }

        val ec = b.get(p).toInt() and 0xFF; p += 1
        val examples = mutableListOf<Pair<String, String>>()
        for (i in 0 until ec) {
            val (ja, np1) = readString(b, p); p = np1
            val (en, np2) = readString(b, p); p = np2
            examples.add(ja to en)
        }

        val (pitch, pAfterPitch) = readString(b, p)
        val (nameType, _) = readString(b, pAfterPitch)

        return Entry(term, reading, tagBits, jlpt, fb, fj, fi, fa, meanings, examples, pitch, nameType)
    }

    private val TAG_NAMES = listOf(
        0 to "noun", 1 to "ichidan", 2 to "godan-r", 3 to "godan-s", 4 to "godan-k",
        5 to "godan-g", 6 to "godan-m", 7 to "godan-b", 8 to "godan-t", 9 to "godan-n",
        10 to "godan-u", 11 to "suru", 12 to "suru-i", 13 to "suru-s", 14 to "kuru",
        15 to "intrans.", 16 to "trans.", 17 to "i-adj", 18 to "na-adj", 19 to "no-adj",
        20 to "pn-adj", 21 to "adverb", 22 to "adv-to", 23 to "expression",
        24 to "interj.", 25 to "conj.", 26 to "particle", 27 to "prefix", 28 to "suffix",
        56 to "★",
    )
}
