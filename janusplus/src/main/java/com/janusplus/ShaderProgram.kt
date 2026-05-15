package com.janusplus

import android.opengl.GLES30
import android.util.Log

class ShaderProgram {

    var programId = 0; private set
    var uProj = -1; private set
    var uTex = -1; private set

    fun compile() {
        val vert = loadShader(GLES30.GL_VERTEX_SHADER, VERT_SRC)
        val frag = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAG_SRC)
        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vert)
        GLES30.glAttachShader(programId, frag)
        GLES30.glLinkProgram(programId)
        val status = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Link failed: ${GLES30.glGetProgramInfoLog(programId)}")
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        GLES30.glDeleteShader(vert)
        GLES30.glDeleteShader(frag)
        uProj = GLES30.glGetUniformLocation(programId, "uProj")
        uTex = GLES30.glGetUniformLocation(programId, "uTex")
    }

    fun use() = GLES30.glUseProgram(programId)

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "ShaderProgram"

        private const val VERT_SRC = """#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec3 aUVL;
layout(location=2) in vec4 aColor;
uniform mat4 uProj;
out vec3 vUVL;
out vec4 vColor;
void main() {
    gl_Position = uProj * vec4(aPos, 0.0, 1.0);
    vUVL = aUVL;
    vColor = aColor;
}"""

        private const val FRAG_SRC = """#version 300 es
precision mediump float;
in vec3 vUVL;
in vec4 vColor;
uniform mediump sampler2DArray uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUVL) * vColor;
}"""
    }
}
