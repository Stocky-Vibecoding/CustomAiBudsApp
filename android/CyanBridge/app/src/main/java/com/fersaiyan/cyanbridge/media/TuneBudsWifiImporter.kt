package com.fersaiyan.cyanbridge.media

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ImportProgressState(
    val isRunning: Boolean = false,
    val statusText: String = "Готов к импорту",
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFilePercent: Int = 0,
    val downloadedCount: Int = 0,
    val error: String? = null,
)

class TuneBudsWifiImporter private constructor(private val context: Context) {
    companion object {
        private val MEDIA_REGEX = Regex(
            """([A-Za-z0-9_\-./]+\.(?:jpg|jpeg|png|mp4|mov))""",
            RegexOption.IGNORE_CASE,
        )

        @Volatile
        private var instance: TuneBudsWifiImporter? = null

        fun getInstance(context: Context): TuneBudsWifiImporter =
            instance ?: synchronized(this) {
                instance ?: TuneBudsWifiImporter(context.applicationContext).also { instance = it }
            }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(ImportProgressState())
    val state: StateFlow<ImportProgressState> = _state.asStateFlow()

    fun getMediaDir(): File = File(context.getExternalFilesDir(null), "AiBudsMedia").apply { mkdirs() }

    suspend fun startImport(tuneBuds: TuneBudsManager) {
        if (_state.value.isRunning) return
        if (!tuneBuds.isConnected()) {
            _state.value = ImportProgressState(error = "Сначала подключи очки по Bluetooth")
            return
        }

        _state.value = ImportProgressState(
            isRunning = true,
            statusText = "Запуск Wi-Fi точки доступа...",
        )

        var reservation: WifiManager.LocalOnlyHotspotReservation? = null
        try {
            val hotspot = startLocalHotspot()
            reservation = hotspot.reservation

            _state.value = _state.value.copy(
                statusText = "Подключение очков к ${hotspot.ssid}...",
            )

            val baseUrl = tuneBuds.startFileManager(
                hotspotSsid = hotspot.ssid,
                hotspotPassword = hotspot.password,
                timeoutMs = 35_000L,
            )?.trimEnd('/') ?: throw IllegalStateException("Очки не вернули адрес Wi-Fi сервера")

            _state.value = _state.value.copy(statusText = "Получение списка файлов с очков...")

            val remoteFiles = fetchFileList(baseUrl)
            if (remoteFiles.isEmpty()) {
                _state.value = ImportProgressState(
                    isRunning = false,
                    statusText = "Новых фото и видео на очках не найдено",
                )
                return
            }

            val targetDir = getMediaDir()
            var downloaded = 0

            remoteFiles.forEachIndexed { index, remotePath ->
                val cleanPath = remotePath.trimStart('/')
                val fileName = cleanPath.substringAfterLast('/')
                val outFile = File(targetDir, fileName)

                _state.value = _state.value.copy(
                    statusText = "Скачивание ${index + 1} из ${remoteFiles.size}: $fileName",
                    currentFileIndex = index + 1,
                    totalFiles = remoteFiles.size,
                    currentFilePercent = 0,
                )

                if (outFile.exists() && outFile.length() > 0L) {
                    _state.value = _state.value.copy(currentFilePercent = 100)
                    return@forEachIndexed
                }

                val fileUrl = if (remotePath.startsWith("http", ignoreCase = true)) {
                    remotePath
                } else {
                    "$baseUrl/$cleanPath"
                }

                downloadFile(fileUrl, outFile) { pct ->
                    _state.value = _state.value.copy(currentFilePercent = pct)
                }
                downloaded++
            }

            _state.value = ImportProgressState(
                isRunning = false,
                statusText = "Готово! Скачано: $downloaded (всего на очках: ${remoteFiles.size})",
                downloadedCount = downloaded,
                totalFiles = remoteFiles.size,
                currentFileIndex = remoteFiles.size,
                currentFilePercent = 100,
            )
        } catch (err: Throwable) {
            _state.value = ImportProgressState(
                isRunning = false,
                statusText = "Ошибка импорта",
                error = err.message ?: "Неизвестная ошибка Wi-Fi переноса",
            )
        } finally {
            runCatching { tuneBuds.finishTransferBlocking() }
            runCatching { reservation?.close() }
        }
    }

    private data class HotspotCredentials(
        val ssid: String,
        val password: String,
        val reservation: WifiManager.LocalOnlyHotspotReservation,
    )

    @SuppressLint("MissingPermission")
    private suspend fun startLocalHotspot(): HotspotCredentials = suspendCancellableCoroutine { cont ->
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    val ssid: String?
                    val pass: String?
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val cfg = reservation.softApConfiguration
                        ssid = cfg.ssid
                        pass = cfg.passphrase
                    } else {
                        @Suppress("DEPRECATION")
                        val cfg = reservation.wifiConfiguration
                        ssid = cfg?.SSID
                        pass = cfg?.preSharedKey
                    }

                    if (ssid.isNullOrBlank() || pass.isNullOrBlank()) {
                        reservation.close()
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("Не удалось получить SSID/пароль точки доступа"))
                        }
                        return
                    }
                    if (cont.isActive) {
                        cont.resume(HotspotCredentials(ssid, pass, reservation))
                    } else {
                        reservation.close()
                    }
                }

                override fun onFailed(reason: Int) {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Не удалось поднять точку доступа (код $reason). Проверь геолокацию и Wi-Fi."))
                    }
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private suspend fun fetchFileList(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val candidates = listOf(
            baseUrl,
            "$baseUrl/files",
            "$baseUrl/list",
            "$baseUrl/media.config",
        )
        for (url in candidates) {
            val body = runCatching {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                }
            }.getOrDefault("")

            if (body.isNotBlank()) {
                val found = MEDIA_REGEX.findAll(body)
                    .map { it.groupValues[1] }
                    .distinct()
                    .toList()
                if (found.isNotEmpty()) return@withContext found
            }
        }
        emptyList()
    }

    private suspend fun downloadFile(
        url: String,
        dest: File,
        onPercent: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} при скачивании ${dest.name}")
            val body = resp.body ?: throw IllegalStateException("Пустой ответ для ${dest.name}")
            val total = body.contentLength()
            val tmp = File(dest.parentFile, "${dest.name}.part")

            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        readTotal += read
                        if (total > 0L) {
                            onPercent(((readTotal * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
            onPercent(100)
        }
    }
}
