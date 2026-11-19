package com.fersaiyan.cyanbridge.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ColorPreset(val label: String) {
    ORIGINAL("Оригинал"),
    META_DAY("Meta День"),
    NIGHT_CAR("Ночь / Авто"),
}

data class MediaProcessingOptions(
    val colorPreset: ColorPreset = ColorPreset.META_DAY,
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
     * Обрабатывает видео по выбранным опциям и сохраняет результат в Галерею (Movies/AiBuds).
     * Если выбран «Оригинал» и выключены апскейл/стаба — копирует мгновенно без перекодирования.
     */
    suspend fun exportVideoToGallery(
        inputFile: File,
        options: MediaProcessingOptions,
        onProgress: (Int) -> Unit = {},
    ): Uri {
        val fileToSave = if (!options.requiresVideoReencode) {
            onProgress(50)
            inputFile
        } else {
            val tempOut = File(context.cacheDir, "proc_${System.currentTimeMillis()}_${inputFile.name}")
            renderVideo(inputFile, tempOut, options, onProgress)
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

        if (fileToSave != inputFile) {
            fileToSave.delete()
        }
        if (options.deleteDraftAfterSave && inputFile.exists()) {
            inputFile.delete()
        }

        onProgress(100)
        return galleryUri
    }

    /**
     * Обрабатывает фото (цветокор + опциональный апскейл 2K) и сохраняет в Галерею (Pictures/AiBuds).
     */
    suspend fun exportPhotoToGallery(
        inputFile: File,
        options: MediaProcessingOptions,
    ): Uri = withContext(Dispatchers.IO) {
        val fileToSave = if (options.colorPreset == ColorPreset.ORIGINAL && !options.upscale2K) {
            inputFile
        } else {
            val raw = BitmapFactory.decodeFile(inputFile.absolutePath)
                ?: throw IllegalStateException("Не удалось открыть фото: ${inputFile.name}")
            val processed = applyPhotoEffects(raw, options)
            if (processed !== raw) raw.recycle()

            val tempOut = File(context.cacheDir, "proc_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempOut).use { out ->
                processed.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            processed.recycle()
            tempOut
        }

        val galleryUri = saveFileToGallery(
            file = fileToSave,
            mimeType = "image/jpeg",
            isVideo = false,
            displayName = buildOutputName(inputFile.nameWithoutExtension, "jpg", options),
        )

        if (fileToSave != inputFile) {
            fileToSave.delete()
        }
        if (options.deleteDraftAfterSave && inputFile.exists()) {
            inputFile.delete()
        }
        galleryUri
    }

    /**
     * Применяет пресет цвета к Bitmap — используется и для экспорта, и для быстрого превью «До / После».
     */
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
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val videoEffects = buildVideoEffects(options)
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile)))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        suspendCancellableCoroutine<Unit> { cont ->
            val progressHolder = ProgressHolder()
            var progressJob: kotlinx.coroutines.Job? = null

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            progressJob?.cancel()
                            onProgress(99)
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
                        onProgress(progressHolder.progress.coerceIn(0, 98))
                    }
                    delay(150L)
                }
            }
        }
    }

    private fun buildVideoEffects(options: MediaProcessingOptions): List<Effect> {
        val effects = mutableListOf<Effect>()

        when (options.colorPreset) {
            ColorPreset.ORIGINAL -> Unit
            ColorPreset.META_DAY -> {
                // Чистый дневной контраст и лёгкая насыщенность без провала в чёрный
                effects += Contrast(0.14f)
                effects += HslAdjustment.Builder()
                    .adjustSaturation(14f)
                    .adjustLightness(2f)
                    .build()
            }
            ColorPreset.NIGHT_CAR -> {
                // Мягкий подъём теней и яркости для сумерек и салона авто
                effects += Brightness(0.12f)
                effects += Contrast(0.06f)
                effects += HslAdjustment.Builder()
                    .adjustSaturation(8f)
                    .adjustLightness(6f)
                    .build()
            }
        }

        if (options.stabilize) {
            // Лёгкий кроп-запас (6%) под сглаживание краёв кадра
            effects += ScaleAndRotateTransformation.Builder()
                .setScale(1.06f, 1.06f)
                .build()
        }

        if (options.upscale2K) {
            // Масштабирование до 2K (высота 1536 при 4:3 -> 2048x1536)
            effects += Presentation.createForHeight(1536)
        }

        return effects
    }

    private fun buildPhotoColorMatrix(preset: ColorPreset): ColorMatrix? = when (preset) {
        ColorPreset.ORIGINAL -> null
        ColorPreset.META_DAY -> {
            val contrast = 1.12f
            val translate = (-0.5f * contrast + 0.5f) * 255f + 4f
            val cm = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            val sat = ColorMatrix().apply { setSaturation(1.14f) }
            cm.postConcat(sat)
            cm
        }
        ColorPreset.NIGHT_CAR -> {
            val contrast = 1.05f
            val lift = 18f
            val cm = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, lift,
                    0f, contrast, 0f, 0f, lift,
                    0f, 0f, contrast, 0f, lift,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            val sat = ColorMatrix().apply { setSaturation(1.08f) }
            cm.postConcat(sat)
            cm
        }
    }

    private fun buildOutputName(base: String, ext: String, options: MediaProcessingOptions): String {
        val tags = buildList {
            if (options.colorPreset != ColorPreset.ORIGINAL) add(options.colorPreset.name.lowercase())
            if (options.upscale2K) add("2k")
            if (options.stabilize) add("stab")
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

        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(file).use { input -> input.copyTo(out) }
        } ?: throw IllegalStateException("Не удалось открыть поток записи в Галерею")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
