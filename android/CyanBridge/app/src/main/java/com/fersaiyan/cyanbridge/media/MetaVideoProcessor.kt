package com.fersaiyan.cyanbridge.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

enum class ColorPreset(val label: String) {
    ORIGINAL("Оригинал"),
    META_DAY("Чистый день"),
    NIGHT_CAR("Вечер / Авто"),
}

data class MediaProcessingOptions(
    val colorPreset: ColorPreset = ColorPreset.ORIGINAL,
    val upscale2K: Boolean = false,
    val stabilize: Boolean = false,
    val deleteDraftAfterSave: Boolean = false,
) {
    val requiresVideoReencode: Boolean
        get() = colorPreset != ColorPreset.ORIGINAL || upscale2K || stabilize
}

@OptIn(UnstableApi::class)
class MetaVideoProcessor(private val context: Context) {

    /**
     * Сохраняет видео в Галерею (Movies/AiBuds).
     * В режиме «Оригинал» без 2K и стабилизации копирует исходник 1 в 1 без перекодирования.
     */
    suspend fun exportVideoToGallery(
        inputFile: File,
        options: MediaProcessingOptions,
        onProgress: (Int) -> Unit = {},
    ): Uri {
        val tempOut = if (options.requiresVideoReencode) {
            File(context.cacheDir, "proc_${System.currentTimeMillis()}_${inputFile.name}")
        } else {
            null
        }

        try {
            val fileToSave = if (tempOut == null) {
                onProgress(50)
                inputFile
            } else {
                val stabTrack = if (options.stabilize) {
                    withContext(Dispatchers.Default) {
                        VideoMotionStabilizer.analyze(inputFile) { analysisPct ->
                            onProgress((analysisPct * 35) / 100)
                        }
                    }
                } else {
                    null
                }

                val progressOffset = if (options.stabilize) 35 else 0
                val progressSpan = 99 - progressOffset
                renderVideo(inputFile, tempOut, options, stabTrack) { renderPct ->
                    val scaled = progressOffset + (renderPct * progressSpan) / 100
                    onProgress(scaled.coerceIn(progressOffset, 99))
                }
                tempOut
            }

            val galleryUri = withContext(Dispatchers.IO) {
                saveFileToGallery(
                    file = fileToSave,
                    mimeType = "video/mp4",
                    isVideo = true,
                    displayName = buildOutputName(inputFile.nameWithoutExtension, "mp4", options),
                )
            }

            if (options.deleteDraftAfterSave && inputFile.exists()) {
                inputFile.delete()
            }

            onProgress(100)
            return galleryUri
        } finally {
            if (tempOut != null && tempOut.exists()) {
                tempOut.delete()
            }
        }
    }

    /**
     * Сохраняет фото в Галерею (Pictures/AiBuds).
     * В режиме «Оригинал» без 2K копирует исходный JPEG без потерь.
     */
    suspend fun exportPhotoToGallery(
        inputFile: File,
        options: MediaProcessingOptions,
    ): Uri = withContext(Dispatchers.IO) {
        val tempOut = if (options.colorPreset == ColorPreset.ORIGINAL && !options.upscale2K) {
            null
        } else {
            val raw = BitmapFactory.decodeFile(inputFile.absolutePath)
                ?: throw IllegalStateException("Не удалось открыть фото: ${inputFile.name}")
            val processed = applyPhotoEffects(raw, options)
            if (processed !== raw) raw.recycle()

            File(context.cacheDir, "proc_${System.currentTimeMillis()}.jpg").also { outFile ->
                try {
                    FileOutputStream(outFile).use { out ->
                        processed.compress(Bitmap.CompressFormat.JPEG, 97, out)
                    }
                } finally {
                    processed.recycle()
                }
            }
        }

        try {
            val galleryUri = saveFileToGallery(
                file = tempOut ?: inputFile,
                mimeType = "image/jpeg",
                isVideo = false,
                displayName = buildOutputName(inputFile.nameWithoutExtension, "jpg", options),
            )
            if (options.deleteDraftAfterSave && inputFile.exists()) {
                inputFile.delete()
            }
            galleryUri
        } finally {
            if (tempOut != null && tempOut.exists()) {
                tempOut.delete()
            }
        }
    }

    fun applyPhotoEffects(source: Bitmap, options: MediaProcessingOptions): Bitmap {
        val targetBitmap = if (options.upscale2K && source.height < 1536) {
            val scale = 1536f / source.height.toFloat()
            val targetW = (source.width * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, targetW, 1536, true)
        } else {
            source
        }

        val matrix = buildPhotoColorMatrix(options.colorPreset) ?: return targetBitmap
        val out = Bitmap.createBitmap(targetBitmap.width, targetBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(targetBitmap, 0f, 0f, paint)
        if (targetBitmap !== source) {
            targetBitmap.recycle()
        }
        return out
    }

    private suspend fun renderVideo(
        inputFile: File,
        outputFile: File,
        options: MediaProcessingOptions,
        stabTrack: StabilizationTrack?,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val videoEffects = buildVideoEffects(options, stabTrack)
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile)))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val targetBitrate = withContext(Dispatchers.IO) {
            calculateTargetBitrate(inputFile, options.upscale2K)
        }

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(targetBitrate)
                    .build(),
            )
            .setEnableFallback(true)
            .build()

        suspendCancellableCoroutine<Unit> { cont ->
            val progressHolder = ProgressHolder()
            var progressJob: Job? = null

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            progressJob?.cancel()
                            onProgress(100)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            progressJob?.cancel()
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    },
                )
                .build()

            cont.invokeOnCancellation {
                progressJob?.cancel()
                transformer.cancel()
            }

            transformer.start(editedMediaItem, outputFile.absolutePath)

            progressJob = CoroutineScope(Dispatchers.Main).launch {
                while (isActive) {
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progressHolder.progress.coerceIn(0, 99))
                    }
                    delay(150L)
                }
            }
        }
    }

    private fun calculateTargetBitrate(inputFile: File, upscale2K: Boolean): Int {
        val mmr = MediaMetadataRetriever()
        val sourceBitrate = try {
            mmr.setDataSource(inputFile.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { mmr.release() }
        }

        val minTarget = if (upscale2K) 28_000_000 else 18_000_000
        val boostedSource = if (sourceBitrate > 0) (sourceBitrate * 1.4f).toInt() else 0
        return maxOf(minTarget, boostedSource).coerceAtMost(45_000_000)
    }

    private fun buildVideoEffects(
        options: MediaProcessingOptions,
        stabTrack: StabilizationTrack?,
    ): List<Effect> {
        val effects = mutableListOf<Effect>()

        when (options.colorPreset) {
            ColorPreset.ORIGINAL -> Unit
            ColorPreset.META_DAY -> {
                // Убираем паразитный зелёный оттенок матрицы + мягкий естественный контраст
                effects += RgbAdjustment.Builder()
                    .setRedScale(1.03f)
                    .setGreenScale(0.985f)
                    .setBlueScale(1.01f)
                    .build()
                effects += Contrast(0.06f)
                effects += HslAdjustment.Builder()
                    .adjustSaturation(5f)
                    .build()
            }
            ColorPreset.NIGHT_CAR -> {
                // Деликатное осветление без вытягивания цветного шума и постеризации
                effects += RgbAdjustment.Builder()
                    .setRedScale(1.02f)
                    .setGreenScale(0.98f)
                    .setBlueScale(1.02f)
                    .build()
                effects += Brightness(0.035f)
                effects += Contrast(0.04f)
                effects += HslAdjustment.Builder()
                    .adjustSaturation(3f)
                    .build()
            }
        }

        if (stabTrack != null) {
            effects += stabTrack.toMatrixTransformation()
        }

        if (options.upscale2K) {
            effects += Presentation.createForHeight(1536)
        }

        return effects
    }

    private fun buildPhotoColorMatrix(preset: ColorPreset): ColorMatrix? = when (preset) {
        ColorPreset.ORIGINAL -> null
        ColorPreset.META_DAY -> {
            val c = 1.06f
            val t = (-0.5f * c + 0.5f) * 255f
            val cm = ColorMatrix(
                floatArrayOf(
                    c * 1.03f, 0f, 0f, 0f, t,
                    0f, c * 0.985f, 0f, 0f, t,
                    0f, 0f, c * 1.01f, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            val sat = ColorMatrix().apply { setSaturation(1.06f) }
            cm.postConcat(sat)
            cm
        }
        ColorPreset.NIGHT_CAR -> {
            val c = 1.04f
            val lift = 6f
            val cm = ColorMatrix(
                floatArrayOf(
                    c * 1.02f, 0f, 0f, 0f, lift,
                    0f, c * 0.98f, 0f, 0f, lift,
                    0f, 0f, c * 1.02f, 0f, lift,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            val sat = ColorMatrix().apply { setSaturation(1.04f) }
            cm.postConcat(sat)
            cm
        }
    }

    private fun buildOutputName(base: String, ext: String, options: MediaProcessingOptions): String {
        val tags = buildList {
            if (options.colorPreset != ColorPreset.ORIGINAL) add(options.colorPreset.name.lowercase())
            if (options.stabilize) add("eis")
            if (options.upscale2K) add("2k")
        }
        val suffix = if (tags.isEmpty()) "" else "_" + tags.joinToString("_")
        return "${base}${suffix}.$ext"
    }

    private fun saveFileToGallery(
        file: File,
        mimeType: String,
        isVideo: Boolean,
        displayName: String,
    ): Uri {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val relativePath = if (isVideo) {
            "${Environment.DIRECTORY_MOVIES}/AiBuds"
        } else {
            "${Environment.DIRECTORY_PICTURES}/AiBuds"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Не удалось создать запись в Галерее")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Не удалось открыть поток записи в Галерею")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (err: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw err
        }
    }
}

/**
 * Готовая таблица компенсации тряски по таймкодам (в NDC-координатах Media3).
 */
@OptIn(UnstableApi::class)
private class StabilizationTrack(
    private val ptsUs: LongArray,
    private val txNdc: FloatArray,
    private val tyNdc: FloatArray,
    private val zoom: Float,
) {
    fun toMatrixTransformation(): MatrixTransformation = object : MatrixTransformation {
        override fun getMatrix(presentationTimeUs: Long): Matrix {
            val (tx, ty) = sample(presentationTimeUs)
            return Matrix().apply {
                postScale(zoom, zoom)
                postTranslate(tx, ty)
            }
        }
    }

    private fun sample(timeUs: Long): Pair<Float, Float> {
        val n = ptsUs.size
        if (n == 0) return 0f to 0f
        if (n == 1 || timeUs <= ptsUs[0]) return txNdc[0] to tyNdc[0]
        if (timeUs >= ptsUs[n - 1]) return txNdc[n - 1] to tyNdc[n - 1]

        val idx = ptsUs.binarySearch(timeUs)
        if (idx >= 0) return txNdc[idx] to tyNdc[idx]

        val right = (-idx - 1).coerceIn(1, n - 1)
        val left = right - 1
        val dt = (ptsUs[right] - ptsUs[left]).coerceAtLeast(1L)
        val alpha = ((timeUs - ptsUs
