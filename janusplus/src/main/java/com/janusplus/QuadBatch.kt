package com.janusplus

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class QuadBatch(private val maxQuads: Int = 4096) {

    // 4 vertices per quad, 9 floats per vertex (x,y, u,v,layer, r,g,b,a)
    private val floatsPerVertex = 9
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
        // aPos (location=0): 2 floats
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        // aUVL (location=1): 3 floats (u, v, layer)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 8)
        GLES30.glEnableVertexAttribArray(1)
        // aColor (location=2): 4 floats
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 20)
        GLES30.glEnableVertexAttribArray(2)

        GLES30.glBindVertexArray(0)
    }

    fun begin() { quadCount = 0 }

    fun addQuad(
        x: Float, y: Float, w: Float, h: Float,
        u0: Float = 0f, v0: Float = 0f, u1: Float = 1f, v1: Float = 1f,
        r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
        layer: Float = 0f
    ) {
        if (quadCount >= maxQuads) return
        val o = quadCount * verticesPerQuad * floatsPerVertex
        fun v(base: Int, vx: Float, vy: Float, vu: Float, vv: Float) {
            vertexData[base] = vx; vertexData[base+1] = vy
            vertexData[base+2] = vu; vertexData[base+3] = vv; vertexData[base+4] = layer
            vertexData[base+5] = r; vertexData[base+6] = g; vertexData[base+7] = b; vertexData[base+8] = a
        }
        v(o,      x,   y,   u0, v0)
        v(o + 9,  x+w, y,   u1, v0)
        v(o + 18, x+w, y+h, u1, v1)
        v(o + 27, x,   y+h, u0, v1)
        quadCount++
    }

    fun addGradientQuad(
        x: Float, y: Float, w: Float, h: Float,
        tlColor: FloatArray, trColor: FloatArray,
        brColor: FloatArray, blColor: FloatArray,
        u: Float = 0f, v: Float = 0f, layer: Float = 0f
    ) {
        if (quadCount >= maxQuads) return
        val o = quadCount * verticesPerQuad * floatsPerVertex
        fun vt(base: Int, vx: Float, vy: Float, c: FloatArray) {
            vertexData[base] = vx; vertexData[base+1] = vy
            vertexData[base+2] = u; vertexData[base+3] = v; vertexData[base+4] = layer
            vertexData[base+5] = c[0]; vertexData[base+6] = c[1]; vertexData[base+7] = c[2]; vertexData[base+8] = c[3]
        }
        vt(o,      x,   y,   tlColor)
        vt(o + 9,  x+w, y,   trColor)
        vt(o + 18, x+w, y+h, brColor)
        vt(o + 27, x,   y+h, blColor)
        quadCount++
    }

    fun addBaked(data: FloatArray, count: Int, x: Float, y: Float,
                 r: Float, g: Float, b: Float, a: Float) {
        if (quadCount + count > maxQuads) return
        val dstOff = quadCount * verticesPerQuad * floatsPerVertex
        System.arraycopy(data, 0, vertexData, dstOff, count * verticesPerQuad * floatsPerVertex)
        for (i in 0 until count * 4) {
            val base = dstOff + i * floatsPerVertex
            vertexData[base]     += x
            vertexData[base + 1] += y
            vertexData[base + 5]  = r
            vertexData[base + 6]  = g
            vertexData[base + 7]  = b
            vertexData[base + 8]  = a
        }
        quadCount += count
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

    fun snapshot(): FloatArray {
        val size = quadCount * verticesPerQuad * floatsPerVertex
        return vertexData.copyOfRange(0, size)
    }

    fun replay(data: FloatArray, count: Int) {
        System.arraycopy(data, 0, vertexData, 0, data.size)
        quadCount = count
    }
}
