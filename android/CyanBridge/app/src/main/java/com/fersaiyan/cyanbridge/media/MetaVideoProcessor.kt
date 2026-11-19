package com.fersaiyan.cyanbridge.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

enum class ColorPreset(val label: String) {
    ORIGINAL("Оригинал"),
    META_DAY("Кино / День"),
    NIGHT_CAR("Ночь / Авто"),
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

private data class GradeParams(
    val contrast: Float,
    val shadowLift: Float,
    val vibrance: Float,
    val sharpness: Float,
    val chromaDenoise: Float,
)

private fun ColorPreset.toGradeParams(): GradeParams? = when (this) {
    ColorPreset.ORIGINAL -> null
    ColorPreset.META_DAY -> GradeParams(
        contrast = 0.24f,
        shadowLift = 0.14f,
        vibrance = 0.22f,
        sharpness = 0.55f,
        chromaDenoise = 0.72f,
    )
    ColorPreset.NIGHT_CAR -> GradeParams(
        contrast = 0.16f,
        shadowLift = 0.34f,
        vibrance = 0.14f,
        sharpness = 0.38f,
        chromaDenoise = 0.90f,
    )
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
    ): Uri = withContext(Dispatchers.Default) {
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
            val galleryUri = withContext(Dispatchers.IO) {
                saveFileToGallery(
                    file = tempOut ?: inputFile,
                    mimeType = "image/jpeg",
                    isVideo = false,
                    displayName = buildOutputName(inputFile.nameWithoutExtension, "jpg", options),
                )
            }
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

    /**
     * Точно тот же конвейер (chroma denoise + CAS luma sharpen + filmic S-curve + smart vibrance),
     * что и в GPU-шейдере видео. Используется и для фото, и для точного превью «До / После».
     */
    fun applyPhotoEffects(source: Bitmap, options: MediaProcessingOptions): Bitmap {
        val working = if (options.upscale2K && source.height < 1536) {
            val scale = 1536f / source.height.toFloat()
            val targetW = (source.width * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, targetW, 1536, true)
        } else {
            source
        }

        val params = options.colorPreset.toGradeParams() ?: return working
        val w = working.width
        val h = working.height
        val size = w * h
        val pixels = IntArray(size)
        working.getPixels(pixels, 0, w, 0, 0, w, h)

        val yArr = FloatArray(size)
        val cbArr = FloatArray(size)
        val crArr = FloatArray(size)
        val inv255 = 1f / 255f

        for (i in 0 until size) {
            val p = pixels[i]
            val r = ((p ushr 16) and 0xFF) * inv255
            val g = ((p ushr 8) and 0xFF) * inv255
            val b = (p and 0xFF) * inv255
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            yArr[i] = y
            cbArr[i] = (b - y) * 0.5389f
            crArr[i] = (r - y) * 0.6350f
        }

        val outPixels = IntArray(size)
        val sigma = 0.07f

        for (row in 0 until h) {
            val rowOff = row * w
            val rowU = max(0, row - 1) * w
            val rowD = min(h - 1, row + 1) * w
            val rowU2 = max(0, row - 2) * w
            val rowD2 = min(h - 1, row + 2) * w

            for (col in 0 until w) {
                val idx = rowOff + col
                val colL = max(0, col - 1)
                val colR = min(w - 1, col + 1)
                val colL2 = max(0, col - 2)
                val colR2 = min(w - 1, col + 2)

                val iL = rowOff + colL
                val iR = rowOff + colR
                val iU = rowU + col
                val iD = rowD + col
                val iL2 = rowOff + colL2
                val iR2 = rowOff + colR2
                val iU2 = rowU2 + col
                val iD2 = rowD2 + col

                val y0 = yArr[idx]
                val yL = yArr[iL]
                val yR = yArr[iR]
                val yU = yArr[iU]
                val yD = yArr[iD]

                // 1. Билатеральное подавление цветового шума (только Cb/Cr)
                val w0 = 1.5f
                val wL = fastWeight(abs(yL - y0) / sigma)
                val wR = fastWeight(abs(yR - y0) / sigma)
                val wU = fastWeight(abs(yU - y0) / sigma)
                val wD = fastWeight(abs(yD - y0) / sigma)
                val wL2 = fastWeight(abs(yArr[iL2] - y0) / sigma) * 0.7f
                val wR2 = fastWeight(abs(yArr[iR2] - y0) / sigma) * 0.7f
                val wU2 = fastWeight(abs(yArr[iU2] - y0) / sigma) * 0.7f
                val wD2 = fastWeight(abs(yArr[iD2] - y0) / sigma) * 0.7f

                val wSum = w0 + wL + wR + wU + wD + wL2 + wR2 + wU2 + wD2
                val avgCb = (cbArr[idx] * w0 +
                    cbArr[iL] * wL + cbArr[iR] * wR + cbArr[iU] * wU + cbArr[iD] * wD +
                    cbArr[iL2] * wL2 + cbArr[iR2] * wR2 + cbArr[iU2] * wU2 + cbArr[iD2] * wD2) / wSum
                val avgCr = (crArr[idx] * w0 +
                    crArr[iL] * wL + crArr[iR] * wR + crArr[iU] * wU + crArr[iD] * wD +
                    crArr[iL2] * wL2 + crArr[iR2] * wR2 + crArr[iU2] * wU2 + crArr[iD2] * wD2) / wSum

                var cb = cbArr[idx] + (avgCb - cbArr[idx]) * params.chromaDenoise
                var cr = crArr[idx] + (avgCr - crArr[idx]) * params.chromaDenoise

                val chromaMag = hypot(cb.toDouble(), cr.toDouble()).toFloat()
                val neutralKeep = 0.45f + 0.55f * smoothstep(0.010f, 0.042f, chromaMag)
                cb *= neutralKeep
                cr *= neutralKeep

                // 2. CAS-резкость строго по яркости Y с отсечкой шума и защитой от ореолов
                val yAvg = (yL + yR + yU + yD) * 0.25f
                val detail = y0 - yAvg
                val edgeMask = smoothstep(0.014f, 0.055f, abs(detail))
                val yMin = min(y0, min(min(yL, yR), min(yU, yD)))
                val yMax = max(y0, max(max(yL, yR), max(yU, yD)))
                var y = (y0 + detail * params.sharpness * edgeMask).coerceIn(yMin, yMax)

                // 3. Киношная тональная кривая + вытягивание теней
                y += params.shadowLift * y * (1f - y) * (1f - y)
                val sCurve = y * y * (3f - 2f * y)
                y = (y + (sCurve - y) * params.contrast).coerceIn(0f, 1f)

                // 4. Умный Vibrance (нейтральные стены не красит)
                val vibMask = smoothstep(0.025f, 0.09f, chromaMag) *
                    (1f - smoothstep(0.22f, 0.42f, chromaMag))
                val vibScale = 1f + params.vibrance * vibMask
                cb *= vibScale
                cr *= vibScale

                val rOut = ((y + cr * 1.5748f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val gOut = ((y - cb * 0.1873f - cr * 0.4681f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val bOut = ((y + cb * 1.8556f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                outPixels[idx] = (pixels[idx] and (0xFF shl 24)) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        if (working !== source) {
            working.recycle()
        }
        return out
    }

    private fun fastWeight(x: Float): Float = 1f / (1f + x + 0.5f * x * x)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
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

        val minTarget = if (upscale2K) 35_000_000 else 24_000_000
        val boostedSource = if (sourceBitrate > 0) (sourceBitrate * 1.6f).toInt() else 0
        return maxOf(minTarget, boostedSource).coerceAtMost(50_000_000)
    }

    private fun buildVideoEffects(
        options: MediaProcessingOptions,
        stabTrack: StabilizationTrack?,
    ): List<Effect> {
        val effects = mutableListOf<Effect>()

        options.colorPreset.toGradeParams()?.let { params ->
            effects += ProCinemaGlEffect(params)
        }

        if (stabTrack != null) {
            effects += stabTrack.toMatrixTransformation()
        }

        if (options.upscale2K) {
            effects += Presentation.createForHeight(1536)
        }

        return effects
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
 * GPU-шейдер обработки для Media3 (OpenGL ES 2.0):
 * 1) Билатеральный chroma denoise (убирает зелёно-розовые пятна на стенах и в тенях)
 * 2) Адаптивная CAS-резкость только по яркости Y (без зерна и ореолов)
 * 3) Киношная S-кривая + мягкое вытягивание теней
 * 4) Умный Vibrance (нейтральные поверхности остаются чистыми)
 */
@OptIn(UnstableApi::class)
private class ProCinemaGlEffect(private val params: GradeParams) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ProCinemaShaderProgram(useHdr, params)
}

@OptIn(UnstableApi::class)
private class ProCinemaShaderProgram(
    useHdr: Boolean,
    private val params: GradeParams,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uContrast;
            uniform float uShadowLift;
            uniform float uVibrance;
            uniform float uSharpness;
            uniform float uChromaDenoise;
            varying vec2 vTexCoord;

            vec3 rgb2ycc(vec3 c) {
                float y  = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float cb = (c.b - y) * 0.5389;
                float cr = (c.r - y) * 0.6350;
                return vec3(y, cb, cr);
            }

            vec3 ycc2rgb(vec3 ycc) {
                float r = ycc.x + ycc.z * 1.5748;
                float g = ycc.x - ycc.y * 0.1873 - ycc.z * 0.4681;
                float b = ycc.x + ycc.y * 1.8556;
                return clamp(vec3(r, g, b), 0.0, 1.0);
            }

            void main() {
                vec4 centerSample = texture2D(uTexSampler, vTexCoord);
                vec3 ycc0 = rgb2ycc(centerSample.rgb);

                vec3 yccL = rgb2ycc(texture2D(uTexSampler, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb);
                vec3 yccR = rgb2ycc(texture2D(uTexSampler, vTexCoord + vec2( uTexelSize.x, 0.0)).rgb);
                vec3 yccU = rgb2ycc(texture2D(uTexSampler, vTexCoord + vec2(0.0,  uTexelSize.y)).rgb);
                vec3 yccD = rgb2ycc(texture2D(uTexSampler, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb);

                vec3 yccL2 = rgb2ycc(texture2D(uTexSampler, vTexCoord + vec2(-2.0 * uTexelSize.x, 0.0)).rgb);
                vec3 yccR2 = rgb2ycc(texture2D(uTexSampler, vTexCoord + vec2( 2.0 * uTexelSize.x, 0.0)).rgb);
                vec3 yccU2 = rgb2ycc(texture2D(uTexSampler, vTexCoord + vec2(0.0,  2.0 * uTexelSize.y)).rgb);
                vec3 yccD2 = rgb2ycc(texture2D(uTexSampler, vTexCoord + vec2(0.0, -2.0 * uTexelSize.y)).rgb);

                float sigma = 0.07;
                float w0 = 1.5;
                float wL = exp(-abs(yccL.x - ycc0.x) / sigma);
                float wR = exp(-abs(yccR.x - ycc0.x) / sigma);
                float wU = exp(-abs(yccU.x - ycc0.x) / sigma);
                float wD = exp(-abs(yccD.x - ycc0.x) / sigma);
                float wL2 = exp(-abs(yccL2.x - ycc0.x) / sigma) * 0.7;
                float wR2 = exp(-abs(yccR2.x - ycc0.x) / sigma) * 0.7;
                float wU2 = exp(-abs(yccU2.x - ycc0.x) / sigma) * 0.7;
                float wD2 = exp(-abs(yccD2.x - ycc0.x) / sigma) * 0.7;

                float wSum = w0 + wL + wR + wU + wD + wL2 + wR2 + wU2 + wD2;
                vec2 avgChroma = (ycc0.yz * w0 +
                                  yccL.yz * wL + yccR.yz * wR + yccU.yz * wU + yccD.yz * wD +
                                  yccL2.yz * wL2 + yccR2.yz * wR2 + yccU2.yz * wU2 + yccD2.yz * wD2) / wSum;
                vec2 chroma = mix(ycc0.yz, avgChroma, uChromaDenoise);

                float chromaMag = length(chroma);
                float neutralSuppress = smoothstep(0.010, 0.042, chromaMag);
                chroma *= mix(0.45, 1.0, neutralSuppress);

                float yAvg = (yccL.x + yccR.x + yccU.x + yccD.x) * 0.25;
                float detail = ycc0.x - yAvg;
                float edgeMask = smoothstep(0.014, 0.055, abs(detail));
                float yMin = min(ycc0.x, min(min(yccL.x, yccR.x), min(yccU.x, yccD.x)));
                float yMax = max(ycc0.x, max(max(yccL.x, yccR.x), max(yccU.x, yccD.x)));
                float y = clamp(ycc0.x + detail * uSharpness * edgeMask, yMin, yMax);

                y = y + uShadowLift * y * (1.0 - y) * (1.0 - y);
                float sCurve = y * y * (3.0 - 2.0 * y);
                y = clamp(mix(y, sCurve, uContrast), 0.0, 1.0);

                float vibMask = smoothstep(0.025, 0.09, chromaMag) * (1.0 - smoothstep(0.22, 0.42, chromaMag));
                chroma *= (1.0 + uVibrance * vibMask);

                gl_FragColor = vec4(ycc2rgb(vec3(y, chroma)), centerSample.a);
            }
        """

        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
        )
    }

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    private var programId = 0
    private var aPosLoc = -1
    private var aTexLoc = -1
    private var uSamplerLoc = -1
    private var uTexelSizeLoc = -1
    private var uContrastLoc = -1
    private var uShadowLiftLoc = -1
    private var uVibranceLoc = -1
    private var uSharpnessLoc = -1
    private var uChromaDenoiseLoc = -1
    private var texWidth = 1
    private var texHeight = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texWidth = inputWidth.coerceAtLeast(1)
        texHeight = inputHeight.coerceAtLeast(1)
        ensureProgram()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        ensureProgram()
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glUniform1i(uSamplerLoc, 0)
        GLES20.glUniform2f(uTexelSizeLoc, 1f / texWidth.toFloat(), 1f / texHeight.toFloat())
        GLES20.glUniform1f(uContrastLoc, params.contrast)
        GLES20.glUniform1f(uShadowLiftLoc, params.shadowLift)
        GLES20.glUniform1f(uVibranceLoc, params.vibrance)
        GLES20.glUniform1f(uSharpnessLoc, params.sharpness)
        GLES20.glUniform1f(uChromaDenoiseLoc, params.chromaDenoise)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)
    }

    override fun release() {
        super.release()
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun ensureProgram() {
        if (programId != 0) return
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw VideoFrameProcessingException("GL link error: $log")
        }
        programId = prog
        aPosLoc = GLES20.glGetAttribLocation(prog, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(prog, "aTexCoord")
        uSamplerLoc = GLES20.glGetUniformLocation(prog, "uTexSampler")
        uTexelSizeLoc = GLES20.glGetUniformLocation(prog, "uTexelSize")
        uContrastLoc = GLES20.glGetUniformLocation(prog, "uContrast")
        uShadowLiftLoc = GLES20.glGetUniformLocation(prog, "uShadowLift")
        uVibranceLoc = GLES20.glGetUniformLocation(prog, "uVibrance")
        uSharpnessLoc = GLES20.glGetUniformLocation(prog, "uSharpness")
        uChromaDenoiseLoc = GLES20.glGetUniformLocation(prog, "uChromaDenoise")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw VideoFrameProcessingException("GL shader compile error: $log")
        }
        return shader
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

            val corrXNorm = (sx - cumX[i]) / GRID_W.toFloat()
            val corrYNorm = (sy - cumY[i]) / GRID_H.toFloat()

            val (dispX, dispY) = when (rotation) {
                90 -> -corrYNorm to -corrXNorm
                180 -> -corrXNorm to corrYNorm
                270 -> corrYNorm to corrXNorm
                else -> corrXNorm to -corrYNorm
            }

            val rawTx = dispX * 2f * ZOOM
            val rawTy = dispY * 2f * ZOOM

            txArr[i] = (maxNdc * tanh((rawTx / maxNdc).toDouble())).toFloat()
            tyArr[i] = (maxNdc * tanh((rawTy / maxNdc).toDouble())).toFloat()
        }

        return StabilizationTrack(pts, txArr, tyArr, ZOOM)
    }
}
