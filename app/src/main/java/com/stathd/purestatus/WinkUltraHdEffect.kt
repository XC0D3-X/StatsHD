package com.stathd.purestatus

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * WinkUltraHdEffect — Real-Time GPU Video Quality Restoration Shader
 *
 * Implements a 3-stage pixel restoration pipeline inspired by Wink (Meitu) Ultra-HD:
 * 1. Edge-Preserving Bilateral De-Blocking & Mosquito Noise Removal (smooths flat compression blocks
 *    on grass/sky/skin without softening borders).
 * 2. Contrast-Adaptive Sharpening (AMD FidelityFX CAS-inspired) with Anti-Halo Neighborhood Clamping
 *    to reconstruct crisp text, hero HP bars, outlines, and fine details.
 * 3. Shadow-Safe Vibrance & Midtone Clarity Pop (enhances color separation without crushing blacks).
 */
@OptIn(UnstableApi::class)
class WinkUltraHdEffect(
    private val sharpnessStrength: Float = 0.78f,
    private val denoiseStrength: Float = 0.45f,
    private val vibranceBoost: Float = 0.10f
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return WinkUltraHdShaderProgram(
            useHdr = useHdr,
            sharpness = sharpnessStrength,
            denoise = denoiseStrength,
            vibrance = vibranceBoost
        )
    }
}

@OptIn(UnstableApi::class)
private class WinkUltraHdShaderProgram(
    useHdr: Boolean,
    private val sharpness: Float,
    private val denoise: Float,
    private val vibrance: Float
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram
    private var texelWidth: Float = 1.0f / 1280.0f
    private var texelHeight: Float = 1.0f / 720.0f

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            val identity = GlUtil.create4x4IdentityMatrix()
            glProgram.setFloatsUniform("uTransformationMatrix", identity)
            glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
            glProgram.setFloatUniform("uSharpness", sharpness)
            glProgram.setFloatUniform("uDenoise", denoise)
            glProgram.setFloatUniform("uVibrance", vibrance)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(texelWidth, texelHeight))
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val safeW = if (inputWidth > 0) inputWidth else 1280
        val safeH = if (inputHeight > 0) inputHeight else 720
        texelWidth = 1.0f / safeW.toFloat()
        texelHeight = 1.0f / safeH.toFloat()
        return Size(safeW, safeH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(texelWidth, texelHeight))
            glProgram.setFloatUniform("uSharpness", sharpness)
            glProgram.setFloatUniform("uDenoise", denoise)
            glProgram.setFloatUniform("uVibrance", vibrance)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = uTransformationMatrix * aFramePosition;
              vec4 texturePosition = vec4(
                  aFramePosition.x * 0.5 + 0.5,
                  aFramePosition.y * 0.5 + 0.5,
                  0.0,
                  1.0
              );
              vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uSharpness;
            uniform float uDenoise;
            uniform float uVibrance;
            varying vec2 vTexSamplingCoord;

            float getLuma(vec3 rgb) {
              return dot(rgb, vec3(0.299, 0.587, 0.114));
            }

            void main() {
              vec2 uv = vTexSamplingCoord;
              vec2 dx = vec2(uTexelSize.x, 0.0);
              vec2 dy = vec2(0.0, uTexelSize.y);

              // 9-tap neighborhood fetch
              vec4 c4 = texture2D(uTexSampler, uv);
              vec3 c  = c4.rgb;
              vec3 n  = texture2D(uTexSampler, uv - dy).rgb;
              vec3 s  = texture2D(uTexSampler, uv + dy).rgb;
              vec3 w  = texture2D(uTexSampler, uv - dx).rgb;
              vec3 e  = texture2D(uTexSampler, uv + dx).rgb;
              vec3 nw = texture2D(uTexSampler, uv - dx - dy).rgb;
              vec3 ne = texture2D(uTexSampler, uv + dx - dy).rgb;
              vec3 sw = texture2D(uTexSampler, uv - dx + dy).rgb;
              vec3 se = texture2D(uTexSampler, uv + dx + dy).rgb;

              // STAGE 1: Edge-Preserving Bilateral De-Blocking & Mosquito Noise Filter
              // Smooths flat compression blocks (small color diffs < 0.055) while preserving sharp edges
              float invSigmaSq = 360.0; // Threshold tuned for H.264 macroblock smoothing
              float wn  = exp(-dot(n - c, n - c) * invSigmaSq);
              float ws  = exp(-dot(s - c, s - c) * invSigmaSq);
              float ww  = exp(-dot(w - c, w - c) * invSigmaSq);
              float we  = exp(-dot(e - c, e - c) * invSigmaSq);
              float wnw = exp(-dot(nw - c, nw - c) * invSigmaSq) * 0.7;
              float wne = exp(-dot(ne - c, ne - c) * invSigmaSq) * 0.7;
              float wsw = exp(-dot(sw - c, sw - c) * invSigmaSq) * 0.7;
              float wse = exp(-dot(se - c, se - c) * invSigmaSq) * 0.7;

              float wSum = 1.0 + wn + ws + ww + we + wnw + wne + wsw + wse;
              vec3 bilateralClean = (c + n * wn + s * ws + w * ww + e * we +
                                     nw * wnw + ne * wne + sw * wsw + se * wse) / wSum;
              vec3 baseColor = mix(c, bilateralClean, clamp(uDenoise, 0.0, 1.0));

              // STAGE 2: Contrast-Adaptive Sharpening (CAS) with Anti-Halo Neighborhood Clamp
              vec3 minCross = min(c, min(min(n, s), min(w, e)));
              vec3 maxCross = max(c, max(max(n, s), max(w, e)));
              vec3 minDiag  = min(minCross, min(min(nw, ne), min(sw, se)));
              vec3 maxDiag  = max(maxCross, max(max(nw, ne), max(sw, se)));
              vec3 localMin = 0.5 * (minCross + minDiag);
              vec3 localMax = 0.5 * (maxCross + maxDiag);

              // High-frequency detail extraction from cross + diagonal blur
              vec3 neighborhoodBlur = (n + s + w + e) * 0.15 + (nw + ne + sw + se) * 0.10;
              vec3 detail = baseColor - neighborhoodBlur;

              // Edge mask: amplify true edges, suppress flat area noise amplification
              float edgeGradient = abs(getLuma(detail));
              float edgeMask = smoothstep(0.008, 0.065, edgeGradient);

              vec3 sharpened = baseColor + detail * (uSharpness * 1.85 * edgeMask);
              // Anti-halo clamp prevents ugly white/black ringing around text and HP bars
              sharpened = clamp(sharpened, localMin - 0.025, localMax + 0.025);
              sharpened = clamp(sharpened, 0.0, 1.0);

              // STAGE 3: Shadow-Safe Vibrance Pop (Zero Crushed Blacks)
              float luma = getLuma(sharpened);
              float maxChan = max(sharpened.r, max(sharpened.g, sharpened.b));
              float minChan = min(sharpened.r, min(sharpened.g, sharpened.b));
              float sat = maxChan - minChan;
              // Boost less-saturated colors smoothly without clipping bright/saturated colors
              vec3 vibrant = mix(vec3(luma), sharpened, 1.0 + (uVibrance * (1.0 - sat)));

              gl_FragColor = vec4(clamp(vibrant, 0.0, 1.0), c4.a);
            }
        """
    }
}
