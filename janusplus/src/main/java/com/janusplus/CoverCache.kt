package com.janusplus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class CoverCache(private val texSize: Int = 4096) {

    data class SlotUV(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    var textureId = 0; private set

    private val slotW = 500
    private val slotH = 750
    private val cols = texSize / slotW
    private val rows = texSize / slotH
    private val maxSlots = cols * rows

    private val slotMap = HashMap<String, Int>()
    private val slotKeys = arrayOfNulls<String>(maxSlots)
    private val accessOrder = IntArray(maxSlots) { 0 }
    private var accessTick = 0
    private val downloading = HashSet<String>()

    data class PendingUpload(val key: String, val bitmap: Bitmap)
    private val uploadQueue = ConcurrentLinkedQueue<PendingUpload>()
    private val executor = Executors.newFixedThreadPool(2)

    var cacheDir: java.io.File? = null
        set(value) {
            field = value
            if (value != null) {
                cachedClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .cache(okhttp3.Cache(java.io.File(value, "covers"), 50L * 1024 * 1024))
                    .build()
            }
        }
    private var cachedClient: okhttp3.OkHttpClient? = null
    private val defaultClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val client get() = cachedClient ?: defaultClient

    fun initGL() {
        slotMap.clear()
        for (i in slotKeys.indices) slotKeys[i] = null
        synchronized(downloading) { downloading.clear() }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            texSize, texSize, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    fun bind() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
    }

    fun uploadFromBitmap(key: String, bmp: Bitmap) {
        if (slotMap.containsKey(key)) { bmp.recycle(); return }
        uploadQueue.add(PendingUpload(key, fitToSlot(bmp)))
    }

    fun request(key: String, url: String) {
        if (url.isEmpty() || slotMap.containsKey(key)) return
        synchronized(downloading) {
            if (!downloading.add(key)) return
        }
        executor.submit {
            try {
                val req = okhttp3.Request.Builder().url(url).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            val fitted = fitToSlot(bmp)
                            uploadQueue.add(PendingUpload(key, fitted))
                        }
                    }
                }
                resp.close()
            } catch (_: Exception) {
                synchronized(downloading) { downloading.remove(key) }
            }
        }
    }

    private fun fitToSlot(src: Bitmap): Bitmap {
        if (src.width == slotW && src.height == slotH) return src
        val dst = Bitmap.createBitmap(slotW, slotH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val srcAspect = src.width.toFloat() / src.height
        val dstAspect = slotW.toFloat() / slotH
        val srcRect = if (srcAspect > dstAspect) {
            val visW = (src.height * dstAspect).toInt()
            val off = (src.width - visW) / 2
            Rect(off, 0, off + visW, src.height)
        } else {
            val visH = (src.width / dstAspect).toInt()
            val off = (src.height - visH) / 2
            Rect(0, off, src.width, off + visH)
        }
        canvas.drawBitmap(src, srcRect, Rect(0, 0, slotW, slotH), null)
        src.recycle()
        return dst
    }

    fun processPending(): Boolean {
        val pending = uploadQueue.poll() ?: return false
        val slot = allocSlot(pending.key)
        val col = slot % cols
        val row = slot / cols
        val x = col * slotW
        val y = row * slotH

        val buf = ByteBuffer.allocateDirect(slotW * slotH * 4).order(ByteOrder.nativeOrder())
        pending.bitmap.copyPixelsToBuffer(buf); buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, x, y, slotW, slotH,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        pending.bitmap.recycle()

        slotMap[pending.key] = slot
        slotKeys[slot] = pending.key
        accessOrder[slot] = ++accessTick
        return true
    }

    fun getUV(key: String): SlotUV? {
        val slot = slotMap[key] ?: return null
        accessOrder[slot] = ++accessTick
        val col = slot % cols
        val row = slot / cols
        val ts = texSize.toFloat()
        return SlotUV(
            (col * slotW) / ts,
            (row * slotH) / ts,
            ((col + 1) * slotW) / ts,
            ((row + 1) * slotH) / ts,
        )
    }

    private fun allocSlot(key: String): Int {
        for (i in 0 until maxSlots) {
            if (slotKeys[i] == null) return i
        }
        var lruSlot = 0
        var lruTick = Int.MAX_VALUE
        for (i in 0 until maxSlots) {
            if (accessOrder[i] < lruTick) { lruTick = accessOrder[i]; lruSlot = i }
        }
        val evictKey = slotKeys[lruSlot]
        if (evictKey != null) slotMap.remove(evictKey)
        return lruSlot
    }

    fun invalidateAll() {
        slotMap.clear()
        for (i in slotKeys.indices) slotKeys[i] = null
        synchronized(downloading) { downloading.clear() }
        while (uploadQueue.poll()?.also { it.bitmap.recycle() } != null) {}
    }

    fun uploadFullImage(bmp: Bitmap): SlotUV {
        val w = bmp.width.coerceAtMost(texSize)
        val h = bmp.height.coerceAtMost(texSize)
        val src = if (bmp.width > w || bmp.height > h)
            Bitmap.createScaledBitmap(bmp, w, h, true).also { bmp.recycle() } else bmp
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        if (src.config != Bitmap.Config.ARGB_8888) {
            val conv = src.copy(Bitmap.Config.ARGB_8888, false)
            src.recycle()
            conv.copyPixelsToBuffer(buf)
            conv.recycle()
        } else {
            src.copyPixelsToBuffer(buf)
            src.recycle()
        }
        buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        return SlotUV(0f, 0f, w.toFloat() / texSize, h.toFloat() / texSize)
    }
}
