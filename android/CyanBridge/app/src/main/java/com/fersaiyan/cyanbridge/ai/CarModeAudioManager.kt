package com.fersaiyan.cyanbridge.ai

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Режим Авто: отключает медиа-аудио (A2DP) на очках, чтобы музыка шла на магнитолу,
 * при этом сохраняя SPP/BLE и голосовой канал для ИИ.
 */
class CarModeAudioManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "CarModeAudioManager"
        private const val PREFS_NAME = "aibuds_car_mode"
        private const val KEY_CAR_MODE = "car_mode_enabled"

        @Volatile
        private var instance: CarModeAudioManager? = null

        fun getInstance(context: Context): CarModeAudioManager =
            instance ?: synchronized(this) {
                instance ?: CarModeAudioManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _isCarModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_CAR_MODE, false))
    val isCarModeEnabled: StateFlow<Boolean> = _isCarModeEnabled.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    @SuppressLint("MissingPermission")
    fun setCarMode(enabled: Boolean, glassesAddress: String?) {
        prefs.edit().putBoolean(KEY_CAR_MODE, enabled).apply()
        _isCarModeEnabled.value = enabled

        if (glassesAddress.isNullOrBlank()) {
            _statusMessage.value = if (enabled) {
                "Режим Авто включён (применится при подключении очков)"
            } else {
                "Режим Авто выключен"
            }
            return
        }

        if (!hasBluetoothConnectPermission()) {
            _statusMessage.value = "Нет разрешения BLUETOOTH_CONNECT"
            return
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _statusMessage.value = "Bluetooth выключен"
            return
        }

        val device: BluetoothDevice = runCatching {
            adapter.getRemoteDevice(glassesAddress.uppercase())
        }.getOrElse {
            _statusMessage.value = "Неверный MAC очков: $glassesAddress"
            return
        }

        adapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP && proxy is BluetoothA2dp) {
                        val ok = toggleA2dpProfile(proxy, device, disconnectA2dp = enabled)
                        _statusMessage.value = when {
                            ok && enabled -> "Режим Авто: музыка на магнитолу (A2DP очков отключён)"
                            ok && !enabled -> "Обычный режим: звук снова в очках"
                            else -> "Система ограничила авто-переключение A2DP — проверь настройки BT"
                        }
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            },
            BluetoothProfile.A2DP,
        )
    }

    private fun toggleA2dpProfile(
        a2dp: BluetoothA2dp,
        device: BluetoothDevice,
        disconnectA2dp: Boolean,
    ): Boolean {
        return runCatching {
            // 1. Пробуем setConnectionPolicy (Android 11+)
            val policyMethod = a2dp.javaClass.getMethod(
                "setConnectionPolicy",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType,
            )
            // CONNECTION_POLICY_FORBIDDEN = 0, CONNECTION_POLICY_ALLOWED = 100
            val policy = if (disconnectA2dp) 0 else 100
            val result = policyMethod.invoke(a2dp, device, policy) as? Boolean ?: false
            if (result) return true

            // 2. Фоллбек через disconnect/connect
            val methodName = if (disconnectA2dp) "disconnect" else "connect"
            val actionMethod = a2dp.javaClass.getMethod(methodName, BluetoothDevice::class.java)
            actionMethod.invoke(a2dp, device) as? Boolean ?: false
        }.onFailure { err ->
            Log.w(TAG, "A2DP toggle failed: ${err.message}")
        }.getOrDefault(false)
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
