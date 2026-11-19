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
        val alpha = ((timeUs - ptsUs[left]).toFloat() / dt.toFloat()).coerceIn(0f, 1f)
        val tx = txNdc[left] + (txNdc[right] - txNdc[left]) * alpha
        val ty = tyNdc[left] + (tyNdc[right] - tyNdc[left]) * alpha
        return tx to ty
    }
}

/**
 * 2-проходной анализатор движения: декодирует Y-канал через аппаратный MediaCodec,
 * оценивает сдвиг (dx, dy) между соседними кадрами и сглаживает траекторию фильтром Гаусса.
 */
private object VideoMotionStabilizer {
    private const val TAG = "VideoMotionStabilizer"
    private const val GRID_W = 80
    private const val GRID_H = 45
    private const val MAX_SEARCH = 8
    private const val SMOOTH_RADIUS = 16
    private const val SMOOTH_SIGMA = 7.5f
    private const val ZOOM = 1.12f

    suspend fun analyze(
        inputFile: File,
        onProgress: (Int) -> Unit,
    ): StabilizationTrack? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(inputFile.absolutePath)
            var videoTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    format = f
                    break
                }
            }
            if (videoTrack < 0 || format == null) return null

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = readDurationUs(format, inputFile)
            val rotation = readRotation(format, inputFile)

            extractor.selectTrack(videoTrack)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val ptsList = ArrayList<Long>(512)
            val dxList = ArrayList<Float>(512)
            val dyList = ArrayList<Float>(512)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var prevGrid: IntArray? = null
            var prevHasTexture = false
            var frameCount = 0

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIdx >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                    if (bufferInfo.size > 0) {
                        val image = runCatching { codec.getOutputImage(outIdx) }.getOrNull()
                        if (image != null) {
                            try {
                                val (currGrid, hasTexture) = downsampleYPlane(image, GRID_W, GRID_H)
                                val shift = if (prevGrid != null && prevHasTexture && hasTexture) {
                                    estimateShift(prevGrid, currGrid, GRID_W, GRID_H, MAX_SEARCH)
                                } else {
                                    0f to 0f
                                }
                                ptsList += bufferInfo.presentationTimeUs
                                dxList += shift.first
                                dyList += shift.second
                                prevGrid = currGrid
                                prevHasTexture = hasTexture
                                frameCount++

                                if (durationUs > 0L && frameCount % 5 == 0) {
                                    val pct = ((bufferInfo.presentationTimeUs * 100L) / durationUs)
                                        .toInt()
                                        .coerceIn(0, 99)
                                    onProgress(pct)
                                }
                            } finally {
                                image.close()
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }

            onProgress(100)
            if (ptsList.size < 4) return null
            return buildSmoothedTrack(ptsList, dxList, dyList, rotation)
        } catch (err: Throwable) {
            Log.w(TAG, "EIS analysis failed, skipping stabilization: ${err.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun readDurationUs(format: MediaFormat, file: File): Long {
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            val d = format.getLong(MediaFormat.KEY_DURATION)
            if (d > 0L) return d
        }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun readRotation(format: MediaFormat, file: File): Int {
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            return ((format.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360
        }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            ((rot % 360) + 360) % 360
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun downsampleYPlane(image: Image, targetW: Int, targetH: Int): Pair<IntArray, Boolean> {
        val out = IntArray(targetW * targetH)
        if (image.planes.isEmpty()) return out to false

        val yPlane = image.planes[0]
        val buf = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val w = image.width
        val h = image.height
        val limit = buf.limit()
        if (w <= 0 || h <= 0 || limit <= 0) return out to false

        val stepX = (w / targetW / 2).coerceAtLeast(1)
        val stepY = (h / targetH / 2).coerceAtLeast(1)

        var sum = 0L
        var dst = 0
        for (gy in 0 until targetH) {
            val sy0 = ((gy * h) / targetH).coerceIn(0, h - 1)
            val sy1 = (sy0 + stepY).coerceIn(0, h - 1)
            val row0 = sy0 * rowStride
            val row1 = sy1 * rowStride
            for (gx in 0 until targetW) {
                val sx0 = ((gx * w) / targetW).coerceIn(0, w - 1)
                val sx1 = (sx0 + stepX).coerceIn(0, w - 1)
                val i00 = row0 + sx0 * pixelStride
                val i10 = row0 + sx1 * pixelStride
                val i01 = row1 + sx0 * pixelStride
                val i11 = row1 + sx1 * pixelStride

                val v00 = if (i00 in 0 until limit) buf.get(i00).toInt() and 0xFF else 0
                val v10 = if (i10 in 0 until limit) buf.get(i10).toInt() and 0xFF else v00
                val v01 = if (i01 in 0 until limit) buf.get(i01).toInt() and 0xFF else v00
                val v11 = if (i11 in 0 until limit) buf.get(i11).toInt() and 0xFF else v00
                val avg = (v00 + v10 + v01 + v11) ushr 2
                out[dst++] = avg
                sum += avg
            }
        }

        val mean = (sum / out.size).toInt()
        var madSum = 0L
        for (i in out.indices) {
            val centered = out[i] - mean
            out[i] = centered
            madSum += abs(centered)
        }
        val hasTexture = (madSum / out.size) >= 4L
        return out to hasTexture
    }

    private fun estimateShift(
        prev: IntArray,
        curr: IntArray,
        w: Int,
        h: Int,
        maxShift: Int,
    ): Pair<Float, Float> {
        val margin = maxShift + 2
        val xStart = margin
        val xEnd = w - margin
        val yStart = margin
        val yEnd = h - margin
        if (xEnd <= xStart + 4 || yEnd <= yStart + 4) return 0f to 0f

        fun sad(dx: Int, dy: Int, step: Int): Long {
            var acc = 0L
            var y = yStart
            while (y < yEnd) {
                val pRow = y * w
                val cRow = (y + dy) * w + dx
                var x = xStart
                while (x < xEnd) {
                    acc += abs(curr[cRow + x] - prev[pRow + x])
                    x += step
                }
                y += step
            }
            return acc
        }

        // Грубый поиск (шаг 2)
        var bestX = 0
        var bestY = 0
        var bestCost = Long.MAX_VALUE
        var dy = -maxShift
        while (dy <= maxShift) {
            var dx = -maxShift
            while (dx <= maxShift) {
                val c = sad(dx, dy, step = 2)
                if (c < bestCost) {
                    bestCost = c
                    bestX = dx
                    bestY = dy
                }
                dx += 2
            }
            dy += 2
        }

        // Точный поиск (шаг 1) в окрестности минимума
        val rx0 = (bestX - 2).coerceAtLeast(-maxShift)
        val rx1 = (bestX + 2).coerceAtMost(maxShift)
        val ry0 = (bestY - 2).coerceAtLeast(-maxShift)
        val ry1 = (bestY + 2).coerceAtMost(maxShift)

        bestCost = Long.MAX_VALUE
        for (y in ry0..ry1) {
            for (x in rx0..rx1) {
                val c = sad(x, y, step = 1)
                if (c < bestCost) {
                    bestCost = c
                    bestX = x
                    bestY = y
                }
            }
        }

        val zeroCost = sad(0, 0, step = 1)
        if (bestCost >= (zeroCost * 985L) / 1000L) {
            return 0f to 0f
        }

        // Субпиксельная параболическая интерполяция
        val subX = if (bestX > -maxShift && bestX < maxShift) {
            val left = sad(bestX - 1, bestY, step = 1).toFloat()
            val right = sad(bestX + 1, bestY, step = 1).toFloat()
            val center = bestCost.toFloat()
            val denom = left - 2f * center + right
            if (denom > 1f) (0.5f * (left - right) / denom).coerceIn(-0.5f, 0.5f) else 0f
        } else {
            0f
        }

        val subY = if (bestY > -maxShift && bestY < maxShift) {
            val up = sad(bestX, bestY - 1, step = 1).toFloat()
            val down = sad(bestX, bestY + 1, step = 1).toFloat()
            val center = bestCost.toFloat()
            val denom = up - 2f * center + down
            if (denom > 1f) (0.5f * (up - down) / denom).coerceIn(-0.5f, 0.5f) else 0f
        } else {
            0f
        }

        return (bestX + subX) to (bestY + subY)
    }

    private fun buildSmoothedTrack(
        ptsList: List<Long>,
        dxList: List<Float>,
        dyList: List<Float>,
        rotation: Int,
    ): StabilizationTrack {
        val n = ptsList.size
        val pts = LongArray(n) { ptsList[it] }
        val cumX = FloatArray(n)
        val cumY = FloatArray(n)

        for (i in 1 until n) {
            cumX[i] = cumX[i - 1] + dxList[i]
            cumY[i] = cumY[i - 1] + dyList[i]
        }

        // Гауссово ядро для сглаживания траектории
        val kernelSize = SMOOTH_RADIUS * 2 + 1
        val weights = FloatArray(kernelSize)
        var wSum = 0f
        val twoSigmaSq = 2f * SMOOTH_SIGMA * SMOOTH_SIGMA
        for (k in -SMOOTH_RADIUS..SMOOTH_RADIUS) {
            val w = exp(-(k * k) / twoSigmaSq)
            weights[k + SMOOTH_RADIUS] = w
            wSum += w
        }
        for (i in weights.indices) weights[i] /= wSum

        val txArr = FloatArray(n)
        val tyArr = FloatArray(n)
        val maxNdc = (ZOOM - 1f) * 0.92f

        for (i in 0 until n) {
            var sx = 0f
            var sy = 0f
            for (k in -SMOOTH_RADIUS..SMOOTH_RADIUS) {
                val idx = (i + k).coerceIn(0, n - 1)
                val w = weights[k + SMOOTH_RADIUS]
                sx += cumX[idx] * w
                sy += cumY[idx] * w
            }

            // Компенсация в долях кадра (координаты исходного буфера: +X вправо, +Y вниз)
            val corrXNorm = (sx - cumX[i]) / GRID_W.toFloat()
            val corrYNorm = (sy - cumY[i]) / GRID_H.toFloat()

            // Перевод в экранные оси NDC (+X вправо, +Y вверх) с учётом поворота видео
            val (dispX, dispY) = when (rotation) {
                90 -> -corrYNorm to -corrXNorm
                180 -> -corrXNorm to corrYNorm
                270 -> corrYNorm to corrXNorm
                else -> corrXNorm to -corrYNorm
            }

            val rawTx = dispX * 2f * ZOOM
            val rawTy = dispY * 2f * ZOOM

            // Мягкое ограничение, чтобы не вылезли чёрные поля
            txArr[i] = (maxNdc * tanh((rawTx / maxNdc).toDouble())).toFloat()
            tyArr[i] = (maxNdc * tanh((rawTy / maxNdc).toDouble())).toFloat()
        }

        return StabilizationTrack(pts, txArr, tyArr, ZOOM)
    }
}
