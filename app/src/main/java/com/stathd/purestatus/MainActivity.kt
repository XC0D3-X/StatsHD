package com.stathd.purestatus

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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
        val pillLabel: String,
        val maxClipDurationMs: Long,
        val maxLongSidePx: Int,
        val maxBitrateBps: Int
    ) {
        CLIP_30S_ULTRA("720p HD • 30s", 30_000L, 1280, 3_200_000),
        CLIP_45S_HD("720p HD • 45s", 45_000L, 1280, 1_800_000),
        CLIP_60S_FULL("720p HD • 60s", 60_000L, 1280, 1_380_000)
    }

    enum class CompareMode {
        ORIGINAL, SPLIT, ENHANCED
    }

    // Top bar & triggers
    private lateinit var btnMenu: ImageButton
    private lateinit var btnPresetPicker: LinearLayout
    private lateinit var tvPresetPill: TextView

    // Main viewport
    private lateinit var videoPreview: VideoView
    private lateinit var layoutSplitCompare: FrameLayout
    private lateinit var ivAfterFrame: ImageView
    private lateinit var ivBeforeFrame: ImageView
    private lateinit var viewSplitLine: View
    private lateinit var seekBarSplit: SeekBar
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var layoutCompareBar: LinearLayout
    private lateinit var tabOriginal: TextView
    private lateinit var tabSplit: TextView
    private lateinit var tabEnhanced: TextView
    private lateinit var tvSourceSpecs: TextView
    private lateinit var fabPickVideo: ImageButton

    // Timeline controls
    private lateinit var btnPlayPause: ImageButton
    private lateinit var tvCurrentTime: TextView
    private lateinit var seekBarTimeline: SeekBar
    private lateinit var tvTotalTime: TextView
    private lateinit var tvTrimWindow: TextView
    private lateinit var seekBarTrimStart: SeekBar

    // Bottom export controls
    private lateinit var layoutProgress: LinearLayout
    private lateinit var tvProgressStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnOptimize: MaterialButton
    private lateinit var layoutReadyActions: LinearLayout
    private lateinit var btnReExport: MaterialButton
    private lateinit var btnOpenSharePopup: MaterialButton

    // Popups
    private lateinit var overlayMenuPopup: FrameLayout
    private lateinit var btnCloseMenu: ImageButton
    private lateinit var switchWinkRestore: SwitchMaterial
    private lateinit var switchWinkMax: SwitchMaterial
    private lateinit var switchRotateVertical: SwitchMaterial
    private lateinit var switchMuteAudio: SwitchMaterial
    private lateinit var switchHdrToneMap: SwitchMaterial

    private lateinit var overlayPresetPopup: FrameLayout
    private lateinit var btnClosePreset: ImageButton
    private lateinit var cardPreset30: LinearLayout
    private lateinit var cardPreset45: LinearLayout
    private lateinit var cardPreset60: LinearLayout

    private lateinit var overlayResultPopup: FrameLayout
    private lateinit var btnCloseResult: ImageButton
    private lateinit var tvResultSpecs: TextView
    private lateinit var btnCompareNow: MaterialButton
    private lateinit var btnShareWhatsApp: MaterialButton
    private lateinit var btnSaveGallery: MaterialButton

    // State
    private var selectedVideoUri: Uri? = null
    private var outputVideoFile: File? = null
    private var sourceSizeBytes: Long = 0L
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    private var sourceDurationMs: Long = 0L
    private var sourceBitrateBps: Long = 0L
    private var trimStartMs: Long = 0L
    private var trimEndMs: Long = 0L

    private var activeVideoDurationMs: Long = 0L
    private var isVideoPlaying: Boolean = false
    private var isScrubbingTimeline: Boolean = false
    private var compareMode: CompareMode = CompareMode.ORIGINAL
    private var currentPreset: StatusPreset = StatusPreset.CLIP_30S_ULTRA
    private var activeTransformer: Transformer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()

    private val timelineUpdateRunnable = object : Runnable {
        override fun run() {
            if (isVideoPlaying && !isScrubbingTimeline && compareMode != CompareMode.SPLIT && videoPreview.visibility == View.VISIBLE) {
                try {
                    val posMs = videoPreview.currentPosition.toLong()
                    val durMs = max(1L, activeVideoDurationMs)
                    val progress = ((posMs * 1000L) / durMs).toInt().coerceIn(0, 1000)
                    seekBarTimeline.progress = progress
                    tvCurrentTime.text = formatTimeMs(posMs)
                } catch (_: Exception) {
                }
            }
            mainHandler.postDelayed(this, 150L)
        }
    }

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
        mainHandler.post(timelineUpdateRunnable)
    }

    private fun bindViews() {
        btnMenu = findViewById(R.id.btnMenu)
        btnPresetPicker = findViewById(R.id.btnPresetPicker)
        tvPresetPill = findViewById(R.id.tvPresetPill)

        videoPreview = findViewById(R.id.videoPreview)
        layoutSplitCompare = findViewById(R.id.layoutSplitCompare)
        ivAfterFrame = findViewById(R.id.ivAfterFrame)
        ivBeforeFrame = findViewById(R.id.ivBeforeFrame)
        viewSplitLine = findViewById(R.id.viewSplitLine)
        seekBarSplit = findViewById(R.id.seekBarSplit)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        layoutCompareBar = findViewById(R.id.layoutCompareBar)
        tabOriginal = findViewById(R.id.tabOriginal)
        tabSplit = findViewById(R.id.tabSplit)
        tabEnhanced = findViewById(R.id.tabEnhanced)
        tvSourceSpecs = findViewById(R.id.tvSourceSpecs)
        fabPickVideo = findViewById(R.id.fabPickVideo)

        btnPlayPause = findViewById(R.id.btnPlayPause)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        seekBarTimeline = findViewById(R.id.seekBarTimeline)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        tvTrimWindow = findViewById(R.id.tvTrimWindow)
        seekBarTrimStart = findViewById(R.id.seekBarTrimStart)

        layoutProgress = findViewById(R.id.layoutProgress)
        tvProgressStatus = findViewById(R.id.tvProgressStatus)
        progressBar = findViewById(R.id.progressBar)
        btnOptimize = findViewById(R.id.btnOptimize)
        layoutReadyActions = findViewById(R.id.layoutReadyActions)
        btnReExport = findViewById(R.id.btnReExport)
        btnOpenSharePopup = findViewById(R.id.btnOpenSharePopup)

        overlayMenuPopup = findViewById(R.id.overlayMenuPopup)
        btnCloseMenu = findViewById(R.id.btnCloseMenu)
        switchWinkRestore = findViewById(R.id.switchWinkRestore)
        switchWinkMax = findViewById(R.id.switchWinkMax)
        switchRotateVertical = findViewById(R.id.switchRotateVertical)
        switchMuteAudio = findViewById(R.id.switchMuteAudio)
        switchHdrToneMap = findViewById(R.id.switchHdrToneMap)

        overlayPresetPopup = findViewById(R.id.overlayPresetPopup)
        btnClosePreset = findViewById(R.id.btnClosePreset)
        cardPreset30 = findViewById(R.id.cardPreset30)
        cardPreset45 = findViewById(R.id.cardPreset45)
        cardPreset60 = findViewById(R.id.cardPreset60)

        overlayResultPopup = findViewById(R.id.overlayResultPopup)
        btnCloseResult = findViewById(R.id.btnCloseResult)
        tvResultSpecs = findViewById(R.id.tvResultSpecs)
        btnCompareNow = findViewById(R.id.btnCompareNow)
        btnShareWhatsApp = findViewById(R.id.btnShareWhatsApp)
        btnSaveGallery = findViewById(R.id.btnSaveGallery)
    }

    private fun setupListeners() {
        // Hamburger Menu Popup
        btnMenu.setOnClickListener { overlayMenuPopup.visibility = View.VISIBLE }
        btnCloseMenu.setOnClickListener { overlayMenuPopup.visibility = View.GONE }
        overlayMenuPopup.setOnClickListener { overlayMenuPopup.visibility = View.GONE }

        switchWinkRestore.setOnCheckedChangeListener { _, isChecked ->
            switchWinkMax.isEnabled = isChecked
        }

        // Preset Popup
        btnPresetPicker.setOnClickListener { overlayPresetPopup.visibility = View.VISIBLE }
        btnClosePreset.setOnClickListener { overlayPresetPopup.visibility = View.GONE }
        overlayPresetPopup.setOnClickListener { overlayPresetPopup.visibility = View.GONE }

        cardPreset30.setOnClickListener { selectPreset(StatusPreset.CLIP_30S_ULTRA) }
        cardPreset45.setOnClickListener { selectPreset(StatusPreset.CLIP_45S_HD) }
        cardPreset60.setOnClickListener { selectPreset(StatusPreset.CLIP_60S_FULL) }

        // Bottom-right '+' FAB
        fabPickVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        // Before / Split / After tabs
        tabOriginal.setOnClickListener { switchCompareMode(CompareMode.ORIGINAL) }
        tabSplit.setOnClickListener { switchCompareMode(CompareMode.SPLIT) }
        tabEnhanced.setOnClickListener { switchCompareMode(CompareMode.ENHANCED) }

        // Split slider
        seekBarSplit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applySplitClip(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Play / Pause button
        btnPlayPause.setOnClickListener {
            if (compareMode == CompareMode.SPLIT) {
                switchCompareMode(CompareMode.ENHANCED)
                return@setOnClickListener
            }
            togglePlayback()
        }

        // Video Timeline Scrubber
        seekBarTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val targetMs = (activeVideoDurationMs * (progress / 1000.0)).toLong()
                tvCurrentTime.text = formatTimeMs(targetMs)
                if (compareMode != CompareMode.SPLIT && videoPreview.visibility == View.VISIBLE) {
                    try {
                        videoPreview.seekTo(targetMs.toInt())
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isScrubbingTimeline = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isScrubbingTimeline = false
                if (compareMode == CompareMode.SPLIT) {
                    val targetMs = (activeVideoDurationMs * ((seekBar?.progress ?: 0) / 1000.0)).toLong()
                    loadSplitComparisonFrames(targetMs)
                }
            }
        })

        // Trim start slider
        seekBarTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTrimWindowFromSlider(progress, seekPreview = fromUser)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Export button
        btnOptimize.setOnClickListener {
            selectedVideoUri?.let { executeOptimization(it) }
        }

        btnReExport.setOnClickListener {
            selectedVideoUri?.let { executeOptimization(it) }
        }

        btnOpenSharePopup.setOnClickListener {
            if (outputVideoFile != null) {
                overlayResultPopup.visibility = View.VISIBLE
            }
        }

        // Result popup actions
        btnCloseResult.setOnClickListener { overlayResultPopup.visibility = View.GONE }
        overlayResultPopup.setOnClickListener { overlayResultPopup.visibility = View.GONE }

        btnCompareNow.setOnClickListener {
            overlayResultPopup.visibility = View.GONE
            switchCompareMode(CompareMode.SPLIT)
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
        tvPresetPill.text = preset.pillLabel
        cardPreset30.setBackgroundResource(
            if (preset == StatusPreset.CLIP_30S_ULTRA) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        cardPreset45.setBackgroundResource(
            if (preset == StatusPreset.CLIP_45S_HD) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        cardPreset60.setBackgroundResource(
            if (preset == StatusPreset.CLIP_60S_FULL) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        overlayPresetPopup.visibility = View.GONE
        seekBarTrimStart.progress = 0
        updateTrimWindowFromSlider(0, seekPreview = true)
    }

    private fun onVideoSelected(uri: Uri) {
        selectedVideoUri = uri
        outputVideoFile = null
        compareMode = CompareMode.ORIGINAL
        layoutCompareBar.visibility = View.GONE
        layoutSplitCompare.visibility = View.GONE
        layoutEmptyState.visibility = View.GONE
        layoutReadyActions.visibility = View.GONE
        btnOptimize.visibility = View.VISIBLE
        videoPreview.visibility = View.VISIBLE
        btnOptimize.isEnabled = true
        btnPlayPause.isEnabled = true
        seekBarTimeline.isEnabled = true

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
            tvSourceSpecs.visibility = View.VISIBLE
            tvSourceSpecs.text = String.format(Locale.US, "%dx%d • %.1f MB", sourceWidth, sourceHeight, sizeMb)
        } catch (_: Exception) {
            sourceWidth = 0
            sourceHeight = 0
            sourceDurationMs = 0L
            sourceBitrateBps = 0L
            tvSourceSpecs.visibility = View.GONE
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        activeVideoDurationMs = sourceDurationMs
        tvTotalTime.text = formatTimeMs(sourceDurationMs)
        tvCurrentTime.text = "0:00"
        seekBarTimeline.progress = 0
        seekBarTrimStart.progress = 0
        updateTrimWindowFromSlider(0, seekPreview = false)

        loadVideoIntoPlayer(uri = uri, filePath = null, seekToMs = 0L, autoPlay = true)
    }

    private fun loadVideoIntoPlayer(uri: Uri?, filePath: String?, seekToMs: Long, autoPlay: Boolean) {
        layoutSplitCompare.visibility = View.GONE
        videoPreview.visibility = View.VISIBLE

        if (filePath != null) {
            videoPreview.setVideoPath(filePath)
        } else if (uri != null) {
            videoPreview.setVideoURI(uri)
        }

        videoPreview.setOnPreparedListener { mp ->
            mp.isLooping = true
            activeVideoDurationMs = max(1L, mp.duration.toLong())
            tvTotalTime.text = formatTimeMs(activeVideoDurationMs)
            val safeSeek = seekToMs.coerceIn(0L, activeVideoDurationMs)
            videoPreview.seekTo(safeSeek.toInt())
            if (autoPlay) {
                videoPreview.start()
                isVideoPlaying = true
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            } else {
                videoPreview.pause()
                isVideoPlaying = false
                btnPlayPause.setImageResource(R.drawable.ic_play)
            }
        }
    }

    private fun togglePlayback() {
        if (videoPreview.visibility != View.VISIBLE) return
        if (isVideoPlaying) {
            videoPreview.pause()
            isVideoPlaying = false
            btnPlayPause.setImageResource(R.drawable.ic_play)
        } else {
            videoPreview.start()
            isVideoPlaying = true
            btnPlayPause.setImageResource(R.drawable.ic_pause)
        }
    }

    private fun switchCompareMode(mode: CompareMode) {
        val outFile = outputVideoFile ?: return
        val srcUri = selectedVideoUri ?: return

        val currentPosMs = try {
            if (compareMode != CompareMode.SPLIT && videoPreview.visibility == View.VISIBLE) {
                videoPreview.currentPosition.toLong()
            } else {
                (activeVideoDurationMs * (seekBarTimeline.progress / 1000.0)).toLong()
            }
        } catch (_: Exception) {
            0L
        }

        compareMode = mode
        updateCompareTabStyles(mode)

        when (mode) {
            CompareMode.ORIGINAL -> {
                val origSeek = (trimStartMs + currentPosMs).coerceAtMost(max(0L, sourceDurationMs - 100L))
                loadVideoIntoPlayer(uri = srcUri, filePath = null, seekToMs = origSeek, autoPlay = true)
            }
            CompareMode.ENHANCED -> {
                loadVideoIntoPlayer(uri = null, filePath = outFile.absolutePath, seekToMs = currentPosMs, autoPlay = true)
            }
            CompareMode.SPLIT -> {
                if (isVideoPlaying) {
                    videoPreview.pause()
                    isVideoPlaying = false
                    btnPlayPause.setImageResource(R.drawable.ic_play)
                }
                videoPreview.visibility = View.GONE
                layoutSplitCompare.visibility = View.VISIBLE
                loadSplitComparisonFrames(currentPosMs)
            }
        }
    }

    private fun updateCompareTabStyles(mode: CompareMode) {
        val tabs = listOf(
            tabOriginal to CompareMode.ORIGINAL,
            tabSplit to CompareMode.SPLIT,
            tabEnhanced to CompareMode.ENHANCED
        )
        for ((tv, m) in tabs) {
            if (m == mode) {
                tv.setBackgroundResource(R.drawable.bg_pill_active)
                tv.setTextColor(0xFFFFFFFF.toInt())
            } else {
                tv.background = null
                tv.setTextColor(0xFF94A3B8.toInt())
            }
        }
    }

    private fun loadSplitComparisonFrames(relativePosMs: Long) {
        val srcUri = selectedVideoUri ?: return
        val outFile = outputVideoFile ?: return

        val origTimeUs = (trimStartMs + relativePosMs) * 1000L
        val enhTimeUs = relativePosMs * 1000L

        thread(start = true) {
            var beforeBmp: Bitmap? = null
            var afterBmp: Bitmap? = null
            val retOrig = MediaMetadataRetriever()
            val retEnh = MediaMetadataRetriever()
            try {
                retOrig.setDataSource(this, srcUri)
                beforeBmp = retOrig.getFrameAtTime(origTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retOrig.getFrameAtTime(origTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
            } finally {
                try { retOrig.release() } catch (_: Exception) {}
            }

            try {
                retEnh.setDataSource(outFile.absolutePath)
                afterBmp = retEnh.getFrameAtTime(enhTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retEnh.getFrameAtTime(enhTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
            } finally {
                try { retEnh.release() } catch (_: Exception) {}
            }

            mainHandler.post {
                if (beforeBmp != null) ivBeforeFrame.setImageBitmap(beforeBmp)
                if (afterBmp != null) ivAfterFrame.setImageBitmap(afterBmp)
                layoutSplitCompare.post {
                    applySplitClip(seekBarSplit.progress)
                }
            }
        }
    }

    private fun applySplitClip(progressPercent: Int) {
        val w = layoutSplitCompare.width
        val h = layoutSplitCompare.height
        if (w <= 0 || h <= 0) return
        val splitX = (w * (progressPercent / 100.0)).toInt().coerceIn(0, w)
        ivBeforeFrame.clipBounds = Rect(0, 0, splitX, h)
        viewSplitLine.translationX = splitX.toFloat()
    }

    private fun updateTrimWindowFromSlider(progress: Int, seekPreview: Boolean) {
        val totalMs = sourceDurationMs
        val maxClipMs = currentPreset.maxClipDurationMs

        if (totalMs <= 0L) {
            trimStartMs = 0L
            trimEndMs = maxClipMs
            seekBarTrimStart.isEnabled = false
            tvTrimWindow.text = "Trim: 0:00 – ${formatTimeMs(maxClipMs)}"
            return
        }

        if (totalMs <= maxClipMs) {
            trimStartMs = 0L
            trimEndMs = totalMs
            seekBarTrimStart.isEnabled = false
            tvTrimWindow.text = "Trim: 0:00 – ${formatTimeMs(totalMs)} (Full)"
        } else {
            seekBarTrimStart.isEnabled = true
            val maxStartMs = totalMs - maxClipMs
            trimStartMs = (maxStartMs * (progress / 100.0)).toLong()
            trimEndMs = min(totalMs, trimStartMs + maxClipMs)
            tvTrimWindow.text = String.format(
                Locale.US,
                "Trim: %s – %s",
                formatTimeMs(trimStartMs),
                formatTimeMs(trimEndMs)
            )
            if (seekPreview && videoPreview.visibility == View.VISIBLE && compareMode == CompareMode.ORIGINAL) {
                try {
                    videoPreview.seekTo(trimStartMs.toInt())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun canUseLosslessDirectCopy(): Boolean {
        if (switchWinkRestore.isChecked) return false
        if (sourceWidth <= 0 || sourceHeight <= 0 || sourceDurationMs <= 0L) return false
        val longSide = max(sourceWidth, sourceHeight)
        if (longSide > currentPreset.maxLongSidePx) return false
        if (switchRotateVertical.isChecked || switchHdrToneMap.isChecked) return false

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
        setBusyUi(true, "Trimming losslessly...")
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
                        trackMap[i] = muxer.addTrack(format)
                        videoTrackIndex = i
                        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                        }
                    } else if (mime.startsWith("audio/") && !removeAudio) {
                        extractor.selectTrack(i)
                        trackMap[i] = muxer.addTrack(format)
                    }
                }

                if (videoTrackIndex == -1) throw IllegalStateException("No video track")
                muxer.start()
                if (startUs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                val bufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs > endUs) break

                    val dstTrack = trackMap[extractor.sampleTrackIndex]
                    if (dstTrack != null && sampleTimeUs >= startUs - 500_000L) {
                        bufferInfo.presentationTimeUs = max(0L, sampleTimeUs - startUs)
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                    }
                    extractor.advance()
                }
                muxer.stop()
                mainHandler.post {
                    onExportFinished(outFile, sourceBitrateBps.toInt(), null)
                }
            } catch (_: Exception) {
                mainHandler.post {
                    if (outFile.exists()) outFile.delete()
                    startStatHdTranscode(inputUri, outFile, allowHdrToneMap = switchHdrToneMap.isChecked)
                }
            } finally {
                try { extractor?.release() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun computeSafeVideoBitrateBps(): Int {
        val clipDurationSec = if (sourceDurationMs > 0L && trimEndMs > trimStartMs) {
            max(1.0, (trimEndMs - trimStartMs) / 1000.0)
        } else {
            currentPreset.maxClipDurationMs / 1000.0
        }

        val audioBitrateBps = if (switchMuteAudio.isChecked) 0 else 128_000
        val maxTotalBits = 10.2 * 1024.0 * 1024.0 * 8.0
        val maxSafeVideoBps = ((maxTotalBits / clipDurationSec) - audioBitrateBps).toInt()
        return min(currentPreset.maxBitrateBps, max(900_000, maxSafeVideoBps))
    }

    private fun startStatHdTranscode(inputUri: Uri, outFile: File, allowHdrToneMap: Boolean) {
        val targetBitrateBps = computeSafeVideoBitrateBps()
        val targetMbps = targetBitrateBps / 1_000_000.0
        setBusyUi(true, String.format(Locale.US, "Enhancing video (%.1f Mbps)... 0%%", targetMbps))

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

        if (switchWinkRestore.isChecked) {
            if (switchWinkMax.isChecked) {
                videoEffects.add(WinkUltraHdEffect(sharpnessStrength = 1.05f, denoiseStrength = 0.0f, vibranceBoost = 0.12f))
            } else {
                videoEffects.add(WinkUltraHdEffect(sharpnessStrength = 0.72f, denoiseStrength = 0.0f, vibranceBoost = 0.08f))
            }
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(inputUri)
        if (sourceDurationMs > 0L && (trimStartMs > 0L || trimEndMs < sourceDurationMs)) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(trimEndMs)
                    .build()
            )
        }

        val editedMediaItem = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(Effects(emptyList(), videoEffects))
            .setRemoveAudio(switchMuteAudio.isChecked)
            .build()

        val compositionBuilder = Composition.Builder(EditedMediaItemSequence(editedMediaItem))
        if (allowHdrToneMap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            compositionBuilder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC)
        }

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
                    onExportFinished(outFile, targetBitrateBps, targetDims)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (allowHdrToneMap) {
                        startStatHdTranscode(inputUri, outFile, allowHdrToneMap = false)
                        return
                    }
                    setBusyUi(false, "")
                    Toast.makeText(
                        this@MainActivity,
                        "Export failed: ${exportException.localizedMessage ?: "Please try again"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
            .build()

        activeTransformer = transformer
        transformer.start(compositionBuilder.build(), outFile.absolutePath)
        pollTransformerProgress(transformer, targetMbps)
    }

    private fun pollTransformerProgress(transformer: Transformer, targetMbps: Double) {
        val runnable = object : Runnable {
            override fun run() {
                if (activeTransformer !== transformer) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress
                    progressBar.progress = pct
                    tvProgressStatus.text = String.format(Locale.US, "Enhancing video (%.1f Mbps)... %d%%", targetMbps, pct)
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    mainHandler.postDelayed(this, 150L)
                }
            }
        }
        mainHandler.post(runnable)
    }

    private fun setBusyUi(busy: Boolean, message: String) {
        btnOptimize.isEnabled = !busy
        btnReExport.isEnabled = !busy
        btnOpenSharePopup.isEnabled = !busy
        fabPickVideo.isEnabled = !busy
        if (busy) {
            layoutReadyActions.visibility = View.GONE
            btnOptimize.visibility = View.VISIBLE
            progressBar.progress = 0
            tvProgressStatus.text = message
        }
        layoutProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun onExportFinished(outFile: File, targetBitrateBps: Int, targetDims: Pair<Int, Int>?) {
        activeTransformer = null
        setBusyUi(false, "")
        btnOptimize.visibility = View.GONE
        layoutReadyActions.visibility = View.VISIBLE

        val newSizeMb = outFile.length() / (1024.0 * 1024.0)
        val effW = if (switchRotateVertical.isChecked) sourceHeight else sourceWidth
        val effH = if (switchRotateVertical.isChecked) sourceWidth else sourceHeight
        val outW = targetDims?.first ?: effW
        val outH = targetDims?.second ?: effH
        val targetMbps = targetBitrateBps / 1_000_000.0

        tvResultSpecs.text = String.format(
            Locale.US,
            "%dx%d • %.2f MB • %.1f Mbps H.264",
            outW,
            outH,
            newSizeMb,
            targetMbps
        )

        layoutCompareBar.visibility = View.VISIBLE
        switchCompareMode(CompareMode.ENHANCED)
        overlayResultPopup.visibility = View.VISIBLE
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
                "Share video to WhatsApp"
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
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val itemUri = contentResolver.insert(collection, values)
            if (itemUri != null) {
                contentResolver.openOutputStream(itemUri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(itemUri, values, null, null)
                }
                Toast.makeText(this, "Saved to Movies/StatHD/$fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryFileSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) cursor.getLong(sizeIndex) else 0L
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
