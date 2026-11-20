package com.fersaiyan.cyanbridge.devices.tunebuds

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException

data class TuneBudsAiEvent(
    val chatState: Int,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class TuneBudsState(
    val connectionLabel: String = "Не подключено",
    val protocolState: String = TuneBudsSppState.DISCONNECTED.name,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val firmwareVersion: String? = null,
    val coprocessorVersion: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val protocolPacketSize: Int = TuneBudsProtocol.DEFAULT_MAX_PACKET_SIZE,
    val aiKitSupport: Int? = null,
    val cameraCoprocessorType: Int? = null,
    val deviceAbility: ByteArray? = null,
    val deviceCapabilityMask: Int? = null,
    val supportDetection: Int? = null,
    val inEarDetectionEnabled: Boolean? = null,
    val supportAudio: Int? = null,
    val videoLimitSeconds: Int? = null,
    val audioLimitSeconds: Int? = null,
    val supportVolumeControl: Int? = null,
    val currentVolume: List<Int>? = null,
    val screenConfig: TuneBudsScreenConfig? = null,
    val resolution: Int? = null,
    val supportOpus: Int? = null,
    val supportAiChat: Int? = null,
    val lastAiChatState: Int? = null,
    val supportAppList: Int? = null,
    val wifiSupportMask: Int? = null,
    val storage: TuneBudsStorageInfo? = null,
    val mediaCounts: TuneBudsMediaCounts? = null,
    val mediaBaseUrl: String? = null,
    val wifiState: Int? = null,
    val workState: Int? = null,
    val isVideoRecording: Boolean = false,
    val videoRecordingStartedAtMs: Long? = null,
    val isAudioRecording: Boolean = false,
    val keySettings: Map<Int, Int> = emptyMap(),
    val rawKeySettingsHex: String? = null,
    val lastError: String? = null,
)

class TuneBudsManager private constructor(context: Context) {
    companion object {
        private const val TAG = "TuneBudsManager"
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val OPTIONAL_ACK_TIMEOUT_MS = 1_000L
        private const val CAMERA_CLOSE_RETRY_MS = 1_000L
        private const val CAMERA_CLOSE_MAX_ATTEMPTS = 15
        private const val FILE_MANAGER_RETRY_MS = 1_000L
        private const val FILE_MANAGER_MAX_ATTEMPTS = 30

        @Volatile
        private var instance: TuneBudsManager? = null

        fun getInstance(context: Context): TuneBudsManager =
            instance ?: synchronized(this) {
                instance ?: TuneBudsManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val client = TuneBudsSppClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestMutex = Mutex()
    private val aiPictureBuffer = ByteArrayOutputStream()
    private val _state = MutableStateFlow(TuneBudsState())
    private val _aiPhotos = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
    private val _aiEvents = MutableSharedFlow<TuneBudsAiEvent>(extraBufferCapacity = 16)
    private val _aiOpusPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    private var connectJob: Job? = null

    val state: StateFlow<TuneBudsState> = _state.asStateFlow()
    val aiPhotos: SharedFlow<ByteArray> = _aiPhotos.asSharedFlow()
    val aiEvents: SharedFlow<TuneBudsAiEvent> = _aiEvents.asSharedFlow()
    val aiOpusPackets: SharedFlow<ByteArray> = _aiOpusPackets.asSharedFlow()

    init {
        scope.launch {
            client.state.collect { transportState ->
                val label = when (transportState) {
                    TuneBudsSppState.DISCONNECTED -> "Не подключено"
                    TuneBudsSppState.BONDING -> "Сопряжение..."
                    TuneBudsSppState.CONNECTING -> "Подключение..."
                    TuneBudsSppState.CONNECTED -> "Подключено"
                    TuneBudsSppState.ERROR -> _state.value.lastError ?: "Ошибка подключения"
                }
                val connected = transportState == TuneBudsSppState.CONNECTED
                if (connected) {
                    AiBudsForegroundService.start(
                        appContext,
                        _state.value.deviceName ?: _state.value.model,
                    )
                } else if (transportState == TuneBudsSppState.DISCONNECTED ||
                    transportState == TuneBudsSppState.ERROR
                ) {
                    AiBudsForegroundService.stop(appContext)
                }
                _state.value = _state.value.copy(
                    connectionLabel = label,
                    protocolState = transportState.name,
                    isVideoRecording = if (connected) _state.value.isVideoRecording else false,
                    videoRecordingStartedAtMs = if (connected) _state.value.videoRecordingStartedAtMs else null,
                    isAudioRecording = if (connected) _state.value.isAudioRecording else false,
                )
            }
        }
        scope.launch { client.frames.collect(::handleFrame) }
    }

    @Synchronized
    fun connect(address: String, deviceName: String? = null) {
        val normalizedAddress = address.trim()
        if (normalizedAddress.isBlank()) {
            updateError("Не выбран Bluetooth-адрес очков")
            return
        }
        val sameAddress = _state.value.deviceAddress.equals(normalizedAddress, ignoreCase = true)
        if (sameAddress && (client.isConnected() || connectJob?.isActive == true)) {
            return
        }
        connectJob?.cancel()
        _state.value = TuneBudsState(
            connectionLabel = "Подключение...",
            protocolState = TuneBudsSppState.CONNECTING.name,
            deviceAddress = normalizedAddress,
            deviceName = deviceName,
        )
        connectJob = scope.launch {
            client.connect(normalizedAddress).getOrElse { error ->
                updateError(error.message ?: "Не удалось подключиться к очкам")
                return@launch
            }
            AiBudsForegroundService.start(appContext, deviceName)
            _state.value = _state.value.copy(
                connectionLabel = "Подключено",
                protocolState = TuneBudsSppState.CONNECTED.name,
                lastError = null,
            )
            runCatching { initializeProtocol() }
                .onFailure { error -> updateError("Ошибка инициализации: ${error.message}") }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        AiBudsForegroundService.stop(appContext)
        client.disconnect()
    }

    fun isConnected(): Boolean = client.isConnected()

    fun takePhoto() = launchCommand("сделать фото") {
        requireSuccess(request(TuneBudsProtocol.CMD_CAMERA_ON, byteArrayOf(0)))
    }

    suspend fun takePhotoBlocking() {
        requireSuccess(request(TuneBudsProtocol.CMD_CAMERA_ON, byteArrayOf(0)))
        delay(2_500L)
        stopCameraSubsystem()
    }

    suspend fun capturePhotoForAi(timeoutMs: Long = 15_000L): Result<ByteArray> = coroutineScope {
        if (!client.isConnected()) {
            return@coroutineScope Result.failure(IOException("Очки не подключены"))
        }
        synchronized(aiPictureBuffer) { aiPictureBuffer.reset() }
        val photoDeferred = async(start = CoroutineStart.UNDISPATCHED) { aiPhotos.first() }
        try {
            val resp = request(TuneBudsProtocol.CMD_CAMERA_ON, byteArrayOf(1))
            val status = TuneBudsProtocol.parseStatus(resp.payload)
            if (status != 0) {
                val hint = if ((_state.value.batteryPercent ?: 100) < 30) {
                    " (возможно, низкий заряд очков)"
                } else {
                    ""
                }
                return@coroutineScope Result.failure(
                    IOException("Очки не смогли сделать фото: код ${status ?: "?"}$hint"),
                )
            }
            val bytes = withTimeoutOrNull(timeoutMs) { photoDeferred.await() }
            if (bytes != null && bytes.isNotEmpty()) {
                Result.success(bytes)
            } else {
                Result.failure(IOException("Таймаут получения фото с очков"))
            }
        } catch (err: Throwable) {
            Result.failure(err)
        } finally {
            photoDeferred.cancel()
        }
    }

    fun toggleVideo() = launchCommand("переключить запись видео") {
        if (_state.value.isVideoRecording) {
            stopCameraSubsystem()
        } else {
            requireSuccess(request(TuneBudsProtocol.CMD_START_VIDEO))
            _state.value = _state.value.copy(
                isVideoRecording = true,
                videoRecordingStartedAtMs = System.currentTimeMillis(),
                isAudioRecording = false,
            )
        }
    }

    fun stopVideo() = launchCommand("остановить видео") {
        stopCameraSubsystem()
    }

    fun setVideoLimit(seconds: Int) = launchCommand("изменить лимит видео") {
        val payload = TuneBudsProtocol.buildVideoLimitPayload(seconds)
        val resp = request(TuneBudsProtocol.CMD_GLASS_CONFIG, payload)
        val tlv = TuneBudsProtocol.parseTlvResponse(resp.payload)
        if (tlv[TuneBudsProtocol.CONFIG_VIDEO_LIMIT_TIME] == false) {
            throw IOException("Очки отклонили лимит видео")
        }
        _state.value = _state.value.copy(videoLimitSeconds = seconds, lastError = null)
        runCatching { requestDeviceInfo(TuneBudsProtocol.INFO_VIDEO_LIMIT) }
    }

    fun setWearDetection(enabled: Boolean) = launchCommand("переключить датчик снятия") {
        requireSuccess(
            request(
                TuneBudsProtocol.CMD_SET_DETECTION,
                byteArrayOf(if (enabled) 1 else 0),
            ),
        )
        _state.value = _state.value.copy(inEarDetectionEnabled = enabled, lastError = null)
    }

    /** Безопасно считывает сырую таблицу кнопок 0x05 без записи в память очков. */
    fun requestKeySettings() = launchCommand("прочитать слоты кнопок") {
        requestDeviceInfo(TuneBudsProtocol.INFO_KEY_SETTINGS)
    }

    /**
     * Отправляет AIChatStateRequest (0xB0): 1 = SCO, 2 = OPUS, 0 = закрыть.
     * Не блокирует очередь надолго, если прошивка не шлёт RESPONSE на 0xB0.
     */
    fun setAiChatState(mode: Int) {
        scope.launch {
            if (!client.isConnected()) return@launch
            runCatching {
                requestOptionalAck(TuneBudsProtocol.CMD_AI_CHAT_STATE, byteArrayOf(mode.toByte()))
                _state.value = _state.value.copy(lastAiChatState = mode)
            }.onFailure { err ->
                Log.w(TAG, "setAiChatState($mode) error: ${err.message}")
            }
        }
    }

    fun toggleAudio() = launchCommand("переключить аудиозапись") {
        if (_state.value.isAudioRecording) {
            stopCameraSubsystem()
        } else {
            requireSuccess(request(TuneBudsProtocol.CMD_START_AUDIO))
            _state.value = _state.value.copy(
                isAudioRecording = true,
                isVideoRecording = false,
                videoRecordingStartedAtMs = null,
            )
        }
    }

    fun requestBattery() = launchCommand("прочитать заряд") {
        requestDeviceInfo(TuneBudsProtocol.INFO_BATTERY)
    }

    fun requestVersion() = launchCommand("прочитать версии") {
        requestDeviceInfo(
            TuneBudsProtocol.INFO_FIRMWARE_VERSION,
            TuneBudsProtocol.INFO_MODEL,
            TuneBudsProtocol.INFO_SERIAL_NUMBER,
        )
        requireSuccess(request(TuneBudsProtocol.CMD_COPROCESSOR_VERSION))
        runCatching { requestCapabilityInfo() }
    }

    fun requestCapabilities() = launchCommand("прочитать возможности") {
        requestCapabilityInfo()
    }

    fun requestStorage() = launchCommand("прочитать память") {
        requireSuccess(request(TuneBudsProtocol.CMD_STORAGE))
    }

    fun formatStorage() = launchCommand("очистить память") {
        requireSuccess(request(TuneBudsProtocol.CMD_FORMAT_STORAGE))
        runCatching {
            request(TuneBudsProtocol.CMD_STORAGE)
            request(TuneBudsProtocol.CMD_MEDIA_COUNTS)
        }
    }

    fun requestMediaCount() = launchCommand("прочитать счётчик медиа") {
        requireSuccess(request(TuneBudsProtocol.CMD_MEDIA_COUNTS))
    }

    fun requestWorkState() = launchCommand("прочитать состояние") {
        val response = request(TuneBudsProtocol.CMD_WORK_STATE)
        TuneBudsProtocol.parseStatus(response.payload)?.let(::applyWorkState)
    }

    fun syncTime() = launchCommand("синхронизировать время") {
        requireSuccess(
            request(
                TuneBudsProtocol.CMD_SET_TIME,
                TuneBudsProtocol.buildSetTimePayload(),
            ),
        )
    }

    fun refreshStatus() = launchCommand("обновить статус") {
        requestDeviceInfo(
            TuneBudsProtocol.INFO_BATTERY,
            TuneBudsProtocol.INFO_FIRMWARE_VERSION,
            TuneBudsProtocol.INFO_KEY_SETTINGS,
            TuneBudsProtocol.INFO_MODEL,
            TuneBudsProtocol.INFO_SERIAL_NUMBER,
            TuneBudsProtocol.INFO_IN_EAR_STATE,
            TuneBudsProtocol.INFO_WIFI_SUPPORT,
        )
        requestCapabilityInfo()
        requireSuccess(request(TuneBudsProtocol.CMD_STORAGE))
        requireSuccess(request(TuneBudsProtocol.CMD_MEDIA_COUNTS))
        requireSuccess(request(TuneBudsProtocol.CMD_COPROCESSOR_VERSION))
    }

    suspend fun startFileManager(
        hotspotSsid: String,
        hotspotPassword: String,
        channel: Int = 0,
        timeoutMs: Long = 30_000L,
    ): String? = coroutineScope {
        if (!client.isConnected()) return@coroutineScope null
        _state.value = _state.value.copy(mediaBaseUrl = null, wifiState = null)
        val wifiFailure = launch(start = CoroutineStart.UNDISPATCHED) {
            val failed = state.first { it.wifiState in setOf(0, 4, 5) }
            throw IOException("Ошибка Wi-Fi на очках (код ${failed.wifiState})")
        }
        val endpoint = async(start = CoroutineStart.UNDISPATCHED) {
            state.filter { !it.mediaBaseUrl.isNullOrBlank() }.first().mediaBaseUrl
        }
        try {
            requireSuccess(
                request(
                    TuneBudsProtocol.CMD_CONFIGURE_WIFI,
                    TuneBudsProtocol.buildWifiPayload(
                        mode = 0,
                        ssid = hotspotSsid,
                        password = hotspotPassword,
                        channel = channel,
                    ),
                ),
            )
            var fileManagerAttempt = 1
            while (true) {
                val response = request(TuneBudsProtocol.CMD_FILE_MANAGER)
                when (val status = TuneBudsProtocol.parseStatus(response.payload)) {
                    0 -> break
                    1, 3, 5 -> {
                        if (fileManagerAttempt >= FILE_MANAGER_MAX_ATTEMPTS) {
                            throw IOException("Файловый сервер очков занят более ${FILE_MANAGER_MAX_ATTEMPTS}с")
                        }
                        fileManagerAttempt++
                        delay(FILE_MANAGER_RETRY_MS)
                    }
                    null -> throw IOException("Файловый сервер очков не ответил")
                    else -> throw IOException("Ошибка файлового сервера очков (код $status)")
                }
            }
            withTimeoutOrNull(timeoutMs) { endpoint.await() }
        } finally {
            wifiFailure.cancel()
            endpoint.cancel()
        }
    }

    fun finishTransfer() = launchCommand("закрыть камеру") {
        stopCameraSubsystem()
    }

    suspend fun finishTransferBlocking() {
        stopCameraSubsystem()
    }

    private suspend fun initializeProtocol() {
        val capabilities = requestDeviceInfo(
            TuneBudsProtocol.INFO_MAX_PACKET_SIZE,
            TuneBudsProtocol.INFO_AI_KIT_SUPPORT,
        )
        capabilities[TuneBudsProtocol.INFO_MAX_PACKET_SIZE]
            ?.firstOrNull()
            ?.toInt()
            ?.and(0xFF)
            ?.takeIf { it > TuneBudsProtocol.HEADER_SIZE }
            ?.let { packetSize ->
                client.setMaxPacketSize(packetSize)
                _state.value = _state.value.copy(protocolPacketSize = packetSize)
            }
        val aiKitSupport = capabilities[TuneBudsProtocol.INFO_AI_KIT_SUPPORT]
            ?.firstOrNull()
            ?.toInt()
            ?.and(0xFF)
            ?: 1
        _state.value = _state.value.copy(aiKitSupport = aiKitSupport)

        requestDeviceInfo(
            TuneBudsProtocol.INFO_BATTERY,
            TuneBudsProtocol.INFO_FIRMWARE_VERSION,
            TuneBudsProtocol.INFO_KEY_SETTINGS,
            TuneBudsProtocol.INFO_MODEL,
            TuneBudsProtocol.INFO_SERIAL_NUMBER,
            TuneBudsProtocol.INFO_IN_EAR_STATE,
            TuneBudsProtocol.INFO_VIDEO_LIMIT,
            TuneBudsProtocol.INFO_AUDIO_LIMIT,
            TuneBudsProtocol.INFO_SUPPORT_DETECTION,
            TuneBudsProtocol.INFO_WIFI_SUPPORT,
        )
        runCatching { requestCapabilityInfo() }

        // Сообщаем прошивке очков, что ИИ-сервис авторизован (аналог setAiServiceAuthed в ABMateManager)
        runCatching {
            request(TuneBudsProtocol.CMD_GLASS_CONFIG, TuneBudsProtocol.buildAiServiceAuthPayload())
            Log.i(TAG, "AI service auth state sent to glasses (0xF7 -> 0x8B [1, 1])")
        }.onFailure {
            Log.w(TAG, "Optional setAiServiceAuthed failed: ${it.message}")
        }

        requireSuccess(request(TuneBudsProtocol.CMD_COPROCESSOR_VERSION))
        requireSuccess(request(TuneBudsProtocol.CMD_STORAGE))
        requireSuccess(request(TuneBudsProtocol.CMD_MEDIA_COUNTS))
        val workState = request(TuneBudsProtocol.CMD_WORK_STATE)
        TuneBudsProtocol.parseStatus(workState.payload)?.let(::applyWorkState)
    }

    private suspend fun requestCapabilityInfo() {
        requestDeviceInfo(
            TuneBudsProtocol.INFO_DEVICE_ABILITY,
            TuneBudsProtocol.INFO_SUPPORT_DETECTION,
            TuneBudsProtocol.INFO_SUPPORT_AUDIO,
            TuneBudsProtocol.INFO_RESOLUTION,
            TuneBudsProtocol.INFO_SCREEN_CONFIG,
            TuneBudsProtocol.INFO_SUPPORT_VOLUME_CONTROL,
            TuneBudsProtocol.INFO_CURRENT_VOLUME,
            TuneBudsProtocol.INFO_VIDEO_LIMIT,
            TuneBudsProtocol.INFO_AUDIO_LIMIT,
            TuneBudsProtocol.INFO_SUPPORT_OPUS,
            TuneBudsProtocol.INFO_AI_CHAT_SUPPORT,
            TuneBudsProtocol.INFO_SUPPORT_APP_LIST,
        )
        val cameraInfo = request(TuneBudsProtocol.CMD_CAMERA_COPROCESSOR_INFO)
        TuneBudsProtocol.parseStatus(cameraInfo.payload)?.let { type ->
            _state.value = _state.value.copy(cameraCoprocessorType = type, lastError = null)
        }
    }

    private suspend fun requestDeviceInfo(vararg types: Int): Map<Int, ByteArray> {
        val response = request(
            TuneBudsProtocol.CMD_DEVICE_INFO,
            TuneBudsProtocol.deviceInfoQuery(*types),
        )
        return TuneBudsProtocol.parseDeviceInfo(response.payload).also(::applyDeviceInfo)
    }

    private suspend fun request(
        command: Int,
        payload: ByteArray = byteArrayOf(),
    ): TuneBudsFrame = requestMutex.withLock {
        coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    client.frames
                        .filter { it.type == TuneBudsFrameType.RESPONSE && it.command == command }
                        .first()
                }
            }
            try {
                client.send(command, payload).getOrThrow()
                response.await()
            } catch (error: Throwable) {
                response.cancel()
                throw error
            }
        }
    }

    private suspend fun requestOptionalAck(
        command: Int,
        payload: ByteArray = byteArrayOf(),
        timeoutMs: Long = OPTIONAL_ACK_TIMEOUT_MS,
    ): TuneBudsFrame? = requestMutex.withLock {
        coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(timeoutMs) {
                    client.frames
                        .filter { it.type == TuneBudsFrameType.RESPONSE && it.command == command }
                        .first()
                }
            }
            try {
                client.send(command, payload).getOrThrow()
                response.await()
            } catch (error: Throwable) {
                response.cancel()
                throw error
            }
        }
    }

    private suspend fun stopCameraSubsystem() {
        repeat(CAMERA_CLOSE_MAX_ATTEMPTS) { attempt ->
            val response = request(TuneBudsProtocol.CMD_CAMERA_CLOSE)
            when (val status = TuneBudsProtocol.parseStatus(response.payload)) {
                0 -> {
                    _state.value = _state.value.copy(
                        isVideoRecording = false,
                        videoRecordingStartedAtMs = null,
                        isAudioRecording = false,
                    )
                    return
                }
                1 -> {
                    if (attempt < CAMERA_CLOSE_MAX_ATTEMPTS - 1) delay(CAMERA_CLOSE_RETRY_MS)
                }
                2 -> throw IOException("Камера занята передачей медиа")
                3 -> throw IOException("Камера занята обновлением")
                null -> throw IOException("Нет ответа от камеры")
                else -> throw IOException("Ошибка закрытия камеры (код $status)")
            }
        }
        throw IOException("Камера сохраняет файл дольше ${CAMERA_CLOSE_MAX_ATTEMPTS}с")
    }

    private fun requireSuccess(response: TuneBudsFrame) {
        val status = TuneBudsProtocol.parseStatus(response.payload)
            ?: throw IOException("Команда 0x${response.command.toString(16)} не вернула статус")
        if (status != 0) {
            throw IOException("Команда 0x${response.command.toString(16)} отклонена (код $status)")
        }
    }

    private fun applyWorkState(value: Int) {
        val cur = _state.value
        val stoppedByGlasses = value == 0 && (cur.isVideoRecording || cur.isAudioRecording)
        _state.value = cur.copy(
            workState = value,
            isVideoRecording = if (stoppedByGlasses) false else cur.isVideoRecording,
            videoRecordingStartedAtMs = if (stoppedByGlasses) null else cur.videoRecordingStartedAtMs,
            isAudioRecording = if (stoppedByGlasses) false else cur.isAudioRecording,
            lastError = null,
        )
    }

    private fun handleFrame(frame: TuneBudsFrame) {
        when (frame.command) {
            TuneBudsProtocol.CMD_WIFI_STATE -> if (frame.type == TuneBudsFrameType.NOTIFICATION) {
                TuneBudsProtocol.parseStatus(frame.payload)?.let { value ->
                    _state.value = _state.value.copy(wifiState = value)
                }
            }
            TuneBudsProtocol.CMD_DEVICE_INFO -> if (frame.type == TuneBudsFrameType.RESPONSE) {
                runCatching { TuneBudsProtocol.parseDeviceInfo(frame.payload) }
                    .onSuccess(::applyDeviceInfo)
                    .onFailure { Log.w(TAG, "Invalid device-info response", it) }
            }
            TuneBudsProtocol.INFO_BATTERY -> TuneBudsProtocol.parseBattery(frame.payload)?.let(::applyBattery)
            TuneBudsProtocol.INFO_KEY_SETTINGS -> if (frame.type == TuneBudsFrameType.NOTIFICATION) {
                val keys = TuneBudsProtocol.parseKeySettings(frame.payload)
                val updated = _state.value.keySettings.toMutableMap().apply { putAll(keys) }
                _state.value = _state.value.copy(
                    keySettings = updated,
                    rawKeySettingsHex = formatHex(frame.payload),
                    lastError = null,
                )
            }
            TuneBudsProtocol.INFO_IN_EAR_STATE -> TuneBudsProtocol.parseSupportFlag(frame.payload)?.let { enabled ->
                _state.value = _state.value.copy(inEarDetectionEnabled = enabled, lastError = null)
            }
            // Событие удержания дужки / AI-чата (0xB0 NOTIFICATION: 1=SCO, 2=OPUS, 0=CLOSE, 255=ERR)
            TuneBudsProtocol.CMD_AI_CHAT_STATE -> if (frame.type == TuneBudsFrameType.NOTIFICATION) {
                val aiState = TuneBudsProtocol.parseStatus(frame.payload) ?: return
                Log.i(TAG, "Glasses AI Chat state notification: $aiState")
                _state.value = _state.value.copy(lastAiChatState = aiState)
                _aiEvents.tryEmit(TuneBudsAiEvent(chatState = aiState))
            }
            // Поток OPUS с микрофона очков по SPP (0xB2 NOTIFICATION, кадры по 80 байт / 20 мс)
            TuneBudsProtocol.CMD_AI_RECORD_DATA -> if (frame.type == TuneBudsFrameType.NOTIFICATION && frame.payload.isNotEmpty()) {
                _aiOpusPackets.tryEmit(frame.payload.copyOf())
            }
            TuneBudsProtocol.CMD_AI_PICTURE -> if (frame.type == TuneBudsFrameType.NOTIFICATION) {
                handleAiPicture(frame.payload)
            }
            TuneBudsProtocol.CMD_COPROCESSOR_VERSION -> if (frame.type == TuneBudsFrameType.NOTIFICATION) {
                TuneBudsProtocol.parseString(frame.payload)?.let { version ->
                    _state.value = _state.value.copy(coprocessorVersion = version, lastError = null)
                }
            }
            TuneBudsProtocol.CMD_CAMERA_COPROCESSOR_INFO -> if (frame.type == TuneBudsFrameType.NOTIFICATION) {
                TuneBudsProtocol.parseStatus(frame.payload)?.let { type ->
                    _state.value = _state.value.copy(cameraCoprocessorType = type, lastError = null)
                }
            }
            TuneBudsProtocol.CMD_STORAGE -> if (frame.type == TuneBudsFrameType.NOTIFICATION) {
                TuneBudsProtocol.parseStorage(frame.payload)?.let { storage ->
                    _state.value = _state.value.copy(storage = storage, lastError = null)
                }
            }
            TuneBudsProtocol.CMD_FILE_MANAGER -> if (frame.type == TuneBudsFrameType.NOTIFICATION) {
                TuneBudsProtocol.parseString(frame.payload)?.let { endpoint ->
                    _state.value = _state.value.copy(mediaBaseUrl = endpoint, lastError = null)
                }
            }
            TuneBudsProtocol.CMD_MEDIA_COUNTS -> if (frame.type == TuneBudsFrameType.NOTIFICATION) {
                TuneBudsProtocol.parseMediaCounts(frame.payload)?.let { counts ->
                    _state.value = _state.value.copy(mediaCounts = counts, lastError = null)
                }
            }
            TuneBudsProtocol.CMD_WORK_STATE -> TuneBudsProtocol.parseStatus(frame.payload)?.let(::applyWorkState)
        }
    }

    private fun applyDeviceInfo(info: Map<Int, ByteArray>) {
        var current = _state.value
        info[TuneBudsProtocol.INFO_BATTERY]?.let { payload ->
            TuneBudsProtocol.parseBattery(payload)?.let { battery ->
                current = current.copy(
                    batteryPercent = battery.percent.coerceIn(0, 100),
                    isCharging = battery.isCharging,
                )
            }
        }
        info[TuneBudsProtocol.INFO_FIRMWARE_VERSION]?.let { payload ->
            TuneBudsProtocol.parseFirmwareVersion(payload)?.let { current = current.copy(firmwareVersion = it) }
        }
        info[TuneBudsProtocol.INFO_KEY_SETTINGS]?.let { payload ->
            val keys = TuneBudsProtocol.parseKeySettings(payload)
            val merged = current.keySettings.toMutableMap().apply { putAll(keys) }
            current = current.copy(
                keySettings = merged,
                rawKeySettingsHex = formatHex(payload),
            )
        }
        info[TuneBudsProtocol.INFO_MODEL]?.let { payload ->
            TuneBudsProtocol.parseString(payload)?.let { current = current.copy(model = it) }
        }
        info[TuneBudsProtocol.INFO_SERIAL_NUMBER]?.let { payload ->
            TuneBudsProtocol.parseString(payload)?.let { current = current.copy(serialNumber = it) }
        }
        info[TuneBudsProtocol.INFO_IN_EAR_STATE]?.let { payload ->
            TuneBudsProtocol.parseSupportFlag(payload)?.let { current = current.copy(inEarDetectionEnabled = it) }
        }
        info[TuneBudsProtocol.INFO_DEVICE_ABILITY]?.let { payload ->
            current = current.copy(deviceAbility = payload.copyOf())
            TuneBudsProtocol.parseDeviceCapabilities(payload)?.let {
                current = current.copy(deviceCapabilityMask = it)
            }
        }
        info[TuneBudsProtocol.INFO_SUPPORT_DETECTION]?.let { payload ->
            TuneBudsProtocol.parseUnsignedValue(payload)?.let {
                current = current.copy(supportDetection = it)
            }
        }
        info[TuneBudsProtocol.INFO_SUPPORT_AUDIO]?.let { payload ->
            TuneBudsProtocol.parseUnsignedValue(payload)?.let {
                current = current.copy(supportAudio = it)
            }
        }
        info[TuneBudsProtocol.INFO_VIDEO_LIMIT]?.let { payload ->
            TuneBudsProtocol.parseUnsignedValue(payload)?.let {
                current = current.copy(videoLimitSeconds = it)
            }
        }
        info[TuneBudsProtocol.INFO_AUDIO_LIMIT]?.let { payload ->
            TuneBudsProtocol.parseUnsignedValue(payload)?.let {
                current = current.copy(audioLimitSeconds = it)
            }
        }
        info[TuneBudsProtocol.INFO_SUPPORT_VOLUME_CONTROL]?.let { payload ->
            TuneBudsProtocol.parseUnsignedValue(payload)?.let {
                current = current.copy(supportVolumeControl = it)
            }
        }
        info[TuneBudsProtocol.INFO_CURRENT_VOLUME]?.let { payload ->
            current = current.copy(currentVolume = payload.map { it.toInt() and 0xFF })
        }
        info[TuneBudsProtocol.INFO_SCREEN_CONFIG]?.let { payload ->
            TuneBudsProtocol.parseScreenConfig(payload)?.let {
                current = current.copy(screenConfig = it)
            }
        }
        info[TuneBudsProtocol.INFO_RESOLUTION]?.let { payload ->
            TuneBudsProtocol.parseUnsignedValue(payload)?.let {
                current = current.copy(resolution = it)
            }
        }
        info[TuneBudsProtocol.INFO_SUPPORT_OPUS]?.let { payload ->
            TuneBudsProtocol.parseUnsignedValue(payload)?.let {
                current = current.copy(supportOpus = it)
            }
        }
        info[TuneBudsProtocol.INFO_AI_CHAT_SUPPORT]?.let { payload ->
            TuneBudsProtocol.parseUnsignedValue(payload)?.let {
                current = current.copy(supportAiChat = it)
            }
        }
        info[TuneBudsProtocol.INFO_SUPPORT_APP_LIST]?.let { payload ->
            TuneBudsProtocol.parseUnsignedValue(payload)?.let {
                current = current.copy(supportAppList = it)
            }
        }
        info[TuneBudsProtocol.INFO_WIFI_SUPPORT]?.firstOrNull()?.let { value ->
            current = current.copy(wifiSupportMask = value.toInt() and 0xFF)
        }
        _state.value = current.copy(lastError = null)
    }

    private fun applyBattery(battery: TuneBudsBattery) {
        _state.value = _state.value.copy(
            batteryPercent = battery.percent.coerceIn(0, 100),
            isCharging = battery.isCharging,
            lastError = null,
        )
    }

    private fun handleAiPicture(payload: ByteArray) {
        if (payload.isEmpty()) return
        val status = payload[0].toInt() and 0xFF
        synchronized(aiPictureBuffer) {
            when (status) {
                1 -> {
                    if (payload.size > 1) aiPictureBuffer.write(payload, 1, payload.size - 1)
                }
                0 -> {
                    if (payload.size > 1) aiPictureBuffer.write(payload, 1, payload.size - 1)
                    val image = aiPictureBuffer.toByteArray()
                    aiPictureBuffer.reset()
                    if (image.isNotEmpty()) _aiPhotos.tryEmit(image)
                }
                else -> {
                    aiPictureBuffer.reset()
                    updateError("Ошибка получения AI-фото (код $status)")
                }
            }
        }
    }

    private fun formatHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun launchCommand(operation: String, block: suspend () -> Unit) {
        scope.launch {
            if (!client.isConnected()) {
                updateError("Сначала подключи очки, чтобы $operation")
                return@launch
            }
            runCatching { block() }
                .onFailure { error -> updateError("Не удалось $operation: ${error.message}") }
        }
    }

    private fun updateError(message: String) {
        Log.w(TAG, message)
        _state.value = _state.value.copy(lastError = message)
    }
}
