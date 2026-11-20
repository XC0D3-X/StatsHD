package com.stathd.purestatus

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    enum class StatusPreset(
        val label: String,
        val maxClipDurationMs: Long,
        val maxLongSidePx: Int,
        val maxBitrateBps: Int
    ) {
        CLIP_30S_ULTRA("30s Wink Ultra HD (3.2 Mbps)", 30_000L, 1280, 3_200_000),
        CLIP_45S_HD("45s Wink High Clarity (1.80 Mbps)", 45_000L, 1280, 1_800_000),
        CLIP_60S_FULL("60s 1-Minute Status (1.38 Mbps)", 60_000L, 1280, 1_380_000)
    }

    private lateinit var videoPreview: VideoView
    private lateinit var tvEmptyPreview: TextView
    private lateinit var tvSourceSpecs: TextView
    private lateinit var tvResolutionGuardInfo: TextView
    private lateinit var btnPickVideo: MaterialButton
    private lateinit var switchWinkRestore: SwitchMaterial
    private lateinit var switchWinkMax: SwitchMaterial
    private lateinit var cardPreset720: LinearLayout
    private lateinit var cardPreset1080: LinearLayout
    private lateinit var cardPreset60s: LinearLayout
    private lateinit var tvTrimWindow: TextView
    private lateinit var seekBarTrimStart: SeekBar
    private lateinit var switchRotateVertical: SwitchMaterial
    private lateinit var switchCrispBoost: SwitchMaterial
    private lateinit var switchHdrToneMap: SwitchMaterial
    private lateinit var switchMuteAudio: SwitchMaterial
    private lateinit var btnOptimize: MaterialButton
    private lateinit var layoutProgress: LinearLayout
    private lateinit var tvProgressStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cardResult: LinearLayout
    private lateinit var tvResultSpecs: TextView
    private lateinit var btnShareWhatsApp: MaterialButton
    private lateinit var btnSaveGallery: MaterialButton

    private var selectedVideoUri: Uri? = null
    private var sourceSizeBytes: Long = 0L
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    private var sourceDurationMs: Long = 0L
    private var sourceBitrateBps: Long = 0L
    private var trimStartMs: Long = 0L
    private var trimEndMs: Long = 0L

    private var currentPreset: StatusPreset = StatusPreset.CLIP_30S_ULTRA
    private var outputVideoFile: File? = null
    private var activeTransformer: Transformer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onVideoSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        handleIncomingShareIntent(intent)
    }

    private fun bindViews() {
        videoPreview = findViewById(R.id.videoPreview)
        tvEmptyPreview = findViewById(R.id.tvEmptyPreview)
        tvSourceSpecs = findViewById(R.id.tvSourceSpecs)
        tvResolutionGuardInfo = findViewById(R.id.tvResolutionGuardInfo)
        btnPickVideo = findViewById(R.id.btnPickVideo)
        switchWinkRestore = findViewById(R.id.switchWinkRestore)
        switchWinkMax = findViewById(R.id.switchWinkMax)
        cardPreset720 = findViewById(R.id.cardPreset720)
        cardPreset1080 = findViewById(R.id.cardPreset1080)
        cardPreset60s = findViewById(R.id.cardPreset60s)
        tvTrimWindow = findViewById(R.id.tvTrimWindow)
        seekBarTrimStart = findViewById(R.id.seekBarTrimStart)
        switchRotateVertical = findViewById(R.id.switchRotateVertical)
        switchCrispBoost = findViewById(R.id.switchCrispBoost)
        switchHdrToneMap = findViewById(R.id.switchHdrToneMap)
        switchMuteAudio = findViewById(R.id.switchMuteAudio)
        btnOptimize = findViewById(R.id.btnOptimize)
        layoutProgress = findViewById(R.id.layoutProgress)
        tvProgressStatus = findViewById(R.id.tvProgressStatus)
        progressBar = findViewById(R.id.progressBar)
        cardResult = findViewById(R.id.cardResult)
        tvResultSpecs = findViewById(R.id.tvResultSpecs)
        btnShareWhatsApp = findViewById(R.id.btnShareWhatsApp)
        btnSaveGallery = findViewById(R.id.btnSaveGallery)
    }

    private fun setupListeners() {
        btnPickVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        cardPreset720.setOnClickListener { selectPreset(StatusPreset.CLIP_30S_ULTRA) }
        cardPreset1080.setOnClickListener { selectPreset(StatusPreset.CLIP_45S_HD) }
        cardPreset60s.setOnClickListener { selectPreset(StatusPreset.CLIP_60S_FULL) }

        seekBarTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTrimWindowFromSlider(progress, seekPreview = fromUser)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        switchWinkRestore.setOnCheckedChangeListener { _, isChecked ->
            switchWinkMax.isEnabled = isChecked
            updateResolutionGuardReadout()
        }
        switchWinkMax.setOnCheckedChangeListener { _, _ -> updateResolutionGuardReadout() }
        switchRotateVertical.setOnCheckedChangeListener { _, _ -> updateResolutionGuardReadout() }
        switchCrispBoost.setOnCheckedChangeListener { _, _ -> updateResolutionGuardReadout() }
        switchHdrToneMap.setOnCheckedChangeListener { _, _ -> updateResolutionGuardReadout() }

        btnOptimize.setOnClickListener {
            val uri = selectedVideoUri
            if (uri != null) {
                executeOptimization(uri)
            }
        }

        btnShareWhatsApp.setOnClickListener {
            outputVideoFile?.let { shareToWhatsApp(it) }
        }

        btnSaveGallery.setOnClickListener {
            outputVideoFile?.let { saveVideoToGallery(it) }
        }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            val sharedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (sharedUri != null) {
                onVideoSelected(sharedUri)
            }
        }
    }

    private fun selectPreset(preset: StatusPreset) {
        currentPreset = preset
        cardPreset720.setBackgroundResource(
            if (preset == StatusPreset.CLIP_30S_ULTRA) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        cardPreset1080.setBackgroundResource(
            if (preset == StatusPreset.CLIP_45S_HD) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        cardPreset60s.setBackgroundResource(
            if (preset == StatusPreset.CLIP_60S_FULL) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        seekBarTrimStart.progress = 0
        updateTrimWindowFromSlider(0, seekPreview = true)
        updateResolutionGuardReadout()
    }

    private fun onVideoSelected(uri: Uri) {
        selectedVideoUri = uri
        cardResult.visibility = View.GONE
        btnOptimize.isEnabled = true

        tvEmptyPreview.visibility = View.GONE
        videoPreview.visibility = View.VISIBLE
        videoPreview.setVideoURI(uri)
        videoPreview.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            videoPreview.start()
        }

        sourceSizeBytes = queryFileSize(uri)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            sourceDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            sourceBitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L

            if (rotation == 90 || rotation == 270) {
                sourceWidth = rawH
                sourceHeight = rawW
            } else {
                sourceWidth = rawW
                sourceHeight = rawH
            }

            val sizeMb = sourceSizeBytes / (1024.0 * 1024.0)
            val durationSec = sourceDurationMs / 1000.0
            val bitrateMbps = sourceBitrateBps / 1_000_000.0

            tvSourceSpecs.text = String.format(
                Locale.US,
                "Source: %dx%d | %.2f MB | %s (%.1fs) | %.2f Mbps",
                sourceWidth,
                sourceHeight,
                sizeMb,
                formatTimeMs(sourceDurationMs),
                durationSec,
                bitrateMbps
            )
        } catch (e: Exception) {
            sourceWidth = 0
            sourceHeight = 0
            sourceDurationMs = 0L
            sourceBitrateBps = 0L
            tvSourceSpecs.text = "Video loaded • Ready for Wink GPU Ultra-HD Restoration"
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        seekBarTrimStart.progress = 0
        updateTrimWindowFromSlider(0, seekPreview = false)
        updateResolutionGuardReadout()
    }

    private fun updateTrimWindowFromSlider(progress: Int, seekPreview: Boolean) {
        val totalMs = sourceDurationMs
        val maxClipMs = currentPreset.maxClipDurationMs

        if (totalMs <= 0L) {
            trimStartMs = 0L
            trimEndMs = maxClipMs
            tvTrimWindow.text = "Clip Window: Auto-trim to max ${maxClipMs / 1000}s"
            return
        }

        if (totalMs <= maxClipMs) {
            trimStartMs = 0L
            trimEndMs = totalMs
            seekBarTrimStart.isEnabled = false
            tvTrimWindow.text = "Clip Window: Full Video (0:00 ➔ ${formatTimeMs(totalMs)}) • Fits in 1 Status"
        } else {
            seekBarTrimStart.isEnabled = true
            val maxStartMs = totalMs - maxClipMs
            trimStartMs = (maxStartMs * (progress / 100.0)).toLong()
            trimEndMs = min(totalMs, trimStartMs + maxClipMs)
            tvTrimWindow.text = String.format(
                Locale.US,
                "Clip Window: %s ➔ %s (%ds clip • Slide to pick highlight)",
                formatTimeMs(trimStartMs),
                formatTimeMs(trimEndMs),
                (trimEndMs - trimStartMs) / 1000L
            )
            if (seekPreview && videoPreview.visibility == View.VISIBLE) {
                try {
                    videoPreview.seekTo(trimStartMs.toInt())
                } catch (_: Exception) {
                }
            }
        }
        updateResolutionGuardReadout()
    }

    private fun canUseLosslessDirectCopy(): Boolean {
        if (switchWinkRestore.isChecked) return false
        if (sourceWidth <= 0 || sourceHeight <= 0 || sourceDurationMs <= 0L) return false
        val longSide = max(sourceWidth, sourceHeight)
        if (longSide > currentPreset.maxLongSidePx) return false
        if (switchRotateVertical.isChecked || switchCrispBoost.isChecked || switchHdrToneMap.isChecked) return false

        val clipDurMs = max(1000L, trimEndMs - trimStartMs)
        val estimatedSliceBytes = if (sourceSizeBytes > 0L) {
            (sourceSizeBytes.toDouble() * (clipDurMs.toDouble() / sourceDurationMs.toDouble())).toLong()
        } else {
            ((sourceBitrateBps / 8.0) * (clipDurMs / 1000.0)).toLong()
        }

        return estimatedSliceBytes in 1..(11_800_000L)
    }

    private fun computeSafeOutputDimensions(): Pair<Int, Int>? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val effW = if (switchRotateVertical.isChecked) sourceHeight else sourceWidth
        val effH = if (switchRotateVertical.isChecked) sourceWidth else sourceHeight
        val longSide = max(effW, effH)
        val cap = currentPreset.maxLongSidePx

        if (longSide <= cap) {
            return null
        }

        val scale = cap.toDouble() / longSide.toDouble()
        val targetW = max(2, ((effW * scale) / 2.0).roundToInt() * 2)
        val targetH = max(2, ((effH * scale) / 2.0).roundToInt() * 2)
        return Pair(targetW, targetH)
    }

    private fun updateResolutionGuardReadout() {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            tvResolutionGuardInfo.text = "Engine Mode: Wink GPU Restoration + 1280px Guard"
            return
        }

        if (canUseLosslessDirectCopy()) {
            tvResolutionGuardInfo.text = String.format(
                Locale.US,
                "★ 100%% LOSSLESS STREAM COPY: %dx%d • Zero Re-Encoding",
                sourceWidth,
                sourceHeight
            )
            return
        }

        val effW = if (switchRotateVertical.isChecked) sourceHeight else sourceWidth
        val effH = if (switchRotateVertical.isChecked) sourceWidth else sourceHeight
        val scaled = computeSafeOutputDimensions()
        val winkMode = if (switchWinkRestore.isChecked) {
            if (switchWinkMax.isChecked) "✨ Wink MAX GPU Shader" else "✨ Wink Balanced GPU Shader"
        } else {
            "Standard H.264"
        }

        if (scaled == null) {
            tvResolutionGuardInfo.text = String.format(
                Locale.US,
                "✓ %s | %dx%d (1:1 Native Pixels • Zero Upscaling)",
                winkMode,
                effW,
                effH
            )
        } else {
            tvResolutionGuardInfo.text = String.format(
                Locale.US,
                "✓ %s | Scaling %dx%d ➔ %dx%d",
                winkMode,
                effW,
                effH,
                scaled.first,
                scaled.second
            )
        }
    }

    private fun executeOptimization(inputUri: Uri) {
        val outDir = File(cacheDir, "stathd_exports").apply { mkdirs() }
        val outFile = File(outDir, "StatHD_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()
        outputVideoFile = outFile

        if (canUseLosslessDirectCopy()) {
            startLosslessStreamCut(inputUri, outFile)
        } else {
            startStatHdTranscode(inputUri, outFile, allowHdrToneMap = switchHdrToneMap.isChecked)
        }
    }

    @SuppressLint("WrongConstant")
    private fun startLosslessStreamCut(inputUri: Uri, outFile: File) {
        btnOptimize.isEnabled = false
        btnPickVideo.isEnabled = false
        cardResult.visibility = View.GONE
        layoutProgress.visibility = View.VISIBLE
        progressBar.progress = 15
        tvProgressStatus.text = "Running 100% Lossless Direct Stream Copy (Zero Re-Encoding)..."

        val startUs = trimStartMs * 1000L
        val endUs = if (trimEndMs > trimStartMs) trimEndMs * 1000L else Long.MAX_VALUE
        val removeAudio = switchMuteAudio.isChecked

        thread(start = true) {
            var extractor: MediaExtractor? = null
            var muxer: MediaMuxer? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(this, inputUri, null)

                muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val trackMap = HashMap<Int, Int>()
                var videoTrackIndex = -1

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        extractor.selectTrack(i)
                        val dstIndex = muxer.addTrack(format)
                        trackMap[i] = dstIndex
                        videoTrackIndex = i
                        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                        }
                    } else if (mime.startsWith("audio/") && !removeAudio) {
                        extractor.selectTrack(i)
                        val dstIndex = muxer.addTrack(format)
                        trackMap[i] = dstIndex
                    }
                }

                if (videoTrackIndex == -1) {
                    throw IllegalStateException("No video track found")
                }

                muxer.start()

                if (startUs > 0L) {
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                val bufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break

                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs > endUs) break

                    val trackIndex = extractor.sampleTrackIndex
                    val dstTrack = trackMap[trackIndex]
                    if (dstTrack != null && sampleTimeUs >= startUs - 500_000L) {
                        bufferInfo.presentationTimeUs = max(0L, sampleTimeUs - startUs)
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                    }
                    extractor.advance()
                }

                muxer.stop()
                mainHandler.post {
                    onLosslessCopySuccess(outFile)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (outFile.exists()) outFile.delete()
                    startStatHdTranscode(inputUri, outFile, allowHdrToneMap = switchHdrToneMap.isChecked)
                }
            } finally {
                try {
                    extractor?.release()
                } catch (_: Exception) {
                }
                try {
                    muxer?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun onLosslessCopySuccess(outFile: File) {
        btnOptimize.isEnabled = true
        btnPickVideo.isEnabled = true
        progressBar.progress = 100
        layoutProgress.visibility = View.GONE
        cardResult.visibility = View.VISIBLE

        val newSizeBytes = outFile.length()
        val newSizeMb = newSizeBytes / (1024.0 * 1024.0)
        val oldSizeMb = sourceSizeBytes / (1024.0 * 1024.0)

        tvResultSpecs.text = String.format(
            Locale.US,
            "★ MODE: 100%% LOSSLESS STREAM COPY (0%% Pixel Loss)\nOutput: %dx%d | Size: %.2f MB (Source: %.2f MB)\nClip: %s ➔ %s | Status: <11 MB WA DIRECT PASSTHROUGH",
            sourceWidth,
            sourceHeight,
            newSizeMb,
            oldSizeMb,
            formatTimeMs(trimStartMs),
            formatTimeMs(trimEndMs)
        )

        videoPreview.setVideoPath(outFile.absolutePath)
        videoPreview.start()
        Toast.makeText(this, "100% Lossless Cut Complete!", Toast.LENGTH_LONG).show()
    }

    private fun computeSafeVideoBitrateBps(): Int {
        val clipDurationSec = if (sourceDurationMs > 0L && trimEndMs > trimStartMs) {
            max(1.0, (trimEndMs - trimStartMs) / 1000.0)
        } else {
            currentPreset.maxClipDurationMs / 1000.0
        }

        val audioBitrateBps = if (switchMuteAudio.isChecked) 0 else 128_000
        // Keep output strictly <= 10.2 MB so WhatsApp Status never re-encodes the file
        val maxTotalBits = 10.2 * 1024.0 * 1024.0 * 8.0
        val maxSafeVideoBps = ((maxTotalBits / clipDurationSec) - audioBitrateBps).toInt()

        return min(currentPreset.maxBitrateBps, max(900_000, maxSafeVideoBps))
    }

    private fun startStatHdTranscode(inputUri: Uri, outFile: File, allowHdrToneMap: Boolean) {
        btnOptimize.isEnabled = false
        btnPickVideo.isEnabled = false
        cardResult.visibility = View.GONE
        layoutProgress.visibility = View.VISIBLE
        progressBar.progress = 0

        val targetBitrateBps = computeSafeVideoBitrateBps()
        val targetMbps = targetBitrateBps / 1_000_000.0
        val winkActive = switchWinkRestore.isChecked
        tvProgressStatus.text = String.format(
            Locale.US,
            "Initializing %s (%.2f Mbps)...",
            if (winkActive) "Wink GPU Ultra-HD Shader" else "H.264 High-Clarity Encoder",
            targetMbps
        )

        val videoEffects = mutableListOf<Effect>()
        if (switchRotateVertical.isChecked) {
            videoEffects.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(90f)
                    .build()
            )
        }

        val targetDims = computeSafeOutputDimensions()
        if (targetDims != null) {
            videoEffects.add(
                Presentation.createForWidthAndHeight(
                    targetDims.first,
                    targetDims.second,
                    Presentation.LAYOUT_SCALE_TO_FIT
                )
            )
        }

        // Add Wink GPU Ultra-HD Restoration Shader (Dual-Radius 3x3 Fine + 7x7 Wide Clarity Unsharp)
        if (winkActive) {
            if (switchWinkMax.isChecked) {
                videoEffects.add(
                    WinkUltraHdEffect(
                        sharpnessStrength = 1.05f,
                        denoiseStrength = 0.0f,
                        vibranceBoost = 0.12f
                    )
                )
            } else {
                videoEffects.add(
                    WinkUltraHdEffect(
                        sharpnessStrength = 0.72f,
                        denoiseStrength = 0.0f,
                        vibranceBoost = 0.08f
                    )
                )
            }
        }

        if (switchCrispBoost.isChecked) {
            videoEffects.add(Contrast(0.05f))
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(inputUri)
        if (sourceDurationMs > 0L && (trimStartMs > 0L || trimEndMs < sourceDurationMs)) {
            val clipConfig = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(trimStartMs)
                .setEndPositionMs(trimEndMs)
                .build()
            mediaItemBuilder.setClippingConfiguration(clipConfig)
        }
        val mediaItem = mediaItemBuilder.build()

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .setRemoveAudio(switchMuteAudio.isChecked)
            .build()

        val compositionBuilder = Composition.Builder(EditedMediaItemSequence(editedMediaItem))
        if (allowHdrToneMap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            compositionBuilder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC)
        }
        val composition = compositionBuilder.build()

        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(targetBitrateBps)
            .setiFrameIntervalSeconds(1.0f)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .build()

        val transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    mainHandler.removeCallbacksAndMessages(null)
                    onTranscodeSuccess(outFile, targetBitrateBps, targetDims)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    mainHandler.removeCallbacksAndMessages(null)
                    if (allowHdrToneMap) {
                        startStatHdTranscode(inputUri, outFile, allowHdrToneMap = false)
                        return
                    }
                    onTranscodeError(exportException)
                }
            })
            .build()

        activeTransformer = transformer
        transformer.start(composition, outFile.absolutePath)
        pollTransformerProgress(transformer, targetMbps, winkActive)
    }

    private fun pollTransformerProgress(transformer: Transformer, targetMbps: Double, winkActive: Boolean) {
        val label = if (winkActive) "Wink GPU De-Block + CAS Sharpen" else "H.264 1s-Keyframe"
        val runnable = object : Runnable {
            override fun run() {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress
                    progressBar.progress = pct
                    tvProgressStatus.text = String.format(
                        Locale.US,
                        "Rendering %s (%.2f Mbps)... %d%%",
                        label,
                        targetMbps,
                        pct
                    )
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    mainHandler.postDelayed(this, 150L)
                }
            }
        }
        mainHandler.post(runnable)
    }

    private fun onTranscodeSuccess(outFile: File, targetBitrateBps: Int, targetDims: Pair<Int, Int>?) {
        btnOptimize.isEnabled = true
        btnPickVideo.isEnabled = true
        progressBar.progress = 100
        layoutProgress.visibility = View.GONE
        cardResult.visibility = View.VISIBLE

        val newSizeBytes = outFile.length()
        val newSizeMb = newSizeBytes / (1024.0 * 1024.0)
        val oldSizeMb = sourceSizeBytes / (1024.0 * 1024.0)
        val targetMbps = targetBitrateBps / 1_000_000.0

        val effW = if (switchRotateVertical.isChecked) sourceHeight else sourceWidth
        val effH = if (switchRotateVertical.isChecked) sourceWidth else sourceHeight
        val outW = targetDims?.first ?: effW
        val outH = targetDims?.second ?: effH
        val resLabel = if (switchRotateVertical.isChecked) {
            "${outW}x${outH} (90° Full-Screen)"
        } else if (targetDims == null) {
            "${outW}x${outH} (1:1 Native)"
        } else {
            "${outW}x${outH}"
        }
        val winkBadge = if (switchWinkRestore.isChecked) {
            if (switchWinkMax.isChecked) "✨ Wink MAX GPU Restored" else "✨ Wink Balanced Restored"
        } else {
            "Standard H.264"
        }

        tvResultSpecs.text = String.format(
            Locale.US,
            "Engine: %s\nOutput: %s | Size: %.2f MB (Source: %.2f MB)\nClip: %s ➔ %s | Bitrate: %.2f Mbps (<11 MB WA Bypass)",
            winkBadge,
            resLabel,
            newSizeMb,
            oldSizeMb,
            formatTimeMs(trimStartMs),
            formatTimeMs(trimEndMs),
            targetMbps
        )

        videoPreview.setVideoPath(outFile.absolutePath)
        videoPreview.start()
        Toast.makeText(this, "Wink GPU Ultra-HD Restoration Complete!", Toast.LENGTH_SHORT).show()
    }

    private fun onTranscodeError(error: ExportException) {
        btnOptimize.isEnabled = true
        btnPickVideo.isEnabled = true
        layoutProgress.visibility = View.GONE
        Toast.makeText(
            this,
            "Encoding error: ${error.localizedMessage ?: "Please try again"}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun shareToWhatsApp(file: File) {
        val contentUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file
        )

        val waIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.whatsapp")
        }

        try {
            startActivity(waIntent)
        } catch (_: Exception) {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share StatHD Video to WhatsApp Status"
            )
            startActivity(chooser)
        }
    }

    private fun saveVideoToGallery(file: File) {
        try {
            val fileName = "StatHD_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StatHD")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collection, values)
            if (itemUri != null) {
                resolver.openOutputStream(itemUri)?.use { outStream ->
                    file.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                }
                Toast.makeText(
                    this,
                    "Saved to Gallery: Movies/StatHD/$fileName",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Failed to save to Gallery: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun queryFileSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    cursor.getLong(sizeIndex)
                } else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSec = max(0L, ms / 1000L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        activeTransformer?.cancel()
    }
}
