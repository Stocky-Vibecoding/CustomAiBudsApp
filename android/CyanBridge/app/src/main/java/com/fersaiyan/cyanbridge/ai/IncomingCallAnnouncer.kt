package com.fersaiyan.cyanbridge.ai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
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
import java.util.Locale

/**
 * Озвучивает имя звонящего строго в Bluetooth-очки при входящем вызове.
 * Запускается и останавливается вместе с AiBudsForegroundService.
 */
class IncomingCallAnnouncer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "IncomingCallAnnouncer"
        private const val PREFS_NAME = "aibuds_call_prefs"
        private const val KEY_ENABLED = "announce_incoming_calls"
        private const val NUMBER_WAIT_FALLBACK_MS = 700L
        private const val REPEAT_DELAY_MS = 4_200L

        @Volatile
        private var instance: IncomingCallAnnouncer? = null

        fun getInstance(context: Context): IncomingCallAnnouncer =
            instance ?: synchronized(this) {
                instance ?: IncomingCallAnnouncer(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var receiverRegistered = false

    @Volatile
    private var isRinging = false

    @Volatile
    private var announcedForCurrentCall = false

    private var announceJob: Job? = null
    private var fallbackJob: Job? = null

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> handleRinging(incomingNumber)
                TelephonyManager.EXTRA_STATE_OFFHOOK,
                TelephonyManager.EXTRA_STATE_IDLE -> stopAnnouncement()
            }
        }
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
        if (!value) stopAnnouncement()
    }

    @Synchronized
    fun startListening() {
        ensureTts()
        if (receiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                context,
                phoneStateReceiver,
                IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }.onFailure { err ->
            Log.w(TAG, "Не удалось подписаться на входящие звонки: ${err.message}")
        }
    }

    @Synchronized
    fun stopListening() {
        stopAnnouncement()
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(phoneStateReceiver) }
        receiverRegistered = false
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("ru", "RU"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                // USAGE_ASSISTANT идёт строго в Bluetooth-гарнитуру и не дублируется на динамик телефона
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
        }
    }

    private fun hasBluetoothAudioOutput(): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    private fun handleRinging(rawNumber: String?) {
        if (!_enabled.value || !hasBluetoothAudioOutput()) return
        isRinging = true
        if (announcedForCurrentCall) return

        val number = rawNumber?.trim().orEmpty()
        if (number.isNotEmpty()) {
            fallbackJob?.cancel()
            fallbackJob = null
            announcedForCurrentCall = true
            announceJob?.cancel()
            announceJob = scope.launch {
                val contactName = resolveContactName(number)
                val phrase = if (!contactName.isNullOrBlank()) {
                    "Звонит $contactName"
                } else {
                    "Входящий звонок, неизвестный номер"
                }
                speakWhileRinging(phrase)
            }
        } else if (fallbackJob?.isActive != true) {
            fallbackJob = scope.launch {
                delay(NUMBER_WAIT_FALLBACK_MS)
                if (isRinging && !announcedForCurrentCall) {
                    announcedForCurrentCall = true
                    speakWhileRinging("Входящий звонок")
                }
            }
        }
    }

    private suspend fun speakWhileRinging(phrase: String) {
        if (!isRinging || !_enabled.value || !hasBluetoothAudioOutput()) return
        speak(phrase)
        delay(REPEAT_DELAY_MS)
        if (isRinging && _enabled.value && hasBluetoothAudioOutput()) {
            speak(phrase)
        }
    }

    private fun stopAnnouncement() {
        isRinging = false
        announcedForCurrentCall = false
        fallbackJob?.cancel()
        fallbackJob = null
        announceJob?.cancel()
        announceJob = null
        runCatching { tts?.stop() }
    }

    private suspend fun resolveContactName(phoneNumber: String): String? = withContext(Dispatchers.IO) {
        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContacts) return@withContext null

        runCatching {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber),
            )
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun speak(text: String) {
        ensureTts()
        if (!ttsReady || !hasBluetoothAudioOutput()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "call_${System.currentTimeMillis()}")
    }
}
