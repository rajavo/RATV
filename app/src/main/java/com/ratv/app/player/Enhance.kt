package com.ratv.app.player

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.roundToInt

/*
 * Smart picture enhancement for weak channels, done by the TV's own graphics chip: nothing extra
 * comes from the internet. A picture under 720 lines is enlarged with an edge-aware filter and then
 * sharpened, the two halves of AMD FidelityFX Super Resolution 1.0 (EASU and RCAS; MIT licence,
 * Copyright (c) 2021 Advanced Micro Devices, Inc.). The sharpening holds back where the picture is
 * noisy, so compression blocks of a weak stream are not made worse.
 *
 * Only pictures under [ENHANCE_BELOW_HEIGHT] lines take this path: HD and 4K channels go straight
 * to the screen as before, where the TV's own picture processing works on them.
 */

/** Pictures with fewer lines than this are enhanced. */
const val ENHANCE_BELOW_HEIGHT = 720

/** The height a picture of [height] lines is enlarged to: twice, at most the screen's 1080 lines. */
fun enhancedHeight(height: Int): Int = (height * minOf(2f, 1080f / height)).roundToInt() and 1.inv()

/** What the picture size of an item is remembered under. */
fun enhanceKey(item: Playable): String = "${item.kind}:${item.id}"

/** Pixels drawn per second when enhancing a picture of [height] lines at [fps] (16:9). */
fun enhanceRate(height: Int, fps: Float): Long {
    val h = enhancedHeight(height).toLong()
    return h * h * 16L / 9L * fps.coerceIn(1f, 120f).toLong()
}

private val SD_HINT = Regex("""(?i)(^|[^a-z0-9])(sd|lq|low|360p?|480p?|540p?|576p?)([^a-z0-9]|$)""")
private val HD_HINT = Regex("""(?i)(^|[^a-z0-9])(hd|fhd|uhd|4k|8k|hevc|1080p?|720p?|2160p?)([^a-z0-9]|$)""")

/** A height guessed from a channel name that says it is low quality ("SD", "LOW", "480"...). */
fun nameHintHeight(name: String?): Int? {
    if (name.isNullOrBlank() || !SD_HINT.containsMatchIn(name) || HD_HINT.containsMatchIn(name)) return null
    return 576
}

/** Remembers the picture size of channels and films, so enhancement starts with the picture next time. */
object EnhanceMemory {
    class Info(val height: Int, val fps: Float)

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences("enhance_memory", Context.MODE_PRIVATE)

    fun get(context: Context, key: String): Info? {
        val v = sp(context).getString(key, null) ?: return null
        val parts = v.split(',')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return Info(h, parts.getOrNull(1)?.toFloatOrNull() ?: 25f)
    }

    fun put(context: Context, key: String, height: Int, fps: Float) {
        val prev = get(context, key)
        val f = if (fps > 0f) fps else prev?.fps ?: 25f
        if (prev != null && prev.height == height && kotlin.math.abs(prev.fps - f) < 1f) return
        sp(context).edit().putString(key, "$height,${"%.2f".format(java.util.Locale.US, f)}").apply()
    }
}

/**
 * The enhancement as a Media3 video effect. [stretchAspect] is the shape of the screen area when
 * the viewer chose to stretch the picture (fill, or a fixed 16:9 / 4:3 shape); null keeps its shape.
 */
@UnstableApi
class TvEnhanceEffect(private val stretchAspect: Float?) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        TvEnhanceProgram(useHdr, stretchAspect)
}

@UnstableApi
private class TvEnhanceProgram(private val hdr: Boolean, private val stretchAspect: Float?) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ hdr, /* texturePoolCapacity= */ 2) {

    private val easu = program(EASU_FRAGMENT)
    private val rcas = program(RCAS_FRAGMENT)
    private val copy = program(COPY_FRAGMENT)
    private var inW = 0
    private var inH = 0
    private var outW = 0
    private var outH = 0
    private var enhance = false
    private var midTex = 0
    private var midFbo = 0
    private val boundFbo = IntArray(1)

    private fun program(fragment: String): GlProgram = try {
        GlProgram(VERTEX, fragment).also {
            it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inW = inputWidth
        inH = inputHeight
        enhance = !hdr && inputHeight in 1 until ENHANCE_BELOW_HEIGHT
        outH = if (enhance) enhancedHeight(inputHeight) else inputHeight
        outW = when {
            stretchAspect != null -> (outH * stretchAspect).roundToInt()
            enhance -> (inputWidth.toFloat() * outH / inputHeight).roundToInt()
            else -> inputWidth
        }.coerceAtLeast(2) and 1.inv()
        releaseMiddle()
        if (enhance) {
            try {
                midTex = GlUtil.createTexture(outW, outH, /* useHighPrecisionColorComponents= */ false)
                midFbo = GlUtil.createFboForTexture(midTex)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
        Log.i("RATVEnhance", "picture ${inputWidth}x$inputHeight -> ${outW}x$outH" + if (enhance) " (enhanced)" else " (as it is)")
        return Size(outW, outH)
    }

    // Every pixel is drawn, so the target needs no clearing first.
    override fun shouldClearTextureBuffer(): Boolean = false

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (enhance) {
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFbo, 0)
                val target = boundFbo[0]
                // 1. Enlarge, following the edges.
                GlUtil.focusFramebufferUsingCurrentContext(midFbo, outW, outH)
                easu.use()
                easu.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                easu.setFloatsUniform("uInputSize", floatArrayOf(inW.toFloat(), inH.toFloat()))
                easu.setFloatsUniform("uOutputSize", floatArrayOf(outW.toFloat(), outH.toFloat()))
                easu.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
                // 2. Sharpen, holding back on noise.
                GlUtil.focusFramebufferUsingCurrentContext(target, outW, outH)
                rcas.use()
                rcas.setSamplerTexIdUniform("uTexSampler", midTex, /* texUnitIndex= */ 0)
                rcas.setFloatsUniform("uSize", floatArrayOf(outW.toFloat(), outH.toFloat()))
                rcas.setFloatUniform("uSharpness", SHARPNESS)
                rcas.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            } else {
                copy.use()
                copy.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                copy.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun releaseMiddle() {
        try {
            if (midFbo != 0) GlUtil.deleteFbo(midFbo)
            if (midTex != 0) GlUtil.deleteTexture(midTex)
        } catch (e: GlUtil.GlException) {
            Log.w("RATVEnhance", "could not free the middle picture", e)
        }
        midFbo = 0
        midTex = 0
    }

    override fun release() {
        super.release()
        releaseMiddle()
        try {
            easu.delete()
            rcas.delete()
            copy.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        /** Sharpening strength: 2^-0.25 of the full strength (AMD's suggested range is 0 to 0.5 stops). */
        val SHARPNESS = Math.pow(2.0, -0.25).toFloat()

        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        const val PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

        const val COPY_FRAGMENT = PRECISION + """
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() {
  gl_FragColor = vec4(texture2D(uTexSampler, vTexSamplingCoord).rgb, 1.0);
}
"""

        // FSR 1.0 EASU, one output pixel from the 12 input pixels around it:
        //    b c
        //  e f g h
        //  i j k l
        //    n o
        const val EASU_FRAGMENT = PRECISION + """
uniform sampler2D uTexSampler;
uniform vec2 uInputSize;
uniform vec2 uOutputSize;

vec3 tapAt(vec2 p) {
  return texture2D(uTexSampler, (p + 0.5) / uInputSize).rgb;
}

float luma2(vec3 c) {
  return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void accumulateDir(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
  float dc = lD - lC;
  float cb = lC - lB;
  float lenX = 1.0 / max(max(abs(dc), abs(cb)), 1.0 / 32768.0);
  float dirX = lD - lB;
  dir.x += dirX * w;
  lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
  len += lenX * lenX * w;
  float ec = lE - lC;
  float ca = lC - lA;
  float lenY = 1.0 / max(max(abs(ec), abs(ca)), 1.0 / 32768.0);
  float dirY = lE - lA;
  dir.y += dirY * w;
  lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
  len += lenY * lenY * w;
}

void accumulateTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {
  vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x);
  v *= len2;
  float d2 = min(v.x * v.x + v.y * v.y, clp);
  float wB = 0.4 * d2 - 1.0;
  float wA = lob * d2 - 1.0;
  wB *= wB;
  wA *= wA;
  wB = 1.5625 * wB - 0.5625;
  float w = wB * wA;
  aC += c * w;
  aW += w;
}

void main() {
  vec2 pp = gl_FragCoord.xy * (uInputSize / uOutputSize) - 0.5;
  vec2 fp = floor(pp);
  pp -= fp;
  vec3 b = tapAt(fp + vec2(0.0, -1.0));
  vec3 c = tapAt(fp + vec2(1.0, -1.0));
  vec3 e = tapAt(fp + vec2(-1.0, 0.0));
  vec3 f = tapAt(fp);
  vec3 g = tapAt(fp + vec2(1.0, 0.0));
  vec3 h = tapAt(fp + vec2(2.0, 0.0));
  vec3 i = tapAt(fp + vec2(-1.0, 1.0));
  vec3 j = tapAt(fp + vec2(0.0, 1.0));
  vec3 k = tapAt(fp + vec2(1.0, 1.0));
  vec3 l = tapAt(fp + vec2(2.0, 1.0));
  vec3 n = tapAt(fp + vec2(0.0, 2.0));
  vec3 o = tapAt(fp + vec2(1.0, 2.0));
  float bL = luma2(b);
  float cL = luma2(c);
  float eL = luma2(e);
  float fL = luma2(f);
  float gL = luma2(g);
  float hL = luma2(h);
  float iL = luma2(i);
  float jL = luma2(j);
  float kL = luma2(k);
  float lL = luma2(l);
  float nL = luma2(n);
  float oL = luma2(o);
  vec2 dir = vec2(0.0);
  float len = 0.0;
  accumulateDir(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
  accumulateDir(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
  accumulateDir(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
  accumulateDir(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
  float dirR = dir.x * dir.x + dir.y * dir.y;
  bool zro = dirR < 1.0 / 32768.0;
  dirR = zro ? 1.0 : inversesqrt(dirR);
  dir.x = zro ? 1.0 : dir.x;
  dir *= dirR;
  len = len * 0.5;
  len *= len;
  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(max(abs(dir.x), abs(dir.y)), 1.0 / 32768.0);
  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
  float clp = 1.0 / lob;
  vec3 min4 = min(min(f, g), min(j, k));
  vec3 max4 = max(max(f, g), max(j, k));
  vec3 aC = vec3(0.0);
  float aW = 0.0;
  accumulateTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
  accumulateTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
  accumulateTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
  accumulateTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
  accumulateTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
  accumulateTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
  accumulateTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
  accumulateTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
  accumulateTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
  accumulateTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
  accumulateTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
  accumulateTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
  vec3 col = aC / max(aW, 1.0 / 32768.0);
  gl_FragColor = vec4(min(max4, max(min4, col)), 1.0);
}
"""

        // FSR 1.0 RCAS with its noise removal, from the four neighbours of each pixel:
        //    b
        //  d e f
        //    h
        const val RCAS_FRAGMENT = PRECISION + """
uniform sampler2D uTexSampler;
uniform vec2 uSize;
uniform float uSharpness;

vec3 at(vec2 p) {
  return texture2D(uTexSampler, p / uSize).rgb;
}

float luma2(vec3 c) {
  return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void main() {
  vec2 p = gl_FragCoord.xy;
  vec3 b = at(p + vec2(0.0, -1.0));
  vec3 d = at(p + vec2(-1.0, 0.0));
  vec3 e = at(p);
  vec3 f = at(p + vec2(1.0, 0.0));
  vec3 h = at(p + vec2(0.0, 1.0));
  float bL = luma2(b);
  float dL = luma2(d);
  float eL = luma2(e);
  float fL = luma2(f);
  float hL = luma2(h);
  float mxL = max(max(max(bL, dL), max(eL, fL)), hL);
  float mnL = min(min(min(bL, dL), min(eL, fL)), hL);
  float nz = 0.25 * (bL + dL + fL + hL) - eL;
  nz = clamp(abs(nz) / max(mxL - mnL, 1.0 / 4096.0), 0.0, 1.0);
  nz = -0.5 * nz + 1.0;
  vec3 mn4 = min(min(b, d), min(f, h));
  vec3 mx4 = max(max(b, d), max(f, h));
  vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0 / 4096.0));
  vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1.0 / 4096.0));
  vec3 lobeC = max(-hitMin, hitMax);
  float lobe = max(-0.1875, min(max(lobeC.r, max(lobeC.g, lobeC.b)), 0.0)) * uSharpness;
  lobe *= nz;
  vec3 col = (lobe * (b + d + h + f) + e) / (4.0 * lobe + 1.0);
  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
    }
}
