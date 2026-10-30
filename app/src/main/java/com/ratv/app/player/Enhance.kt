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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/*
 * Smart picture enhancement for weak channels, done by the TV's own graphics chip: nothing extra
 * comes from the internet. It comes in two strengths, from AMD FidelityFX Super Resolution 1.0
 * (EASU and RCAS; MIT licence, Copyright (c) 2021 Advanced Micro Devices, Inc.):
 *
 *  - full: the picture is enlarged with an edge-aware filter (EASU), then sharpened (RCAS);
 *  - light: the picture is only sharpened at its own size (RCAS) and the screen enlarges it. About a
 *    quarter of the work, for weaker graphics chips (and for any chip that falls behind on full).
 *
 * The sharpening holds back where the picture is noisy, so the compression blocks of a weak stream
 * are not made worse. All the maths stays clear of tiny numbers, so chips that only compute with
 * 16-bit numbers (older Mali 400 series) never turn it into a black picture; and the effect checks
 * now and then that what it draws is not black while the channel is not, and stops if it is.
 *
 * Pictures up to 1080 lines take this path: weak ones are enlarged and sharpened, 720p ones are
 * enlarged to 1080 on strong chips, and 1080p ones are sharpened at their own size. After the
 * sharpening the colours are livened a little (saturation and a gentle contrast curve). 4K
 * channels go straight to the screen, where the TV's own picture processing works on them.
 */

/** Pictures with fewer lines than this are enhanced (everything up to 1080p). */
const val ENHANCE_BELOW_HEIGHT = 1440

/** Pictures with this many lines or more are HD: sharpened at their own size, and given up first if the TV falls behind. */
const val ENHANCE_HD_LINES = 720

/** Pictures under this many lines may be enlarged (full strength); 1080p is only sharpened. */
private const val ENLARGE_BELOW_HEIGHT = 1080

/** Enhancement strengths: off, light (sharpen only), full (enlarge and sharpen). */
const val ENHANCE_OFF = 0
const val ENHANCE_LIGHT = 1
const val ENHANCE_FULL = 2

/** The height a picture of [height] lines is enlarged to by the full enhancement: twice, at most 1080. */
fun enhancedHeight(height: Int): Int = (height * minOf(2f, 1080f / height)).roundToInt() and 1.inv()

/** What the picture size of an item is remembered under. */
fun enhanceKey(item: Playable): String = "${item.kind}:${item.id}"

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

/** What the enhancement is doing right now (written on the graphics thread, read by the player). */
object EnhanceStatus {
    /** The strength in use: [ENHANCE_LIGHT], [ENHANCE_FULL], or [ENHANCE_OFF] for a picture left as it is. */
    @Volatile var strength = ENHANCE_OFF
    /** The graphics chip, as it names itself ("Mali-G52", "Mali-470"...), once known. */
    @Volatile var gpu: String? = null
    /** False for chips that only compute with 16-bit numbers in pictures (no high precision). */
    @Volatile var highPrecision = true
    /** The picture size going in and coming out. */
    @Volatile var inSize = ""
    @Volatile var outSize = ""

    // Chips too weak for the full strength: Mali 400 series and older Mali-T, the smallest Mali-G,
    // older PowerVR and Adreno 300/400.
    private val WEAK = Regex("""(?i)mali-4\d\d|mali-t[678]\d\d|mali-g31|powervr sgx|ge8[123]\d\d|adreno \(tm\) [34]\d\d""")

    /** The strongest strength this chip is given. */
    fun chipStrength(): Int {
        if (com.ratv.app.data.debugProperty("debug.ratv.enhancegpu") == "weak") return ENHANCE_LIGHT
        val name = gpu.orEmpty()
        return if (!highPrecision || WEAK.containsMatchIn(name)) ENHANCE_LIGHT else ENHANCE_FULL
    }
}

/**
 * The enhancement as a Media3 video effect, at most [maxStrength]. [stretchAspect] is the shape of
 * the screen area when the viewer chose to stretch the picture (fill, or a fixed 16:9 / 4:3 shape);
 * null keeps its shape.
 */
@UnstableApi
class TvEnhanceEffect(private val stretchAspect: Float?, private val maxStrength: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        // Until the first picture is sized, a failure is put down to the strength asked for.
        EnhanceStatus.strength = maxStrength
        return TvEnhanceProgram(useHdr, stretchAspect, maxStrength)
    }
}

@UnstableApi
private class TvEnhanceProgram(
    private val hdr: Boolean,
    private val stretchAspect: Float?,
    private val maxStrength: Int
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ hdr, /* texturePoolCapacity= */ 2) {

    private var easu: GlProgram? = null
    private var rcas: GlProgram? = null
    private var copy: GlProgram? = null
    private var inW = 0
    private var inH = 0
    private var outW = 0
    private var outH = 0
    private var strength = ENHANCE_OFF
    private var midTex = 0
    private var midFbo = 0
    private var frames = 0
    private val boundFbo = IntArray(1)

    private fun program(vertex: String, fragment: String): GlProgram = try {
        GlProgram(vertex, fragment).also {
            it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private fun probeChip() {
        if (EnhanceStatus.gpu != null) return
        EnhanceStatus.gpu = GLES20.glGetString(GLES20.GL_RENDERER)?.trim().orEmpty().ifBlank { "?" }
        val range = IntArray(2)
        val precision = IntArray(1)
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0)
        EnhanceStatus.highPrecision = precision[0] > 0
        Log.i("RATVEnhance", "graphics chip: ${EnhanceStatus.gpu}, high precision: ${EnhanceStatus.highPrecision}")
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        probeChip()
        inW = inputWidth
        inH = inputHeight
        strength = when {
            hdr || inputHeight !in 1 until ENHANCE_BELOW_HEIGHT -> ENHANCE_OFF
            inputHeight >= ENLARGE_BELOW_HEIGHT -> minOf(maxStrength, ENHANCE_LIGHT) // 1080p: sharpened at its own size
            else -> minOf(maxStrength, EnhanceStatus.chipStrength())
        }
        EnhanceStatus.strength = strength
        outH = if (strength == ENHANCE_FULL) enhancedHeight(inputHeight) else inputHeight
        outW = when {
            stretchAspect != null -> (outH * stretchAspect).roundToInt()
            else -> (inputWidth.toFloat() * outH / inputHeight).roundToInt()
        }.coerceAtLeast(2) and 1.inv()
        releaseMiddle()
        try {
            when (strength) {
                ENHANCE_FULL -> {
                    if (easu == null) easu = program(VERTEX, EASU_FRAGMENT)
                    if (rcas == null) rcas = program(RCAS_VERTEX, RCAS_FRAGMENT)
                    midTex = GlUtil.createTexture(outW, outH, /* useHighPrecisionColorComponents= */ false)
                    midFbo = GlUtil.createFboForTexture(midTex)
                }
                ENHANCE_LIGHT -> if (rcas == null) rcas = program(RCAS_VERTEX, RCAS_FRAGMENT)
                else -> if (copy == null) copy = program(VERTEX, COPY_FRAGMENT)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        frames = 0
        EnhanceStatus.inSize = "${inputWidth}x$inputHeight"
        EnhanceStatus.outSize = "${outW}x$outH"
        val what = when (strength) {
            ENHANCE_FULL -> "enhanced"
            ENHANCE_LIGHT -> "light"
            else -> "as it is"
        }
        Log.i("RATVEnhance", "picture ${inputWidth}x$inputHeight -> ${outW}x$outH ($what)")
        return Size(outW, outH)
    }

    // Every pixel is drawn, so the target needs no clearing first.
    override fun shouldClearTextureBuffer(): Boolean = false

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, boundFbo, 0)
            val target = boundFbo[0]
            when (strength) {
                ENHANCE_FULL -> {
                    // 1. Enlarge, following the edges.
                    val e = easu!!
                    GlUtil.focusFramebufferUsingCurrentContext(midFbo, outW, outH)
                    e.use()
                    e.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                    e.setFloatsUniform("uInputSize", floatArrayOf(inW.toFloat(), inH.toFloat()))
                    e.setFloatsUniform("uOutputSize", floatArrayOf(outW.toFloat(), outH.toFloat()))
                    e.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
                    // 2. Sharpen the enlarged picture, holding back on noise.
                    GlUtil.focusFramebufferUsingCurrentContext(target, outW, outH)
                    sharpen(midTex, outW, outH)
                }
                ENHANCE_LIGHT -> sharpen(inputTexId, inW, inH) // at its own size; the screen enlarges it
                else -> {
                    val c = copy!!
                    c.use()
                    c.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                    c.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
                }
            }
            frames++
            if (strength != ENHANCE_OFF && (frames == 15 || frames == 75 || frames % 750 == 0)) {
                checkNotBlack(inputTexId, target, presentationTimeUs)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun sharpen(texId: Int, texW: Int, texH: Int) {
        val r = rcas!!
        r.use()
        r.setSamplerTexIdUniform("uTexSampler", texId, /* texUnitIndex= */ 0)
        r.setFloatsUniform("uTexel", floatArrayOf(1f / texW, 1f / texH))
        r.setFloatUniform("uSharpness", SHARPNESS)
        r.setFloatUniform("uVivid", VIVID)
        r.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
    }

    /**
     * Compares the middle of the picture going in and coming out: a black result from a picture that
     * is not black means this chip cannot run the enhancement, which then stops (the player plays
     * the picture the usual way).
     */
    private fun checkNotBlack(inputTexId: Int, target: Int, presentationTimeUs: Long) {
        val out = centreLevel(outW, outH)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, inputTexId, 0)
        val input = if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) centreLevel(inW, inH) else -1
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target)
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        if (input > 24 && out < 4) {
            Log.w("RATVEnhance", "the enhanced picture came out black (in $input, out $out)")
            throw VideoFrameProcessingException("the enhanced picture came out black", presentationTimeUs)
        }
    }

    /** The average brightness (0-255) of an 8x8 block in the middle of the bound picture. */
    private fun centreLevel(w: Int, h: Int): Int {
        val buf = ByteBuffer.allocateDirect(8 * 8 * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(maxOf(0, w / 2 - 4), maxOf(0, h / 2 - 4), 8, 8, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        var sum = 0
        for (i in 0 until 64) {
            sum += (buf.get(i * 4).toInt() and 0xFF) + (buf.get(i * 4 + 1).toInt() and 0xFF) + (buf.get(i * 4 + 2).toInt() and 0xFF)
        }
        return sum / (64 * 3)
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
            easu?.delete()
            rcas?.delete()
            copy?.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        /**
         * Sharpening strength: 120% of the 2^-0.25 used before (about RCAS's full strength). The lobe
         * stays far from -0.25, where the sharpening's division would break down.
         */
        val SHARPNESS = (1.2 * Math.pow(2.0, -0.25)).toFloat()

        /** How much the colours are livened after the sharpening: saturation +12%, a 10% contrast curve. */
        const val VIVID = 0.2f

        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        // The sharpening reads each pixel and its four neighbours at places worked out here, once per
        // corner, at full precision: chips that compute pictures with 16-bit numbers still read the
        // right pixels.
        const val RCAS_VERTEX = """
attribute vec4 aFramePosition;
uniform vec2 uTexel;
varying vec2 vC;
varying vec2 vB;
varying vec2 vD;
varying vec2 vF;
varying vec2 vH;
void main() {
  gl_Position = aFramePosition;
  vec2 c = aFramePosition.xy * 0.5 + 0.5;
  vC = c;
  vB = c + vec2(0.0, -uTexel.y);
  vD = c + vec2(-uTexel.x, 0.0);
  vF = c + vec2(uTexel.x, 0.0);
  vH = c + vec2(0.0, uTexel.y);
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
        // (Given only to chips with high precision; its limits still stay clear of tiny numbers.)
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
  float lenX = 1.0 / max(max(abs(dc), abs(cb)), 1.0 / 4096.0);
  float dirX = lD - lB;
  dir.x += dirX * w;
  lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
  len += lenX * lenX * w;
  float ec = lE - lC;
  float ca = lC - lA;
  float lenY = 1.0 / max(max(abs(ec), abs(ca)), 1.0 / 4096.0);
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
  bool zro = dirR < 1.0 / 4096.0;
  dirR = zro ? 1.0 : inversesqrt(dirR);
  dir.x = zro ? 1.0 : dir.x;
  dir *= dirR;
  len = clamp(len * 0.5, 0.0, 1.0);
  len *= len;
  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(max(abs(dir.x), abs(dir.y)), 1.0 / 4096.0);
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
  vec3 col = aC / max(aW, 1.0 / 4096.0);
  gl_FragColor = vec4(min(max4, max(min4, col)), 1.0);
}
"""

        // FSR 1.0 RCAS with its noise removal, from the four neighbours of each pixel:
        //    b
        //  d e f
        //    h
        // Every limit is 1/4096 or more, a number 16-bit maths still holds.
        const val RCAS_FRAGMENT = PRECISION + """
uniform sampler2D uTexSampler;
uniform float uSharpness;
uniform float uVivid;
varying vec2 vC;
varying vec2 vB;
varying vec2 vD;
varying vec2 vF;
varying vec2 vH;

float luma2(vec3 c) {
  return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void main() {
  vec3 b = texture2D(uTexSampler, vB).rgb;
  vec3 d = texture2D(uTexSampler, vD).rgb;
  vec3 e = texture2D(uTexSampler, vC).rgb;
  vec3 f = texture2D(uTexSampler, vF).rgb;
  vec3 h = texture2D(uTexSampler, vH).rgb;
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
  vec3 col = clamp((lobe * (b + d + h + f) + e) / (4.0 * lobe + 1.0), 0.0, 1.0);
  // Livelier colours: more saturation, and a gentle S curve for depth (blacks and whites kept).
  float y = dot(col, vec3(0.299, 0.587, 0.114));
  col = clamp(mix(vec3(y), col, 1.0 + 0.6 * uVivid), 0.0, 1.0);
  col = mix(col, col * col * (3.0 - 2.0 * col), 0.5 * uVivid);
  gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
