package com.janusplus

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class QuadBatch(private val maxQuads: Int = 4096) {

    // 4 vertices per quad, 8 floats per vertex (x,y, u,v, r,g,b,a)
    private val floatsPerVertex = 8
    private val verticesPerQuad = 4
    private val indicesPerQuad = 6

    private val vertexData = FloatArray(maxQuads * verticesPerQuad * floatsPerVertex)
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val indexBuffer: ShortBuffer
    private var vao = 0
    private var vbo = 0
    private var ebo = 0
    var quadCount = 0; private set
    private var cachedData: FloatArray? = null
    private var cachedCount = 0

    init {
        val indices = ShortArray(maxQuads * indicesPerQuad)
        for (i in 0 until maxQuads) {
            val v = (i * 4).toShort()
            val idx = i * 6
            indices[idx] = v
            indices[idx + 1] = (v + 1).toShort()
            indices[idx + 2] = (v + 2).toShort()
            indices[idx + 3] = (v + 2).toShort()
            indices[idx + 4] = (v + 3).toShort()
            indices[idx + 5] = v
        }
        indexBuffer = ByteBuffer
            .allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices)
        indexBuffer.position(0)
    }

    fun initGL() {
        val bufs = IntArray(3)

        GLES30.glGenVertexArrays(1, bufs, 0)
        vao = bufs[0]
        GLES30.glGenBuffers(2, bufs, 1)
        vbo = bufs[1]
        ebo = bufs[2]

        GLES30.glBindVertexArray(vao)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexData.size * 4, null, GLES30.GL_DYNAMIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo)
        indexBuffer.position(0)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.capacity() * 2, indexBuffer, GLES30.GL_STATIC_DRAW)

        val stride = floatsPerVertex * 4
        // aPos (location=0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        // aUV (location=1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(1)
        // aColor (location=2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 16)
        GLES30.glEnableVertexAttribArray(2)

        GLES30.glBindVertexArray(0)
    }

    fun begin() {
        quadCount = 0
    }

    fun addBaked(data: FloatArray, count: Int, x: Float, y: Float,
                 r: Float, g: Float, b: Float, a: Float) {
        if (quadCount + count > maxQuads) return
        val dstOff = quadCount * verticesPerQuad * floatsPerVertex
        System.arraycopy(data, 0, vertexData, dstOff, count * 32)
        // Apply position offset and color
        for (i in 0 until count * 4) {
            val base = dstOff + i * 8
            vertexData[base]     += x    // x position
            vertexData[base + 1] += y    // y position
            vertexData[base + 4]  = r    // color
            vertexData[base + 5]  = g
            vertexData[base + 6]  = b
            vertexData[base + 7]  = a
        }
        quadCount += count
    }

    fun snapshot(): FloatArray {
        val size = quadCount * verticesPerQuad * floatsPerVertex
        return vertexData.copyOfRange(0, size).also { cachedData = it; cachedCount = quadCount }
    }

    fun replay(data: FloatArray, count: Int) {
        System.arraycopy(data, 0, vertexData, 0, data.size)
        quadCount = count
    }

    fun addQuad(
        x: Float, y: Float, w: Float, h: Float,
        u0: Float = 0f, v0: Float = 0f, u1: Float = 1f, v1: Float = 1f,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f
    ) {
        if (quadCount >= maxQuads) return
        val off = quadCount * verticesPerQuad * floatsPerVertex
        // Top-left
        vertexData[off]    = x;   vertexData[off+1]  = y
        vertexData[off+2]  = u0;  vertexData[off+3]  = v0
        vertexData[off+4]  = r;   vertexData[off+5]  = g
        vertexData[off+6]  = b;   vertexData[off+7]  = a
        // Top-right
        vertexData[off+8]  = x+w; vertexData[off+9]  = y
        vertexData[off+10] = u1;  vertexData[off+11] = v0
        vertexData[off+12] = r;   vertexData[off+13] = g
        vertexData[off+14] = b;   vertexData[off+15] = a
        // Bottom-right
        vertexData[off+16] = x+w; vertexData[off+17] = y+h
        vertexData[off+18] = u1;  vertexData[off+19] = v1
        vertexData[off+20] = r;   vertexData[off+21] = g
        vertexData[off+22] = b;   vertexData[off+23] = a
        // Bottom-left
        vertexData[off+24] = x;   vertexData[off+25] = y+h
        vertexData[off+26] = u0;  vertexData[off+27] = v1
        vertexData[off+28] = r;   vertexData[off+29] = g
        vertexData[off+30] = b;   vertexData[off+31] = a
        quadCount++
    }

    fun addGradientQuad(
        x: Float, y: Float, w: Float, h: Float,
        tlColor: FloatArray, trColor: FloatArray,
        brColor: FloatArray, blColor: FloatArray,
        u: Float = 0f, v: Float = 0f
    ) {
        if (quadCount >= maxQuads) return
        val off = quadCount * verticesPerQuad * floatsPerVertex
        fun putVertex(base: Int, vx: Float, vy: Float, c: FloatArray) {
            vertexData[base]   = vx; vertexData[base+1] = vy
            vertexData[base+2] = u;  vertexData[base+3] = v
            vertexData[base+4] = c[0]; vertexData[base+5] = c[1]
            vertexData[base+6] = c[2]; vertexData[base+7] = c[3]
        }
        putVertex(off,      x,   y,   tlColor)
        putVertex(off + 8,  x+w, y,   trColor)
        putVertex(off + 16, x+w, y+h, brColor)
        putVertex(off + 24, x,   y+h, blColor)
        quadCount++
    }

    fun flush() {
        if (quadCount == 0) return
        GLES30.glBindVertexArray(vao)
        vertexBuffer.clear()
        vertexBuffer.put(vertexData, 0, quadCount * verticesPerQuad * floatsPerVertex)
        vertexBuffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, quadCount * verticesPerQuad * floatsPerVertex * 4, vertexBuffer)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, quadCount * indicesPerQuad, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }
}
