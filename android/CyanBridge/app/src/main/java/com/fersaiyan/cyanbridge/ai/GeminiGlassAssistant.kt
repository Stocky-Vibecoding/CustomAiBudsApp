package com.fersaiyan.cyanbridge.ai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.widget.Toast
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

data class GeminiConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val model: String = "gemini-2.5-flash",
    val speakResponses: Boolean = true,
    val autoVisionOnTempleHold: Boolean = false,
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
        private const val KEY_AUTO_VISION = "auto_vision"

        private const val SCO_CONNECT_TIMEOUT_MS = 5_000L
        private const val VOICE_WATCHDOG_MS = 16_000L
        private const val OPUS_FIRST_PACKET_TIMEOUT_MS = 5_000L
        private const val OPUS_SILENCE_FINISH_MS = 1_400L
        private const val OPUS_MAX_RECORD_MS = 12_000L
        private const val OPUS_FRAME_SIZE = 80

        private const val GLASSES_SYSTEM_PROMPT =
            "Ты — голосовой ИИ-ассистент в умных очках. Твои ответы сразу озвучиваются голосом (TTS) прямо в уши пользователю. " +
                "Отвечай максимально кратко, чётко и по делу (1–3 предложения). " +
                "Никогда не используй Markdown, звёздочки, решётки, тире для списков, эмодзи и любые лишние символы — " +
                "только чистый живой разговорный текст на русском языке."

        private val FALLBACK_MODELS = listOf(
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
        )

        private val VISION_TRIGGERS = listOf(
            "посмотри",
            "глянь",
            "взгляни",
            "что видишь",
            "что ты видишь",
            "что передо мной",
            "сфоткай",
            "сфотографируй",
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
    private val mainHandler = Handler(Looper.getMainLooper())
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

    private val _lastTempleLog = MutableStateFlow("Готов к работе (ожидание 0xB0 / HFP)")
    val lastTempleLog: StateFlow<String> = _lastTempleLog.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var glassesListenerJob: Job? = null
    private var activeVoiceJob: Job? = null
    private var opusStopSignal: CompletableDeferred<Unit>? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var scoOpened = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("ru", "RU"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
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
            .putBoolean(KEY_AUTO_VISION, cleaned.autoVisionOnTempleHold)
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

    /** Запуск по системному HFP VOICE_COMMAND (если очки шлют AT+BVRA вместо 0xB0). */
    fun triggerFromVoiceButton(tuneBudsManager: TuneBudsManager) {
        if (_isBusy.value) return
        _lastTempleLog.value = "Запуск через Bluetooth HFP"
        startScoVoiceSession(tuneBudsManager, confirmWithGlasses = false)
    }

    /** Точный обработчик SpeechAiSessionImpl.handleChatState (0xB0 NOTIFICATION). */
    private fun handleAiChatState(event: TuneBudsAiEvent, tuneBudsManager: TuneBudsManager) {
        when (event.chatState) {
            TuneBudsProtocol.AI_CHAT_OPEN_SCO -> {
                if (_isBusy.value) return
                _lastTempleLog.value = "Дужка: старт ИИ (режим SCO)"
                startScoVoiceSession(tuneBudsManager, confirmWithGlasses = true)
            }
            TuneBudsProtocol.AI_CHAT_OPEN_OPUS -> {
                if (_isBusy.value) return
                _lastTempleLog.value = "Дужка: старт ИИ (поток OPUS 0xB2)"
                startOpusVoiceSession(tuneBudsManager)
            }
            TuneBudsProtocol.AI_CHAT_CLOSE -> {
                opusStopSignal?.complete(Unit)
                if (_isBusy.value && speechRecognizer != null) {
                    mainHandler.post { runCatching { speechRecognizer?.stopListening() } }
                }
            }
            TuneBudsProtocol.AI_CHAT_ERROR -> {
                _lastTempleLog.value = "Очки сообщили об ошибке ИИ (код 255)"
                opusStopSignal?.complete(Unit)
            }
        }
    }

    /**
     * Режим 2 (AI_CHAT_OPEN_OPUS):
     * Очки сами пишут голос в OPUS (кадры по 80 байт / 20 мс) и шлют по SPP в 0xB2.
     * Собираем кадры, пакуем в Ogg Opus (audio/ogg) и отправляем напрямую в Gemini.
     */
    private fun startOpusVoiceSession(tuneBudsManager: TuneBudsManager) {
        activeVoiceJob?.cancel()
        activeVoiceJob = scope.launch {
            _isBusy.value = true
            tts?.stop()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Слушаю (OPUS с очков)...", Toast.LENGTH_SHORT).show()
            }

            val frames = ArrayList<ByteArray>(256)
            val stopSignal = CompletableDeferred<Unit>()
            opusStopSignal = stopSignal

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
                        _lastTempleLog.value = "Принимаю голос с очков..."
                    }
                }
            }

            try {
                // Подтверждаем открытие потока (SpeechSessionImpl.executeOpenCommand -> sendChatState(2))
                tuneBudsManager.setAiChatStateBlocking(TuneBudsProtocol.AI_CHAT_OPEN_OPUS)

                val startedAt = System.currentTimeMillis()
                while (!stopSignal.isCompleted) {
                    delay(120L)
                    val now = System.currentTimeMillis()
                    if (now - startedAt >= OPUS_MAX_RECORD_MS) break
                    if (lastPacketAtMs == 0L) {
                        if (now - startedAt >= OPUS_FIRST_PACKET_TIMEOUT_MS) break
                    } else {
                        if (now - lastPacketAtMs >= OPUS_SILENCE_FINISH_MS) break
                    }
                }
            } finally {
                collectorJob.cancel()
                opusStopSignal = null
                tuneBudsManager.setAiChatStateBlocking(TuneBudsProtocol.AI_CHAT_CLOSE)
            }

            if (frames.isEmpty()) {
                _lastTempleLog.value = "Очки не прислали OPUS-аудио"
                _isBusy.value = false
                return@launch
            }

            val durationSec = (frames.size * 20) / 1000f
            _lastTempleLog.value = "Получено %.1f сек голоса — отправляю в Gemini...".format(durationSec)

            val oggBytes = OggOpusMuxer.mux(frames)
            askWithNativeAudio(
                audioBytes = oggBytes,
                audioMime = "audio/ogg",
                withGlassPhoto = _config.value.autoVisionOnTempleHold,
                tuneBudsManager = tuneBudsManager,
            )
        }
    }

    /**
     * Режим 1 (AI_CHAT_OPEN_SCO) или системный HFP VOICE_COMMAND:
     * Поднимает Bluetooth SCO через BroadcastReceiver (как в ScoReader.java) без гаданий по delay.
     */
    private fun startScoVoiceSession(tuneBudsManager: TuneBudsManager, confirmWithGlasses: Boolean) {
        activeVoiceJob?.cancel()
        activeVoiceJob = scope.launch {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMic) {
                val msg = "Нет разрешения на микрофон"
                _lastTempleLog.value = msg
                appendMessage(isUser = false, text = msg)
                if (confirmWithGlasses) tuneBudsManager.setAiChatStateBlocking(TuneBudsProtocol.AI_CHAT_CLOSE)
                return@launch
            }

            _isBusy.value = true
            tts?.stop()

            if (confirmWithGlasses) {
                tuneBudsManager.setAiChatStateBlocking(TuneBudsProtocol.AI_CHAT_OPEN_SCO)
            }

            val scoReady = openScoAndAwaitConnected()
            _lastTempleLog.value = if (scoReady) "Слушаю (микрофон очков SCO)..." else "Слушаю (микрофон телефона)..."

            withContext(Dispatchers.Main) {
                val recognizer = createBestRecognizer()
                if (recognizer == null) {
                    val msg = "Сервис распознавания речи не найден"
                    _lastTempleLog.value = msg
                    appendMessage(isUser = false, text = msg)
                    closeSco()
                    _isBusy.value = false
                    if (confirmWithGlasses) tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_CLOSE)
                    return@withContext
                }

                Toast.makeText(context, "Слушаю... Говори", Toast.LENGTH_SHORT).show()
                destroyRecognizer()
                speechRecognizer = recognizer

                val watchdogJob = scope.launch(Dispatchers.Main) {
                    delay(VOICE_WATCHDOG_MS)
                    if (_isBusy.value && speechRecognizer != null) {
                        _lastTempleLog.value = "Таймаут ожидания речи"
                        destroyRecognizer()
                        closeSco()
                        _isBusy.value = false
                        if (confirmWithGlasses) tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_CLOSE)
                    }
                }

                recognizer.setRecognitionListener(
                    object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _lastTempleLog.value = "Говорите..."
                        }

                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() {
                            _lastTempleLog.value = "Распознаю..."
                        }

                        override fun onError(error: Int) {
                            watchdogJob.cancel()
                            destroyRecognizer()
                            closeSco()
                            val errMsg = speechErrorToRussian(error)
                            Log.w(TAG, "SpeechRecognizer error=$error ($errMsg)")
                            _lastTempleLog.value = errMsg
                            _isBusy.value = false
                            if (confirmWithGlasses) tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_CLOSE)
                        }

                        override fun onResults(results: Bundle?) {
                            watchdogJob.cancel()
                            destroyRecognizer()
                            closeSco()
                            if (confirmWithGlasses) tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_CLOSE)

                            val spoken = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty()

                            if (spoken.isBlank()) {
                                _lastTempleLog.value = "Речь не распознана"
                                _isBusy.value = false
                                return
                            }

                            val lower = spoken.lowercase(Locale("ru", "RU"))
                            val wantsPhoto = _config.value.autoVisionOnTempleHold ||
                                VISION_TRIGGERS.any { lower.contains(it) }

                            _lastTempleLog.value = if (wantsPhoto) "Запрос с фото: «$spoken»" else "Запрос: «$spoken»"
                            scope.launch {
                                ask(
                                    prompt = spoken,
                                    withGlassPhoto = wantsPhoto,
                                    tuneBudsManager = tuneBudsManager,
                                )
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                            if (!partial.isNullOrBlank()) {
                                _lastTempleLog.value = "Слышу: «$partial»"
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    },
                )

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_200L)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                runCatching {
                    recognizer.startListening(intent)
                }.onFailure { err ->
                    watchdogJob.cancel()
                    destroyRecognizer()
                    closeSco()
                    _lastTempleLog.value = "Ошибка микрофона: ${err.message}"
                    _isBusy.value = false
                    if (confirmWithGlasses) tuneBudsManager.setAiChatState(TuneBudsProtocol.AI_CHAT_CLOSE)
                }
            }
        }
    }

    /** Точный аналог ScoReader.java: подписка на ACTION_SCO_AUDIO_STATE_UPDATED + ожидание CONNECTED. */
    private suspend fun openScoAndAwaitConnected(): Boolean {
        @Suppress("DEPRECATION")
        if (!audioManager.isBluetoothScoAvailableOffCall) return false

        val connected = CompletableDeferred<Boolean>()
        var stickyConfirmJob: Job? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: -1
                if (isInitialStickyBroadcast) {
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        stickyConfirmJob = scope.launch {
                            delay(800L)
                            connected.complete(true)
                        }
                    }
                    return
                }
                stickyConfirmJob?.cancel()
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    connected.complete(true)
                }
            }
        }

        closeSco()
        scoReceiver = receiver
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
            scoOpened = true
        }.onFailure {
            closeSco()
            return false
        }

        val ok = withTimeoutOrNull(SCO_CONNECT_TIMEOUT_MS) { connected.await() } == true
        stickyConfirmJob?.cancel()
        if (!ok) {
            closeSco()
        }
        return ok
    }

    private fun closeSco() {
        scoReceiver?.let { r ->
            runCatching { context.unregisterReceiver(r) }
        }
        scoReceiver = null
        if (scoOpened) {
            runCatching {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            }
            scoOpened = false
        }
    }

    private fun createBestRecognizer(): SpeechRecognizer? {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            return runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext) }.getOrNull()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            return runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
            }.getOrNull()
        }
        return null
    }

    private fun destroyRecognizer() {
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }

    private fun speechErrorToRussian(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи аудио"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания"
        SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ничего не услышал"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Микрофон занят"
        SpeechRecognizer.ERROR_SERVER -> "Сервер речи недоступен"
        else -> "Ошибка речи (код $code)"
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
            _isBusy.value = false
            return msg
        }

        _isBusy.value = true
        appendMessage(isUser = true, text = prompt, hasPhoto = withGlassPhoto)

        return try {
            val photoBytes = if (withGlassPhoto && tuneBudsManager != null && tuneBudsManager.isConnected()) {
                tuneBudsManager.capturePhotoForAi(timeoutMs = 15_000L)
            } else {
                null
            }

            val rawReply = sendGeminiWithFallback(cfg, prompt, photoBytes, null, null)
            val cleanReply = cleanForSpeech(rawReply)
            appendMessage(isUser = false, text = cleanReply)
            if (cfg.speakResponses) {
                speak(cleanReply)
            }
            cleanReply
        } catch (error: Throwable) {
            val errText = "Ошибка Gemini: ${error.message ?: "нет ответа"}"
            Log.e(TAG, errText, error)
            appendMessage(isUser = false, text = errText)
            errText
        } finally {
            _isBusy.value = false
        }
    }

    private suspend fun askWithNativeAudio(
        audioBytes: ByteArray,
        audioMime: String,
        withGlassPhoto: Boolean,
        tuneBudsManager: TuneBudsManager,
    ) {
        val cfg = _config.value
        if (cfg.apiKey.isBlank()) {
            val msg = "Укажи API-ключ Gemini в настройках."
            appendMessage(isUser = false, text = msg)
            _isBusy.value = false
            return
        }

        appendMessage(isUser = true, text = "[Голосовой запрос с очков]", hasPhoto = withGlassPhoto)

        try {
            val photoBytes = if (withGlassPhoto && tuneBudsManager.isConnected()) {
                tuneBudsManager.capturePhotoForAi(timeoutMs = 15_000L)
            } else {
                null
            }

            val rawReply = sendGeminiWithFallback(
                cfg = cfg,
                prompt = "Выслушай голосовой запрос пользователя в прикреплённом аудио и ответь кратко и по делу.",
                jpegBytes = photoBytes,
                audioBytes = audioBytes,
                audioMime = audioMime,
            )
            val cleanReply = cleanForSpeech(rawReply)
            appendMessage(isUser = false, text = cleanReply)
            _lastTempleLog.value = "Ответ получен"
            if (cfg.speakResponses) {
                speak(cleanReply)
            }
        } catch (error: Throwable) {
            val errText = "Ошибка Gemini: ${error.message ?: "нет ответа"}"
            Log.e(TAG, errText, error)
            _lastTempleLog.value = errText
            appendMessage(isUser = false, text = errText)
        } finally {
            _isBusy.value = false
        }
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
                    return sendGeminiRequest(cfg.copy(model = modelName), prompt, jpegBytes, audioBytes, audioMime)
                } catch (err: Throwable) {
                    lastError = err
                    val msg = err.message.orEmpty()
                    val isTransient = msg.contains("HTTP 503") || msg.contains("HTTP 429") || msg.contains("HTTP 500")
                    if (!isTransient) throw err
                    Log.w(TAG, "Model $modelName transient error (attempt ${attempt + 1}): $msg")
                    delay(1_000L)
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

        val bodyJson = JSONObject()
            .put("system_instruction", systemInstruction)
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .toString()

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${rawBody.take(180)}")
            }
            val root = JSONObject(rawBody)
            val candidates = root.optJSONArray("candidates")
                ?: throw IllegalStateException("Пустой ответ от модели")
            val text = candidates
                .optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
            if (text.isNullOrBlank()) "Модель вернула пустой текст." else text
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
        autoVisionOnTempleHold = prefs.getBoolean(KEY_AUTO_VISION, false),
    )
}

/**
 * Стандартный упаковщик сырых 20ms OPUS-кадров (0xB2) в контейнер Ogg Opus (RFC 7845 + RFC 3533)
 * для прямой отправки в Gemini (audio/ogg) без нативных библиотек.
 */
private object OggOpusMuxer {
    private const val STREAM_SERIAL = 0x54425544 // "TBUD"
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

        // Страница 0 (BOS): OpusHead (19 байт, 1 канал, 16kHz input, pre-skip 312)
        val opusHead = byteArrayOf(
            'O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte(),
            'H'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(),
            1, // version
            1, // channels
            0x38, 0x01, // pre-skip = 312 LE
            0x80.toByte(), 0x3E, 0x00, 0x00, // input sample rate = 16000 LE
            0x00, 0x00, // output gain = 0
            0x00, // channel mapping family = 0
        )
        writePage(out, listOf(opusHead), headerType = 0x02, granule = 0L, seq = pageSeq++)

        // Страница 1: OpusTags
        val vendor = "AiBuds".toByteArray(StandardCharsets.UTF_8)
        val opusTags = ByteArrayOutputStream(16 + vendor.size).apply {
            write("OpusTags".toByteArray(StandardCharsets.US_ASCII))
            writeIntLe(vendor.size)
            write(vendor)
            writeIntLe(0)
        }.toByteArray()
        writePage(out, listOf(opusTags), headerType = 0x00, granule = 0L, seq = pageSeq++)

        // Аудио-страницы
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
        // CRC (22..25) оставляем нулями перед расчётом
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
