package com.fersaiyan.cyanbridge.ai

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsAiEvent
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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
        private const val TEMPLE_DEBOUNCE_MS = 2_500L

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

    private val _lastTempleLog = MutableStateFlow("Ожидание нажатия дужки...")
    val lastTempleLog: StateFlow<String> = _lastTempleLog.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var glassesListenerJob: Job? = null
    private var lastTriggerMs = 0L

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
                handleTempleEvent(event, tuneBudsManager)
            }
        }
    }

    private fun handleTempleEvent(event: TuneBudsAiEvent, tuneBudsManager: TuneBudsManager) {
        val hex = event.rawPayload.take(12).joinToString(" ") { "%02X".format(it) }
        _lastTempleLog.value = "cmd=0x${event.command.toString(16)} state=${event.state} [$hex]"

        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < TEMPLE_DEBOUNCE_MS) return
        if (_isBusy.value) return

        val shouldTrigger = event.state == 1 || event.command == 0x55AA
        if (!shouldTrigger) return

        lastTriggerMs = now
        tuneBudsManager.setAiChatState(1)
        startVoiceListening(tuneBudsManager)
    }

    private fun startVoiceListening(tuneBudsManager: TuneBudsManager) {
        scope.launch(Dispatchers.Main) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _lastTempleLog.value = "Распознавание речи недоступно на устройстве"
                tuneBudsManager.setAiChatState(0)
                return@launch
            }

            _isBusy.value = true
            _lastTempleLog.value = "Слушаю голос..."
            playListenBeep()

            runCatching { speechRecognizer?.destroy() }
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        _lastTempleLog.value = "Обрабатываю запрос..."
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "SpeechRecognizer error=$error")
                        _lastTempleLog.value = "Голос не распознан (код $error)"
                        _isBusy.value = false
                        tuneBudsManager.setAiChatState(0)
                    }

                    override fun onResults(results: Bundle?) {
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        if (spoken.isBlank()) {
                            _lastTempleLog.value = "Пустой голосовой запрос"
                            _isBusy.value = false
                            tuneBudsManager.setAiChatState(0)
                            return
                        }

                        val lower = spoken.lowercase(Locale("ru", "RU"))
                        val wantsPhoto = _config.value.autoVisionOnTempleHold ||
                            VISION_TRIGGERS.any { lower.contains(it) }

                        _lastTempleLog.value = if (wantsPhoto) {
                            "Запрос с фото: «$spoken»"
                        } else {
                            "Запрос: «$spoken»"
                        }

                        scope.launch {
                            ask(
                                prompt = spoken,
                                withGlassPhoto = wantsPhoto,
                                tuneBudsManager = tuneBudsManager,
                            )
                            tuneBudsManager.setAiChatState(0)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                },
            )

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer.startListening(intent)
        }
    }

    private fun playListenBeep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }
    }

    suspend fun ask(
        prompt: String,
        withGlassPhoto: Boolean = false,
        tuneBudsManager: TuneBudsManager? = null,
    ): String {
        val cfg = _config.value
        if (cfg.apiKey.isBlank()) {
            val msg = "Укажи API-ключ Gemini в настройках ИИ."
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

            val fullPrompt = if (photoBytes != null) {
                "$prompt (Ответь кратко, 1–3 предложения на русском, удобно для озвучки.)"
            } else {
                "$prompt (Ответь кратко и по делу на русском, 1–3 предложения.)"
            }

            val reply = sendGeminiRequest(cfg, fullPrompt, photoBytes)
            appendMessage(isUser = false, text = reply)
            if (cfg.speakResponses) {
                speak(reply)
            }
            reply
        } catch (error: Throwable) {
            val errText = "Ошибка Gemini: ${error.message ?: "нет ответа"}"
            Log.e(TAG, errText, error)
            appendMessage(isUser = false, text = errText)
            errText
        } finally {
            _isBusy.value = false
        }
    }

    private suspend fun sendGeminiRequest(
        cfg: GeminiConfig,
        prompt: String,
        jpegBytes: ByteArray?,
    ): String = withContext(Dispatchers.IO) {
        val url = "${cfg.baseUrl}/v1beta/models/${cfg.model}:generateContent?key=${cfg.apiKey}"

        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", base64),
                    ),
                )
            }
        }

        val bodyJson = JSONObject()
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
            val first = candidates.optJSONObject(0)
            val content = first?.optJSONObject("content")
            val respParts = content?.optJSONArray("parts")
            val text = respParts?.optJSONObject(0)?.optString("text")?.trim()
            if (text.isNullOrBlank()) "Модель вернула пустой текст." else text
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gemini_${System.currentTimeMillis()}")
    }

    private fun appendMessage(isUser: Boolean, text: String, hasPhoto: Boolean = false) {
        val updated = (_messages.value + GeminiChatMessage(isUser = isUser, text = text, hasPhoto = hasPhoto))
            .takeLast(30)
        _messages.value = updated
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
