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
import kotlin.math.max

/**
 * WinkUltraHdEffect v1.4.7 — Resolution-Adaptive Ultra-Sharp Dual-Radius GPU Shader
 *
 * Automatically adapts kernel radii for both 720p (1280px) and 1080p (1920px) outputs:
 * 1. Fine Edge Kernel (1.25px * resScale, up to 2.25x strength): Reconstructs ultra-crisp
 *    text, damage numbers, HP bars, eyes, and fine outlines.
 * 2. Wide 3D Clarity Kernel (3.2px * resScale, up to 1.45x strength): Removes hazy softness
 *    from zoomed-in gameplay/camera videos and adds strong 3D local texture pop.
 * 3. Crisp Micro-Contrast (1.09x) + Vivid Color Pop (1.15x) with wide +-0.34 headroom.
 */
@OptIn(UnstableApi::class)
class WinkUltraHdEffect(
    private val sharpnessStrength: Float = 1.15f,
    private val denoiseStrength: Float = 0.0f,
    private val vibranceBoost: Float = 0.15f
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return WinkUltraHdShaderProgram(
            useHdr = useHdr,
            sharpness = sharpnessStrength,
            clarity = if (sharpnessStrength >= 0.85f) 1.45f else 0.90f,
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
    private var texelWidth: Float = 1.0f / 1080.0f
    private var texelHeight: Float = 1.0f / 1920.0f
    private var resScale: Float = 1.5f

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
            glProgram.setFloatUniform("uResScale", resScale)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(texelWidth, texelHeight))
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val safeW = if (inputWidth > 0) inputWidth else 1080
        val safeH = if (inputHeight > 0) inputHeight else 1920
        texelWidth = 1.0f / safeW.toFloat()
        texelHeight = 1.0f / safeH.toFloat()
        // Automatically scale tap radius at 1080p (1920px) so sharpening stays just as punchy as 720p!
        val longSide = max(safeW, safeH).toFloat()
        resScale = max(1.0f, longSide / 1280.0f)
        return Size(safeW, safeH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(texelWidth, texelHeight))
            glProgram.setFloatUniform("uResScale", resScale)
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
            uniform float uResScale;
            uniform float uSharpness;
            uniform float uClarity;
            uniform float uVibrance;
            varying vec2 vTexSamplingCoord;

            float getLuma(vec3 rgb) {
              return dot(rgb, vec3(0.299, 0.587, 0.114));
            }

            void main() {
              vec2 uv = vTexSamplingCoord;

              vec4 c4 = texture2D(uTexSampler, uv);
              vec3 c  = c4.rgb;

              // SCALE 1: Fine High-Frequency Edge Reconstruction (resolution-adaptive)
              float r1 = 1.25 * uResScale;
              vec2 dx1 = vec2(uTexelSize.x * r1, 0.0);
              vec2 dy1 = vec2(0.0, uTexelSize.y * r1);
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

              // SCALE 2: Wide 3D Local Clarity / Dehaze (reconstructs zoomed textures & armor)
              float r2 = 3.2 * uResScale;
              vec2 dx2 = vec2(uTexelSize.x * r2, 0.0);
              vec2 dy2 = vec2(0.0, uTexelSize.y * r2);
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

              float edgeEnergy = abs(getLuma(detailFine)) + abs(getLuma(detailWide));
              float noiseGate = smoothstep(0.002, 0.012, edgeEnergy);

              // Strong Wink Ultra-HD Reconstruction
              vec3 boost = (detailFine * (2.25 * uSharpness) + detailWide * (1.35 * uClarity)) * noiseGate;
              boost = clamp(boost, -0.34, 0.34);
              vec3 sharpened = clamp(c + boost, 0.0, 1.0);

              // Crisp Micro-Contrast (1.09x) + Vivid Color Pop
              vec3 contrasted = clamp((sharpened - 0.5) * 1.09 + 0.5, 0.0, 1.0);
              float luma = getLuma(contrasted);
              vec3 finalRgb = mix(vec3(luma), contrasted, 1.0 + uVibrance);

              gl_FragColor = vec4(clamp(finalRgb, 0.0, 1.0), c4.a);
            }
        """
    }
}
