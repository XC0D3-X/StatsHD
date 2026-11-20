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

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    enum class StatusPreset(
        val label: String,
        val shortSidePx: Int,
        val videoBitrateBps: Int
    ) {
        PURE_720P("Pure 720p HD (2.2 Mbps)", 720, 2_200_000),
        ULTRA_1080P("Ultra 1080p FHD (3.2 Mbps)", 1080, 3_200_000),
        LONG_60S("Long Status 60s (1.5 Mbps)", 720, 1_500_000)
    }

    private lateinit var videoPreview: VideoView
    private lateinit var tvEmptyPreview: TextView
    private lateinit var tvSourceSpecs: TextView
    private lateinit var btnPickVideo: MaterialButton
    private lateinit var cardPreset720: LinearLayout
    private lateinit var cardPreset1080: LinearLayout
    private lateinit var cardPreset60s: LinearLayout
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
    private var currentPreset: StatusPreset = StatusPreset.PURE_720P
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
        btnPickVideo = findViewById(R.id.btnPickVideo)
        cardPreset720 = findViewById(R.id.cardPreset720)
        cardPreset1080 = findViewById(R.id.cardPreset1080)
        cardPreset60s = findViewById(R.id.cardPreset60s)
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

        cardPreset720.setOnClickListener { selectPreset(StatusPreset.PURE_720P) }
        cardPreset1080.setOnClickListener { selectPreset(StatusPreset.ULTRA_1080P) }
        cardPreset60s.setOnClickListener { selectPreset(StatusPreset.LONG_60S) }

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
            if (preset == StatusPreset.PURE_720P) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        cardPreset1080.setBackgroundResource(
            if (preset == StatusPreset.ULTRA_1080P) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
        cardPreset60s.setBackgroundResource(
            if (preset == StatusPreset.LONG_60S) R.drawable.bg_preset_selected else R.drawable.bg_preset_unselected
        )
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
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L

            val sizeMb = sourceSizeBytes / (1024.0 * 1024.0)
            val durationSec = durationMs / 1000.0
            val bitrateMbps = bitrateBps / 1_000_000.0

            tvSourceSpecs.text = String.format(
                Locale.US,
                "Source: %dx%d | %.2f MB | %.1fs | %.1f Mbps",
                width,
                height,
                sizeMb,
                durationSec,
                bitrateMbps
            )
        } catch (e: Exception) {
            tvSourceSpecs.text = "Video loaded • Ready for StatHD optimization"
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun startStatHdTranscode(inputUri: Uri, allowHdrToneMap: Boolean) {
        btnOptimize.isEnabled = false
        btnPickVideo.isEnabled = false
        cardResult.visibility = View.GONE
        layoutProgress.visibility = View.VISIBLE
        progressBar.progress = 0
        tvProgressStatus.text = "Initializing Hardware H.264 Encoder (${currentPreset.label})..."

        val outDir = File(cacheDir, "stathd_exports").apply { mkdirs() }
        val outFile = File(outDir, "StatHD_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()
        outputVideoFile = outFile

        // 1. Configure Video Effects (Resolution Downscale + Crisp Micro-Contrast)
        val videoEffects = mutableListOf<Effect>()
        videoEffects.add(Presentation.createForHeight(currentPreset.shortSidePx))
        if (switchCrispBoost.isChecked) {
            // Subtle micro-contrast enhancement so downscaled text & edges look sharper on WhatsApp Status
            videoEffects.add(Contrast(0.08f))
        }

        val mediaItem = MediaItem.fromUri(inputUri)
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .setRemoveAudio(switchMuteAudio.isChecked)
            .build()

        val compositionBuilder = Composition.Builder(EditedMediaItemSequence(editedMediaItem))
        if (allowHdrToneMap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            compositionBuilder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC)
        }
        val composition = compositionBuilder.build()

        // 2. Configure Bitrate-Locked Hardware H.264 Encoder
        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(currentPreset.videoBitrateBps)
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
                    onTranscodeSuccess(outFile)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    mainHandler.removeCallbacksAndMessages(null)
                    // Automatic fallback if device hardware doesn't support explicit HDR tone-mapping mode
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
        pollTransformerProgress(transformer)
    }

    private fun pollTransformerProgress(transformer: Transformer) {
        val runnable = object : Runnable {
            override fun run() {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress
                    progressBar.progress = pct
                    tvProgressStatus.text = "Encoding H.264 Bitrate-Lock (${currentPreset.label})... $pct%"
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    mainHandler.postDelayed(this, 150L)
                }
            }
        }
        mainHandler.post(runnable)
    }

    private fun onTranscodeSuccess(outFile: File) {
        btnOptimize.isEnabled = true
        btnPickVideo.isEnabled = true
        progressBar.progress = 100
        layoutProgress.visibility = View.GONE
        cardResult.visibility = View.VISIBLE

        val newSizeBytes = outFile.length()
        val newSizeMb = newSizeBytes / (1024.0 * 1024.0)
        val oldSizeMb = sourceSizeBytes / (1024.0 * 1024.0)
        val targetMbps = currentPreset.videoBitrateBps / 1_000_000.0

        val savedPct = if (sourceSizeBytes > 0 && newSizeBytes < sourceSizeBytes) {
            ((sourceSizeBytes - newSizeBytes) * 100.0 / sourceSizeBytes)
        } else {
            0.0
        }

        tvResultSpecs.text = String.format(
            Locale.US,
            "Preset: %s\nOriginal: %.2f MB ➔ StatHD: %.2f MB (Saved %.0f%%)\nCodec: H.264 (avc1) | Bitrate: %.1f Mbps | Status: WA BYPASS READY",
            currentPreset.label,
            oldSizeMb,
            newSizeMb,
            savedPct,
            targetMbps
        )

        // Update preview to play the newly optimized StatHD video
        videoPreview.setVideoPath(outFile.absolutePath)
        videoPreview.start()
        Toast.makeText(this, "StatHD Master Ready for WhatsApp Status!", Toast.LENGTH_SHORT).show()
    }

    private fun onTranscodeError(error: ExportException) {
        btnOptimize.isEnabled = true
        btnPickVideo.isEnabled = true
        layoutProgress.visibility = View.GONE
        Toast.makeText(
            this,
            "Encoding error: ${error.localizedMessage ?: "Please try the Pure 720p preset"}",
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
            // Fallback to general share chooser if standard WhatsApp package isn't installed (e.g. WA Business)
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

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        activeTransformer?.cancel()
    }
}
