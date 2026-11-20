package com.fersaiyan.cyanbridge.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsAiEvent
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

data class GeminiConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val model: String = "gemini-2.5-flash",
    val speakResponses: Boolean = true,
)

data class GeminiChatMessage(
    val id: Long = System.currentTimeMillis(),
    val isUser: Boolean,
    val text: String,
    val hasPhoto: Boolean = false,
)

class GeminiGlassAssistant private constructor(private val context: Context) {
    companion object {
        private const val TAG = "GeminiGlassAssistant"
        private const val PREFS_NAME = "aibuds_gemini_prefs"
        private const val KEY_API = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_SPEAK = "speak_responses"

        private const val SAMPLE_RATE_HZ = 16_000
        private const val SCO_ROUTE_TIMEOUT_MS = 2_800L
        private const val PCM_NO_SPEECH_TIMEOUT_MS = 4_800L
        private const val PCM_SILENCE_STOP_MS = 1_300L
        private const val PCM_MAX_RECORD_MS = 12_000L

        private const val OPUS_FIRST_PACKET_TIMEOUT_MS = 5_000L
        private const val OPUS_SILENCE_FINISH_MS = 1_300L
        private const val OPUS_MAX_RECORD_MS = 12_000L
        private const val OPUS_FRAME_SIZE = 80

        private const val NEED_PHOTO_PREFIX = "NEED_PHOTO:"

        private const val GLASSES_SYSTEM_PROMPT =
            "Ты — голосовой ИИ-ассистент в умных очках. Твои ответы сразу озвучиваются голосом (TTS) прямо в уши. " +
                "Отвечай максимально кратко, чётко и по делу (1–3 предложения). " +
                "Никогда не используй Markdown, звёздочки, решётки, тире для списков, эмодзи и технические термины — " +
                "только чистый живой разговорный текст на русском языке. " +
                "Если нужно узнать погоду, новости, курсы или актуальные факты — используй поиск Google."

        private const val VISION_ROUTER_INSTRUCTION =
            "Если пользователь просит посмотреть, глянуть, описать, прочитать, перевести, распознать или проверить " +
                "что-то перед глазами (например: смотри, посмотри, глянь, что это, что передо мной, прочитай, переведи это) — " +
                "НЕ отвечай текстом от себя, а верни строго одну строку в формате: " +
                "NEED_PHOTO: <краткий вопрос пользователя на русском>. " +
                "Во всех остальных случаях сразу дай краткий голосовой ответ на вопрос пользователя."

        private val FALLBACK_MODELS = listOf(
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
        )

        private val VISION_TRIGGERS = listOf(
            "посмотри",
            "смотри",
            "глянь",
            "взгляни",
            "что видишь",
            "что ты видишь",
            "что передо мной",
            "сфоткай",
            "сфотографируй",
            "прочитай",
            "переведи это",
            "что это перед",
        )

        @Volatile
        private var instance: GeminiGlassAssistant? = null

        fun getInstance(context: Context): GeminiGlassAssistant =
            instance ?: synchronized(this) {
                instance ?: GeminiGlassAssistant(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<GeminiConfig> = _config.asStateFlow()

    private val _messages = MutableStateFlow<List<GeminiChatMessage>>(emptyList())
    val messages: StateFlow<List<GeminiChatMessage>> = _messages.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _lastTempleLog = MutableStateFlow("Готов к работе")
    val lastTempleLog: StateFlow<String> = _lastTempleLog.asStateFlow()

    private val busyGuard = AtomicBoolean(false)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var glassesListenerJob: Job? = null
    private var activeVoiceJob: Job? = null
    private var stopCaptureSignal: CompletableDeferred<Unit>? = null
    private var legacyScoReceiver: BroadcastReceiver? = null
    private var audioRouteActive = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("ru", "RU"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
        }
    }

    fun updateConfig(newConfig: GeminiConfig) {
        val cleaned = newConfig.copy(
            apiKey = newConfig.apiKey.trim(),
            baseUrl = newConfig.baseUrl.trim().trimEnd('/').ifBlank {
                "https://generativelanguage.googleapis.com"
            },
            model = newConfig.model.trim().ifBlank { "gemini-2.5-flash" },
        )
        prefs.edit()
            .putString(KEY_API, cleaned.apiKey)
            .putString(KEY_BASE_URL, cleaned.baseUrl)
            .putString(KEY_MODEL, cleaned.model)
            .putBoolean(KEY_SPEAK, cleaned.speakResponses)
            .apply()
        _config.value = cleaned
    }

    fun clearHistory() {
        _messages.value = emptyList()
    }

    fun bindToGlasses(tuneBudsManager: TuneBudsManager) {
        if (glassesListenerJob?.isActive == true) return
        glassesListenerJob = scope.launch {
            tuneBudsManager.aiEvents.collect { event ->
                handleAiChatState(event, tuneBudsManager)
            }
        }
    }

    fun triggerFromVoiceButton(tuneBudsManager: TuneBudsManager) {
        if (!busyGuard.compareAndSet(false, true)) return
        _isBusy.value = true
        _lastTempleLog.value = "Запуск через Bluetooth HFP"
        startScoPcmSession(tuneBudsManager, confirmWithGlasses = false)
    }

    private fun handleAiChatState(event: TuneBudsAiEvent, tuneBudsManager: TuneBudsManager) {
        when (event.chatState) {
            TuneBudsProtocol.AI_CHAT_OPEN_SCO -> {
                if (!busyGuard.compareAndSet(false, true)) return
                _isBusy.value = true
                _lastTempleLog.value = "Дужка: слушаю (SCO)"
                startScoPcmSession(tuneBudsManager, confirmWithGlasses = true)
            }
            TuneBudsProtocol.AI_CHAT_OPEN_OPUS -> {
                if (!busyGuard.compareAndSet(false, true)) return
                _isBusy.value = true
                _lastTempleLog.value = "Дужка: слушаю (OPUS)"
                startOpusVoiceSession(tuneBudsManager)
            }
            TuneBudsProtocol.AI_CHAT_CLOSE -> {
                stopCaptureSignal?.complete(Unit)
            }
            TuneBudsProtocol.AI_CHAT_ERROR -> {
                _lastTempleLog.value = "Ошибка ИИ на очках (код 255)"
                stopCaptureSignal?.complete(Unit)
            }
        }
    }

    private fun startOpusVoiceSession(tuneBudsManager: TuneBudsManager) {
        activeVoiceJob?.cancel()
        activeVoiceJob = scope.launch {
            val wakeLock = acquireWakeLock()
            try {
                tts?.stop()
                val frames = ArrayList<ByteArray>(256)
                val stopSignal = CompletableDeferred<Unit>()
                stopCaptureSignal = stopSignal

                var lastPacketAtMs = 0L
                val collectorJob = launch {
                    tuneBudsManager.aiOpusPackets.collect { payload ->
                        lastPacketAtMs = System.currentTimeMillis()
                        if (payload.size > OPUS_FRAME_SIZE && payload.size % OPUS_FRAME_SIZE == 0) {
                            var off = 0
                            while (off < payload.size) {
                                frames.add(payload.copyOfRange(off, off + OPUS_FRAME_SIZE))
                                off += OPUS_FRAME_SIZE
                            }
                        } else if (payload.isNotEmpty()) {
                            frames.add(payload)
                        }
                        if (frames.size == 1) {
                            _lastTempleLog.value = "Слушаю голос с очков..."
                        }
                    }
                }

                try {
                    tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_OPEN_OPUS)
                    val startedAt = System.currentTimeMillis()
                    while (!stopSignal.isCompleted && isActive) {
                        delay(100L)
                        val now = System.currentTimeMillis()
                        if (now - startedAt >= OPUS_MAX_RECORD_MS) break
                        if (lastPacketAtMs == 0L) {
                            if (now - startedAt >= OPUS_FIRST_PACKET_TIMEOUT_MS) break
                        } else if (now - lastPacketAtMs >= OPUS_SILENCE_FINISH_MS) {
                            break
                        }
                    }
                } finally {
                    collectorJob.cancel()
                    stopCaptureSignal = null
                    tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_CLOSE)
                }

                if (frames.isEmpty()) {
                    _lastTempleLog.value = "Не получил аудио от очков"
                    return@launch
                }

                _lastTempleLog.value = "Отправляю запрос в Gemini..."
                val oggBytes = OggOpusMuxer.mux(frames)
                processNativeAudioWithGemini(
                    audioBytes = oggBytes,
                    audioMime = "audio/ogg",
                    tuneBudsManager = tuneBudsManager,
                )
            } finally {
                releaseWakeLock(wakeLock)
                busyGuard.set(false)
                _isBusy.value = false
            }
        }
    }

    private fun startScoPcmSession(tuneBudsManager: TuneBudsManager, confirmWithGlasses: Boolean) {
        activeVoiceJob?.cancel()
        activeVoiceJob = scope.launch {
            val wakeLock = acquireWakeLock()
            try {
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    val msg = "Нет разрешения на микрофон."
                    _lastTempleLog.value = msg
                    appendMessage(isUser = false, text = msg)
                    if (confirmWithGlasses) tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_CLOSE)
                    return@launch
                }

                tts?.stop()
                if (confirmWithGlasses) {
                    tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_OPEN_SCO)
                }

                val stopSignal = CompletableDeferred<Unit>()
                stopCaptureSignal = stopSignal

                val usingBtMic = openBluetoothMicRoute()
                _lastTempleLog.value = if (usingBtMic) "Слушаю (микрофон очков)..." else "Слушаю (микрофон телефона)..."

                val pcmData = try {
                    recordPcmWithVad(preferBluetoothInput = usingBtMic, stopSignal = stopSignal)
                } finally {
                    stopCaptureSignal = null
                    closeAudioRoute()
                    if (confirmWithGlasses) {
                        tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_CLOSE)
                    }
                }

                if (pcmData == null || pcmData.size < SAMPLE_RATE_HZ / 2) {
                    _lastTempleLog.value = "Ничего не услышал"
                    return@launch
                }

                _lastTempleLog.value = "Отправляю запрос в Gemini..."
                val wavBytes = WavEncoder.encode(pcmData, sampleRate = SAMPLE_RATE_HZ)
                processNativeAudioWithGemini(
                    audioBytes = wavBytes,
                    audioMime = "audio/wav",
                    tuneBudsManager = tuneBudsManager,
                )
            } finally {
                closeAudioRoute()
                releaseWakeLock(wakeLock)
                busyGuard.set(false)
                _isBusy.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordPcmWithVad(
        preferBluetoothInput: Boolean,
        stopSignal: CompletableDeferred<Unit>,
    ): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE_HZ / 5)

        val primarySource = if (preferBluetoothInput) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        var record = AudioRecord(
            primarySource,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
        }

        if (preferBluetoothInput && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val btInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (btInput != null) {
                record.preferredDevice = btInput
            }
        }

        val pcmOut = ByteArrayOutputStream(SAMPLE_RATE_HZ * 2 * 6)
        val frameShorts = ShortArray(320)
        val frameBytes = ByteArray(frameShorts.size * 2)

        try {
            record.startRecording()
            val startedAt = System.currentTimeMillis()
            var speechDetected = false
            var lastVoiceAt = startedAt
            var noiseFloor = 220.0
            var framesRead = 0

            while (!stopSignal.isCompleted) {
                val read = record.read(frameShorts, 0, frameShorts.size)
                if (read <= 0) break

                var sumSq = 0.0
                for (i in 0 until read) {
                    val s = frameShorts[i].toInt()
                    sumSq += s.toDouble() * s.toDouble()
                    frameBytes[i * 2] = (s and 0xFF).toByte()
                    frameBytes[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
                }
                pcmOut.write(frameBytes, 0, read * 2)

                val rms = sqrt(sumSq / read)
                framesRead++
                val now = System.currentTimeMillis()

                if (framesRead <= 12) {
                    noiseFloor = (noiseFloor * 0.7) + (rms * 0.3)
                } else {
                    val threshold = max(340.0, noiseFloor * 2.7)
                    if (rms >= threshold) {
                        speechDetected = true
                        lastVoiceAt = now
                    } else if (!speechDetected) {
                        noiseFloor = (noiseFloor * 0.92) + (rms * 0.08)
                    }
                }

                val elapsed = now - startedAt
                if (elapsed >= PCM_MAX_RECORD_MS) break
                if (!speechDetected && elapsed >= PCM_NO_SPEECH_TIMEOUT_MS) break
                if (speechDetected && (now - lastVoiceAt) >= PCM_SILENCE_STOP_MS) break
            }

            return if (speechDetected) pcmOut.toByteArray() else null
        } catch (err: Throwable) {
            Log.w(TAG, "AudioRecord capture error: ${err.message}")
            return null
        } finally {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
        }
    }

    private suspend fun openBluetoothMicRoute(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return false

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioRouteActive = true

            if (audioManager.communicationDevice?.id == btDevice.id) {
                return true
            }

            val switched = CompletableDeferred<Boolean>()
            val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                if (device?.id == btDevice.id) {
                    switched.complete(true)
                }
            }
            val executor = ContextCompat.getMainExecutor(context)
            audioManager.addOnCommunicationDeviceChangedListener(executor, listener)
            return try {
                val accepted = audioManager.setCommunicationDevice(btDevice)
                if (!accepted) {
                    closeAudioRoute()
                    false
                } else {
                    val ok = withTimeoutOrNull(SCO_ROUTE_TIMEOUT_MS) { switched.await() } == true ||
                        audioManager.communicationDevice?.id == btDevice.id
                    if (!ok) closeAudioRoute()
                    ok
                }
            } finally {
                runCatching { audioManager.removeOnCommunicationDeviceChangedListener(listener) }
            }
        } else {
            @Suppress("DEPRECATION")
            if (!audioManager.isBluetoothScoAvailableOffCall) return false

            val connected = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: -1
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        connected.complete(true)
                    }
                }
            }
            closeAudioRoute()
            legacyScoReceiver = receiver
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            runCatching {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                audioRouteActive = true
            }.onFailure {
                closeAudioRoute()
                return false
            }

            val ok = withTimeoutOrNull(SCO_ROUTE_TIMEOUT_MS) { connected.await() } == true
            if (!ok) closeAudioRoute()
            return ok
        }
    }

    private fun closeAudioRoute() {
        legacyScoReceiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        legacyScoReceiver = null
        if (!audioRouteActive) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        audioRouteActive = false
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiBuds:VoiceSession").apply {
            setReferenceCounted(false)
            acquire(60_000L)
        }
    }.getOrNull()

    private fun releaseWakeLock(wl: PowerManager.WakeLock?) {
        runCatching {
            if (wl != null && wl.isHeld) wl.release()
        }
    }

    suspend fun ask(
        prompt: String,
        withGlassPhoto: Boolean = false,
        tuneBudsManager: TuneBudsManager? = null,
    ): String {
        val cfg = _config.value
        if (cfg.apiKey.isBlank()) {
            val msg = "Укажи API-ключ Gemini в настройках."
            appendMessage(isUser = false, text = msg)
            return msg
        }

        if (!busyGuard.compareAndSet(false, true)) {
            return "Подожди, обрабатываю предыдущий запрос."
        }
        _isBusy.value = true

        val lower = prompt.lowercase(Locale("ru", "RU"))
        val eagerPhoto = withGlassPhoto || VISION_TRIGGERS.any { lower.contains(it) }
        appendMessage(isUser = true, text = prompt, hasPhoto = eagerPhoto)

        return try {
            val canUseGlasses = tuneBudsManager != null && tuneBudsManager.isConnected()
            val initialPhoto = if (eagerPhoto && canUseGlasses) {
                _lastTempleLog.value = "Делаю фото с очков..."
                val photoRes = tuneBudsManager!!.capturePhotoForAiResult()
                if (photoRes.isFailure) {
                    val err = photoRes.exceptionOrNull()?.message ?: "Не удалось сделать фото с очков."
                    deliverReply(err, cfg)
                    return err
                }
                photoRes.getOrNull()
            } else {
                null
            }

            val firstText = sendGeminiWithFallback(
                cfg = cfg,
                prompt = if (initialPhoto == null && canUseGlasses) {
                    "$VISION_ROUTER_INSTRUCTION\n\nЗапрос пользователя: $prompt"
                } else {
                    prompt
                },
                jpegBytes = initialPhoto,
                audioBytes = null,
                audioMime = null,
            )

            val needPhotoQuestion = extractNeedPhotoQuestion(firstText)
            val finalText = if (needPhotoQuestion != null && canUseGlasses) {
                snapAndAskWithPhoto(
                    question = needPhotoQuestion.ifBlank { prompt },
                    cfg = cfg,
                    tuneBudsManager = tuneBudsManager!!,
                )
            } else {
                firstText
            }

            deliverReply(finalText, cfg)
        } catch (error: Throwable) {
            val errText = "Ошибка Gemini: ${error.message ?: "нет ответа"}"
            Log.e(TAG, errText, error)
            _lastTempleLog.value = errText
            appendMessage(isUser = false, text = errText)
            errText
        } finally {
            busyGuard.set(false)
            _isBusy.value = false
        }
    }

    private suspend fun processNativeAudioWithGemini(
        audioBytes: ByteArray,
        audioMime: String,
        tuneBudsManager: TuneBudsManager,
    ) {
        val cfg = _config.value
        if (cfg.apiKey.isBlank()) {
            val msg = "Укажи API-ключ Gemini в настройках."
            appendMessage(isUser = false, text = msg)
            return
        }

        try {
            val canUseGlasses = tuneBudsManager.isConnected()
            val prompt = if (canUseGlasses) {
                "Выслушай голосовой запрос в аудио.\n$VISION_ROUTER_INSTRUCTION"
            } else {
                "Выслушай голосовой запрос в аудио и сразу дай краткий ответ на русском."
            }

            val firstText = sendGeminiWithFallback(
                cfg = cfg,
                prompt = prompt,
                jpegBytes = null,
                audioBytes = audioBytes,
                audioMime = audioMime,
            )

            val needPhotoQuestion = extractNeedPhotoQuestion(firstText)
            if (needPhotoQuestion != null && canUseGlasses) {
                val question = needPhotoQuestion.ifBlank { "Опиши кратко, что сейчас передо мной." }
                appendMessage(isUser = true, text = question, hasPhoto = true)
                val finalText = snapAndAskWithPhoto(
                    question = question,
                    cfg = cfg,
                    tuneBudsManager = tuneBudsManager,
                )
                deliverReply(finalText, cfg)
            } else {
                appendMessage(isUser = true, text = "[Голосовой запрос с очков]", hasPhoto = false)
                deliverReply(firstText, cfg)
            }
        } catch (error: Throwable) {
            val errText = "Ошибка Gemini: ${error.message ?: "нет ответа"}"
            Log.e(TAG, errText, error)
            _lastTempleLog.value = errText
            appendMessage(isUser = false, text = errText)
            if (cfg.speakResponses) speak("Не удалось связаться с ассистентом.")
        }
    }

    private suspend fun snapAndAskWithPhoto(
        question: String,
        cfg: GeminiConfig,
        tuneBudsManager: TuneBudsManager,
    ): String {
        _lastTempleLog.value = "Делаю фото с очков..."
        val photoRes = tuneBudsManager.capturePhotoForAiResult()
        val photoBytes = photoRes.getOrElse { err ->
            return err.message ?: "Не удалось получить фото с очков."
        }
        _lastTempleLog.value = "Анализирую фото..."
        return sendGeminiWithFallback(
            cfg = cfg,
            prompt = question,
            jpegBytes = photoBytes,
            audioBytes = null,
            audioMime = null,
        )
    }

    private fun extractNeedPhotoQuestion(rawText: String): String? {
        val trimmed = rawText.trim()
        val idx = trimmed.indexOf(NEED_PHOTO_PREFIX, ignoreCase = true)
        if (idx < 0) return null
        return trimmed.substring(idx + NEED_PHOTO_PREFIX.length)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
    }

    private fun deliverReply(rawReply: String, cfg: GeminiConfig): String {
        val cleanReply = cleanForSpeech(rawReply)
        appendMessage(isUser = false, text = cleanReply)
        _lastTempleLog.value = "Ответ получен"
        if (cfg.speakResponses) {
            speak(cleanReply)
        }
        return cleanReply
    }

    private suspend fun sendGeminiWithFallback(
        cfg: GeminiConfig,
        prompt: String,
        jpegBytes: ByteArray?,
        audioBytes: ByteArray?,
        audioMime: String?,
    ): String {
        val modelsToTry = (listOf(cfg.model) + FALLBACK_MODELS).distinct()
        var lastError: Throwable? = null

        for ((index, modelName) in modelsToTry.withIndex()) {
            val attempts = if (index == 0) 2 else 1
            repeat(attempts) { attempt ->
                try {
                    return sendGeminiRequest(
                        cfg = cfg.copy(model = modelName),
                        prompt = prompt,
                        jpegBytes = jpegBytes,
                        audioBytes = audioBytes,
                        audioMime = audioMime,
                        enableGoogleSearch = true,
                    )
                } catch (err: Throwable) {
                    val msg = err.message.orEmpty()
                    if (msg.contains("HTTP 400")) {
                        return sendGeminiRequest(
                            cfg = cfg.copy(model = modelName),
                            prompt = prompt,
                            jpegBytes = jpegBytes,
                            audioBytes = audioBytes,
                            audioMime = audioMime,
                            enableGoogleSearch = false,
                        )
                    }
                    lastError = err
                    val isTransient = msg.contains("HTTP 503") || msg.contains("HTTP 429") || msg.contains("HTTP 500")
                    if (!isTransient) throw err
                    Log.w(TAG, "Model $modelName transient error (attempt ${attempt + 1}): $msg")
                    delay(900L)
                }
            }
        }
        throw lastError ?: IllegalStateException("Все модели Gemini временно перегружены")
    }

    private suspend fun sendGeminiRequest(
        cfg: GeminiConfig,
        prompt: String,
        jpegBytes: ByteArray?,
        audioBytes: ByteArray?,
        audioMime: String?,
        enableGoogleSearch: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val url = "${cfg.baseUrl}/v1beta/models/${cfg.model}:generateContent?key=${cfg.apiKey}"

        val systemInstruction = JSONObject().put(
            "parts",
            JSONArray().put(JSONObject().put("text", GLASSES_SYSTEM_PROMPT)),
        )

        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            if (audioBytes != null && audioBytes.isNotEmpty() && !audioMime.isNullOrBlank()) {
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", audioMime)
                            .put("data", Base64.encodeToString(audioBytes, Base64.NO_WRAP)),
                    ),
                )
            }
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP)),
                    ),
                )
            }
        }

        val bodyObj = JSONObject()
            .put("system_instruction", systemInstruction)
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))

        if (enableGoogleSearch) {
            bodyObj.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${rawBody.take(180)}")
            }
            val root = JSONObject(rawBody)
            val candidates = root.optJSONArray("candidates")
                ?: throw IllegalStateException("Пустой ответ от модели")
            val respParts = candidates
                .optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return@withContext "Модель вернула пустой текст."

            val textParts = ArrayList<String>()
            for (i in 0 until respParts.length()) {
                val t = respParts.optJSONObject(i)?.optString("text")?.trim().orEmpty()
                if (t.isNotEmpty()) textParts += t
            }
            textParts.joinToString(" ").ifBlank { "Модель вернула пустой текст." }
        }
    }

    private fun cleanForSpeech(text: String): String = text
        .replace(Regex("[*_`#~>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(cleanForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, "gemini_${System.currentTimeMillis()}")
    }

    private fun appendMessage(isUser: Boolean, text: String, hasPhoto: Boolean = false) {
        _messages.value = (_messages.value + GeminiChatMessage(isUser = isUser, text = text, hasPhoto = hasPhoto))
            .takeLast(30)
    }

    private fun loadConfig(): GeminiConfig = GeminiConfig(
        apiKey = prefs.getString(KEY_API, "").orEmpty(),
        baseUrl = prefs.getString(KEY_BASE_URL, "https://generativelanguage.googleapis.com")
            .orEmpty()
            .ifBlank { "https://generativelanguage.googleapis.com" },
        model = prefs.getString(KEY_MODEL, "gemini-2.5-flash").orEmpty().ifBlank { "gemini-2.5-flash" },
        speakResponses = prefs.getBoolean(KEY_SPEAK, true),
    )
}

/** Упаковщик 16-битного моно PCM в стандартный WAV (audio/wav). */
private object WavEncoder {
    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(44 + pcm.size)

        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte(); out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        putIntLe(out, 4, 36 + pcm.size)
        out[8] = 'W'.code.toByte(); out[9] = 'A'.code.toByte(); out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()

        out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte(); out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
        putIntLe(out, 16, 16)
        putShortLe(out, 20, 1)
        putShortLe(out, 22, channels)
        putIntLe(out, 24, sampleRate)
        putIntLe(out, 28, byteRate)
        putShortLe(out, 32, blockAlign)
        putShortLe(out, 34, bitsPerSample)

        out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte(); out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
        putIntLe(out, 40, pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    private fun putShortLe(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putIntLe(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}

/** Упаковщик сырых 20ms OPUS-кадров (0xB2) в контейнер Ogg Opus (RFC 7845 + RFC 3533). */
private object OggOpusMuxer {
    private const val STREAM_SERIAL = 0x54425544
    private const val SAMPLES_PER_20MS_48K = 960L
    private const val FRAMES_PER_PAGE = 50

    private val crcTable = IntArray(256).apply {
        for (i in 0..255) {
            var r = i shl 24
            repeat(8) {
                r = if ((r and Int.MIN_VALUE) != 0) (r shl 1) xor 0x04C11DB7 else (r shl 1)
            }
            this[i] = r
        }
    }

    fun mux(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(frames.size * 85 + 256)
        var pageSeq = 0

        val opusHead = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1,
            1,
            0x38, 0x01,
            0x80.toByte(), 0x3E, 0x00, 0x00,
            0x00, 0x00,
            0x00,
        )
        writePage(out, listOf(opusHead), headerType = 0x02, granule = 0L, seq = pageSeq++)

        val vendor = "AiBuds".toByteArray(StandardCharsets.UTF_8)
        val opusTags = ByteArrayOutputStream(16 + vendor.size).apply {
            write("OpusTags".toByteArray(StandardCharsets.US_ASCII))
            writeIntLe(vendor.size)
            write(vendor)
            writeIntLe(0)
        }.toByteArray()
        writePage(out, listOf(opusTags), headerType = 0x00, granule = 0L, seq = pageSeq++)

        var granule = 0L
        var offset = 0
        while (offset < frames.size) {
            val end = minOf(offset + FRAMES_PER_PAGE, frames.size)
            val chunk = frames.subList(offset, end)
            granule += chunk.size.toLong() * SAMPLES_PER_20MS_48K
            val isLast = end == frames.size
            writePage(
                out = out,
                packets = chunk,
                headerType = if (isLast) 0x04 else 0x00,
                granule = granule,
                seq = pageSeq++,
            )
            offset = end
        }
        return out.toByteArray()
    }

    private fun writePage(
        out: ByteArrayOutputStream,
        packets: List<ByteArray>,
        headerType: Int,
        granule: Long,
        seq: Int,
    ) {
        val segments = ByteArrayOutputStream()
        var payloadSize = 0
        for (pkt in packets) {
            var rem = pkt.size
            while (rem >= 255) {
                segments.write(255)
                rem -= 255
            }
            segments.write(rem)
            payloadSize += pkt.size
        }
        val segBytes = segments.toByteArray()

        val page = ByteArray(27 + segBytes.size + payloadSize)
        page[0] = 'O'.code.toByte()
        page[1] = 'g'.code.toByte()
        page[2] = 'g'.code.toByte()
        page[3] = 'S'.code.toByte()
        page[4] = 0
        page[5] = headerType.toByte()
        putLongLe(page, 6, granule)
        putIntLe(page, 14, STREAM_SERIAL)
        putIntLe(page, 18, seq)
        page[26] = segBytes.size.toByte()
        System.arraycopy(segBytes, 0, page, 27, segBytes.size)

        var pos = 27 + segBytes.size
        for (pkt in packets) {
            System.arraycopy(pkt, 0, page, pos, pkt.size)
            pos += pkt.size
        }

        val crc = oggCrc32(page)
        putIntLe(page, 22, crc)
        out.write(page)
    }

    private fun oggCrc32(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private fun putIntLe(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putLongLe(buf: ByteArray, offset: Int, v: Long) {
        for (i in 0..7) {
            buf[offset + i] = ((v ushr (i * 8)) and 0xFFL).toByte()
        }
    }

    private fun ByteArrayOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }
}
