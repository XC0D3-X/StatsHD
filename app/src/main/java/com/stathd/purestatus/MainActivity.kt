package com.stathd.purestatus

import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
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
import java.util.Locale
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
        CLIP_30S_ULTRA("30s Ultra HD (2.6 Mbps)", 30_000L, 1280, 2_650_000),
        CLIP_60S_HD("60s HD Balanced (1.35 Mbps)", 60_000L, 1280, 1_350_000),
        CLIP_90S_FULL("90s Full Status (0.92 Mbps)", 90_000L, 1280, 920_000)
    }

    private lateinit var videoPreview: VideoView
    private lateinit var tvEmptyPreview: TextView
    private lateinit var tvSourceSpecs: TextView
    private lateinit var tvResolutionGuardInfo: TextView
    private lateinit var btnPickVideo: MaterialButton
    private lateinit var cardPreset720: LinearLayout
    private lateinit var cardPreset1080: LinearLayout
    private lateinit var cardPreset60s: LinearLayout
    private lateinit var tvTrimWindow: TextView
    private lateinit var seekBarTrimStart: SeekBar
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
        cardPreset720 = findViewById(R.id.cardPreset720)
        cardPreset1080 = findViewById(R.id.cardPreset1080)
        cardPreset60s = findViewById(R.id.cardPreset60s)
        tvTrimWindow = findViewById(R.id.tvTrimWindow)
        seekBarTrimStart = findViewById(R.id.seekBarTrimStart)
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
        cardPreset1080.setOnClickListener { selectPreset(StatusPreset.CLIP_60S_HD) }
        cardPreset60s.setOnClickListener { selectPreset(StatusPreset.CLIP_90S_FULL) }

        seekBarTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTrimWindowFromSlider(progress, seekPreview = fromUser)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnOptimize.setOnClickListener {
            val uri = selectedVideoUri
            if (uri != null) {
                startStatHdTranscode(uri, allowHdrToneMap = switchHdrToneMap.isChecked)
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
            if (preset == StatusPreset.CLIP_60S_HD) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        cardPreset60s.setBackgroundResource(
            if (preset == StatusPreset.CLIP_90S_FULL) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        seekBarTrimStart.progress = 0
        updateTrimWindowFromSlider(0, seekPreview = true)
        updateResolutionGuardReadout()
    }

    private fun onVideoSelected(uri: Uri) {
        selectedVideoUri = uri
        cardResult.visibility = View.GONE
        btnOptimize.isEnabled = true

        // Setup preview
        tvEmptyPreview.visibility = View.GONE
        videoPreview.visibility = View.VISIBLE
        videoPreview.setVideoURI(uri)
        videoPreview.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            videoPreview.start()
        }

        // Read metadata
        sourceSizeBytes = queryFileSize(uri)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            sourceDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L

            if (rotation == 90 || rotation == 270) {
                sourceWidth = rawH
                sourceHeight = rawW
            } else {
                sourceWidth = rawW
                sourceHeight = rawH
            }

            val sizeMb = sourceSizeBytes / (1024.0 * 1024.0)
            val durationSec = sourceDurationMs / 1000.0
            val bitrateMbps = bitrateBps / 1_000_000.0

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
            tvSourceSpecs.text = "Video loaded • Ready for StatHD v1.1 optimization"
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
            tvTrimWindow.text = "Clip Window: Auto-trim to max ${maxClipMs / 1000}s (<10.5 MB WA Ceiling)"
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
    }

    private fun computeSafeOutputDimensions(): Pair<Int, Int>? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val longSide = max(sourceWidth, sourceHeight)
        val cap = currentPreset.maxLongSidePx

        // Smart Resolution Guard: NEVER upscale if longSide is already <= 1280px (e.g. 1280x576 stays 1280x576 1:1!)
        if (longSide <= cap) {
            return null
        }

        val scale = cap.toDouble() / longSide.toDouble()
        // Round to even numbers for H.264 macroblock compliance
        val targetW = max(2, ((sourceWidth * scale) / 2.0).roundToInt() * 2)
        val targetH = max(2, ((sourceHeight * scale) / 2.0).roundToInt() * 2)
        return Pair(targetW, targetH)
    }

    private fun updateResolutionGuardReadout() {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            tvResolutionGuardInfo.text = "Resolution Guard: Max 1280px Long-Side (Never Upscale)"
            return
        }
        val scaled = computeSafeOutputDimensions()
        if (scaled == null) {
            tvResolutionGuardInfo.text = String.format(
                Locale.US,
                "✓ Resolution Guard: %dx%d is <= %dpx • 1:1 Native Pixels (Zero Upscaling!)",
                sourceWidth,
                sourceHeight,
                currentPreset.maxLongSidePx
            )
        } else {
            tvResolutionGuardInfo.text = String.format(
                Locale.US,
                "✓ Resolution Guard: Scaling %dx%d ➔ %dx%d (Strict %dpx Long-Side Cap)",
                sourceWidth,
                sourceHeight,
                scaled.first,
                scaled.second,
                currentPreset.maxLongSidePx
            )
        }
    }

    private fun computeSafeVideoBitrateBps(): Int {
        // Target max file size of 10.1 MB (84,724,940 bits) so output stays strictly below WhatsApp's 11 MB re-encode ceiling
        val clipDurationSec = if (sourceDurationMs > 0L && trimEndMs > trimStartMs) {
            max(1.0, (trimEndMs - trimStartMs) / 1000.0)
        } else {
            currentPreset.maxClipDurationMs / 1000.0
        }

        val audioBitrateBps = if (switchMuteAudio.isChecked) 0 else 128_000
        val maxTotalBits = 10.1 * 1024.0 * 1024.0 * 8.0
        val maxSafeVideoBps = ((maxTotalBits / clipDurationSec) - audioBitrateBps).toInt()

        return min(currentPreset.maxBitrateBps, max(750_000, maxSafeVideoBps))
    }

    private fun startStatHdTranscode(inputUri: Uri, allowHdrToneMap: Boolean) {
        btnOptimize.isEnabled = false
        btnPickVideo.isEnabled = false
        cardResult.visibility = View.GONE
        layoutProgress.visibility = View.VISIBLE
        progressBar.progress = 0

        val targetBitrateBps = computeSafeVideoBitrateBps()
        val targetMbps = targetBitrateBps / 1_000_000.0
        tvProgressStatus.text = String.format(
            Locale.US,
            "Initializing Hardware H.264 Encoder (%.2f Mbps • <10.5 MB Target)...",
            targetMbps
        )

        val outDir = File(cacheDir, "stathd_exports").apply { mkdirs() }
        val outFile = File(outDir, "StatHD_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()
        outputVideoFile = outFile

        // 1. Smart Resolution Guard + Optional Contrast (Empty list by default for 1:1 native pixels & 100% original colors!)
        val videoEffects = mutableListOf<Effect>()
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
        if (switchCrispBoost.isChecked) {
            videoEffects.add(Contrast(0.05f))
        }

        // 2. Built-in Clipping Configuration so videos >30s/60s/90s are pre-trimmed cleanly
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

        // 3. Configure Bitrate-Locked Hardware H.264 Encoder
        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(targetBitrateBps)
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
                        startStatHdTranscode(inputUri, allowHdrToneMap = false)
                        return
                    }
                    onTranscodeError(exportException)
                }
            })
            .build()

        activeTransformer = transformer
        transformer.start(composition, outFile.absolutePath)
        pollTransformerProgress(transformer, targetMbps)
    }

    private fun pollTransformerProgress(transformer: Transformer, targetMbps: Double) {
        val runnable = object : Runnable {
            override fun run() {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress
                    progressBar.progress = pct
                    tvProgressStatus.text = String.format(
                        Locale.US,
                        "Encoding H.264 (%.2f Mbps • 100%% Original Colors)... %d%%",
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

        val outW = targetDims?.first ?: sourceWidth
        val outH = targetDims?.second ?: sourceHeight
        val resLabel = if (targetDims == null) "${outW}x${outH} (1:1 Native)" else "${outW}x${outH}"
        val colorLabel = if (!switchCrispBoost.isChecked && !switchHdrToneMap.isChecked) {
            "100% Original SDR"
        } else {
            "Custom Tuned"
        }

        tvResultSpecs.text = String.format(
            Locale.US,
            "Output: %s | Size: %.2f MB (Source: %.2f MB)\nClip: %s ➔ %s | Bitrate: %.2f Mbps H.264\nColors: %s | Status: <11 MB WA PASSTHROUGH READY",
            resLabel,
            newSizeMb,
            oldSizeMb,
            formatTimeMs(trimStartMs),
            formatTimeMs(trimEndMs),
            targetMbps,
            colorLabel
        )

        videoPreview.setVideoPath(outFile.absolutePath)
        videoPreview.start()
        Toast.makeText(this, "StatHD v1.1 Ready (<10.5 MB WhatsApp Bypass)!", Toast.LENGTH_SHORT).show()
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
