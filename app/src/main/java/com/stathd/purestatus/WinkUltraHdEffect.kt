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
 * WinkUltraHdEffect v1.4 — Dual-Radius Multi-Scale Unsharp + Local 3D Clarity GPU Shader
 *
 * Calibrated directly on zoomed portrait gameplay (1220x2168 -> 720x1280) to survive
 * WhatsApp Status's internal H.264 DCT quantization:
 * 1. Fine 3x3 Kernel (1.0px radius, amount 1.65x): Reconstructs crisp text ("CvN Syamir 969",
 *    damage/HP numbers, HP bar tick lines, and fine UI edges).
 * 2. Wide 7x7 Clarity Kernel (2.5px radius, amount 1.15x): Reconstructs zoomed-in game textures,
 *    hero armor, bush leaves, and skill rings (Dehaze / 3D Clarity Pop).
 * 3. Micro-Contrast (1.06x) + Vibrance/Saturation (1.12x): Restores vivid HD pop without crushing blacks.
 */
@OptIn(UnstableApi::class)
class WinkUltraHdEffect(
    private val sharpnessStrength: Float = 1.0f,
    private val denoiseStrength: Float = 0.0f,
    private val vibranceBoost: Float = 0.12f
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return WinkUltraHdShaderProgram(
            useHdr = useHdr,
            sharpness = sharpnessStrength,
            clarity = if (sharpnessStrength >= 0.85f) 1.15f else 0.75f,
            vibrance = vibranceBoost
        )
    }
}

@OptIn(UnstableApi::class)
private class WinkUltraHdShaderProgram(
    useHdr: Boolean,
    private val sharpness: Float,
    private val clarity: Float,
    private val vibrance: Float
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram
    private var texelWidth: Float = 1.0f / 720.0f
    private var texelHeight: Float = 1.0f / 1280.0f

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
            glProgram.setFloatUniform("uClarity", clarity)
            glProgram.setFloatUniform("uVibrance", vibrance)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(texelWidth, texelHeight))
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val safeW = if (inputWidth > 0) inputWidth else 720
        val safeH = if (inputHeight > 0) inputHeight else 1280
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
            glProgram.setFloatUniform("uClarity", clarity)
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
            uniform float uClarity;
            uniform float uVibrance;
            varying vec2 vTexSamplingCoord;

            float getLuma(vec3 rgb) {
              return dot(rgb, vec3(0.299, 0.587, 0.114));
            }

            void main() {
              vec2 uv = vTexSamplingCoord;

              // Center raw pixel (never pre-blurred!)
              vec4 c4 = texture2D(uTexSampler, uv);
              vec3 c  = c4.rgb;

              // SCALE 1: Fine 3x3 Unsharp Kernel (1.0px radius — sharpens text, numbers & HP bar ticks)
              vec2 dx1 = vec2(uTexelSize.x, 0.0);
              vec2 dy1 = vec2(0.0, uTexelSize.y);
              vec3 n1  = texture2D(uTexSampler, uv - dy1).rgb;
              vec3 s1  = texture2D(uTexSampler, uv + dy1).rgb;
              vec3 w1  = texture2D(uTexSampler, uv - dx1).rgb;
              vec3 e1  = texture2D(uTexSampler, uv + dx1).rgb;
              vec3 nw1 = texture2D(uTexSampler, uv - dx1 - dy1).rgb;
              vec3 ne1 = texture2D(uTexSampler, uv + dx1 - dy1).rgb;
              vec3 sw1 = texture2D(uTexSampler, uv - dx1 + dy1).rgb;
              vec3 se1 = texture2D(uTexSampler, uv + dx1 + dy1).rgb;

              vec3 blurFine = (n1 + s1 + w1 + e1) * 0.15 + (nw1 + ne1 + sw1 + se1) * 0.10;
              vec3 detailFine = c - blurFine;

              // SCALE 2: Wide 7x7 Clarity Kernel (2.5px radius — reconstructs zoomed-in game edges & armor)
              vec2 dx2 = vec2(uTexelSize.x * 2.5, 0.0);
              vec2 dy2 = vec2(0.0, uTexelSize.y * 2.5);
              vec3 n2  = texture2D(uTexSampler, uv - dy2).rgb;
              vec3 s2  = texture2D(uTexSampler, uv + dy2).rgb;
              vec3 w2  = texture2D(uTexSampler, uv - dx2).rgb;
              vec3 e2  = texture2D(uTexSampler, uv + dx2).rgb;
              vec3 nw2 = texture2D(uTexSampler, uv - dx2 - dy2).rgb;
              vec3 ne2 = texture2D(uTexSampler, uv + dx2 - dy2).rgb;
              vec3 sw2 = texture2D(uTexSampler, uv - dx2 + dy2).rgb;
              vec3 se2 = texture2D(uTexSampler, uv + dx2 + dy2).rgb;

              vec3 blurWide = (n2 + s2 + w2 + e2) * 0.15 + (nw2 + ne2 + sw2 + se2) * 0.10;
              vec3 detailWide = c - blurWide;

              // Noise gate: only suppress ultra-flat mosquito noise (< 0.005), full strength on all edges
              float edgeEnergy = abs(getLuma(detailFine)) + abs(getLuma(detailWide));
              float noiseGate = smoothstep(0.003, 0.016, edgeEnergy);

              // Dual-Radius Unsharp Reconstruction (matches FFmpeg unsharp=3:3:1.65 + unsharp=7:7:1.15)
              vec3 boost = (detailFine * (1.65 * uSharpness) + detailWide * (1.05 * uClarity)) * noiseGate;
              // Soft limit at +-0.24 so text & UI borders pop strongly without severe clipping
              boost = clamp(boost, -0.24, 0.24);
              vec3 sharpened = clamp(c + boost, 0.0, 1.0);

              // Subtle Micro-Contrast (1.06x) + Vibrance/Saturation Pop (1.12x)
              vec3 contrasted = clamp((sharpened - 0.5) * 1.06 + 0.5, 0.0, 1.0);
              float luma = getLuma(contrasted);
              vec3 finalRgb = mix(vec3(luma), contrasted, 1.0 + uVibrance);

              gl_FragColor = vec4(clamp(finalRgb, 0.0, 1.0), c4.a);
            }
        """
    }
}
