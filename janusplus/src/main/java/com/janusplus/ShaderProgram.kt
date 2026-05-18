package com.janusplus

import android.opengl.GLES30
import android.util.Log

class ShaderProgram {

    var programId = 0; private set
    var uProj = -1; private set
    var uTex = -1; private set
    var uTexEtc2 = -1; private set
    var uTexVideo = -1; private set
    var uSdfThreshold = -1; private set
    var uSdfSmoothing = -1; private set
    var uOutlineWidth = -1; private set
    var uOutlineColor = -1; private set
    var uShadowOffset = -1; private set
    var uShadowColor = -1; private set
    var uFontLayerMax = -1; private set

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
        uTexEtc2 = GLES30.glGetUniformLocation(programId, "uTexEtc2")
        uTexVideo = GLES30.glGetUniformLocation(programId, "uTexVideo")
        uSdfThreshold = GLES30.glGetUniformLocation(programId, "uSdfThreshold")
        uSdfSmoothing = GLES30.glGetUniformLocation(programId, "uSdfSmoothing")
        uOutlineWidth = GLES30.glGetUniformLocation(programId, "uOutlineWidth")
        uOutlineColor = GLES30.glGetUniformLocation(programId, "uOutlineColor")
        uShadowOffset = GLES30.glGetUniformLocation(programId, "uShadowOffset")
        uShadowColor = GLES30.glGetUniformLocation(programId, "uShadowColor")
        uFontLayerMax = GLES30.glGetUniformLocation(programId, "uFontLayerMax")
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

    var programIdExt = 0; private set
    var uProjExt = -1; private set
    var uTexExt = -1; private set
    var uTexMatExt = -1; private set

    fun compileExternal() {
        val vert = loadShader(GLES30.GL_VERTEX_SHADER, VERT_SRC_EXT)
        val frag = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAG_SRC_EXT)
        programIdExt = GLES30.glCreateProgram()
        GLES30.glAttachShader(programIdExt, vert)
        GLES30.glAttachShader(programIdExt, frag)
        GLES30.glLinkProgram(programIdExt)
        val status = IntArray(1)
        GLES30.glGetProgramiv(programIdExt, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Ext link failed: ${GLES30.glGetProgramInfoLog(programIdExt)}")
            GLES30.glDeleteProgram(programIdExt)
            programIdExt = 0
        }
        GLES30.glDeleteShader(vert)
        GLES30.glDeleteShader(frag)
        uProjExt = GLES30.glGetUniformLocation(programIdExt, "uProj")
        uTexExt = GLES30.glGetUniformLocation(programIdExt, "uTex")
        uTexMatExt = GLES30.glGetUniformLocation(programIdExt, "uTexMat")
    }

    fun useExternal() = GLES30.glUseProgram(programIdExt)

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
uniform mediump sampler2DArray uTexEtc2;
uniform mediump sampler2D uTexVideo;
uniform float uSdfThreshold;
uniform float uSdfSmoothing;
uniform float uOutlineWidth;
uniform vec4 uOutlineColor;
uniform vec2 uShadowOffset;
uniform vec4 uShadowColor;
uniform float uFontLayerMax;
out vec4 fragColor;
void main() {
    if (vUVL.z < 0.0) {
        fragColor = texture(uTexVideo, vUVL.xy) * vColor;
    } else if (vUVL.z >= 10.0) {
        fragColor = texture(uTexEtc2, vec3(vUVL.xy, vUVL.z - 10.0)) * vColor;
    } else if (vUVL.z < uFontLayerMax) {
        // SDF text rendering
        float dist = texture(uTex, vUVL).r;
        float fillAlpha = smoothstep(uSdfThreshold - uSdfSmoothing, uSdfThreshold + uSdfSmoothing, dist);
        vec4 fill = vec4(vColor.rgb, vColor.a * fillAlpha);

        if (uOutlineWidth > 0.0) {
            float outT = uSdfThreshold - uOutlineWidth;
            float outAlpha = smoothstep(outT - uSdfSmoothing, outT + uSdfSmoothing, dist);
            vec4 outline = vec4(uOutlineColor.rgb, uOutlineColor.a * outAlpha);
            fill = mix(outline, fill, fillAlpha);
        }

        if (uShadowColor.a > 0.0) {
            vec2 shadowUV = vUVL.xy - uShadowOffset;
            float shadowDist = texture(uTex, vec3(shadowUV, vUVL.z)).r;
            float shadowAlpha = smoothstep(uSdfThreshold - uSdfSmoothing, uSdfThreshold + uSdfSmoothing, shadowDist);
            vec4 shadow = vec4(uShadowColor.rgb, uShadowColor.a * shadowAlpha);
            fragColor = mix(shadow, fill, fill.a);
        } else {
            fragColor = fill;
        }
    } else {
        // Regular RGBA (covers, thumbnails)
        fragColor = texture(uTex, vUVL) * vColor;
    }
}"""
        // External OES shader for video frames
        private const val VERT_SRC_EXT = """#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec3 aUVL;
layout(location=2) in vec4 aColor;
uniform mat4 uProj;
uniform mat4 uTexMat;
out vec2 vUV;
out vec4 vColor;
void main() {
    gl_Position = uProj * vec4(aPos, 0.0, 1.0);
    vUV = (uTexMat * vec4(aUVL.xy, 0.0, 1.0)).xy;
    vColor = aColor;
}"""

        private const val FRAG_SRC_EXT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUV;
in vec4 vColor;
uniform samplerExternalOES uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUV) * vColor;
}"""
    }
}
