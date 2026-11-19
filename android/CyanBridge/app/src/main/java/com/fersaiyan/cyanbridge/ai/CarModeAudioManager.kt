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
    fun setCarMode(enabled: Boolean, glassesAddress: String?, glassesName: String? = null) {
        prefs.edit().putBoolean(KEY_CAR_MODE, enabled).apply()
        _isCarModeEnabled.value = enabled

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

        adapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP && proxy is BluetoothA2dp) {
                        val target = resolveGlassesDevice(adapter, proxy, glassesAddress, glassesName)
                        if (target == null) {
                            _statusMessage.value = "Очки не найдены в профиле A2DP"
                        } else {
                            val ok = toggleA2dpProfile(proxy, target, disconnectA2dp = enabled)
                            _statusMessage.value = when {
                                ok && enabled -> "Режим Авто: A2DP отключён (${target.name ?: target.address})"
                                ok && !enabled -> "Обычный режим: звук снова в очках"
                                else -> "A2DP не переключился (устройство: ${target.name ?: target.address})"
                            }
                        }
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            },
            BluetoothProfile.A2DP,
        )
    }

    @SuppressLint("MissingPermission")
    private fun resolveGlassesDevice(
        adapter: BluetoothAdapter,
        a2dp: BluetoothA2dp,
        glassesAddress: String?,
        glassesName: String?,
    ): BluetoothDevice? {
        val connected = runCatching { a2dp.connectedDevices }.getOrDefault(emptyList())
        if (!glassesAddress.isNullOrBlank()) {
            connected.firstOrNull { it.address.equals(glassesAddress, ignoreCase = true) }?.let { return it }
        }
        if (!glassesName.isNullOrBlank()) {
            connected.firstOrNull {
                val n = it.name.orEmpty()
                n.equals(glassesName, ignoreCase = true) || n.contains(glassesName, ignoreCase = true)
            }?.let { return it }
        }
        val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        if (!glassesAddress.isNullOrBlank()) {
            bonded.firstOrNull { it.address.equals(glassesAddress, ignoreCase = true) }?.let { return it }
        }
        if (!glassesName.isNullOrBlank()) {
            bonded.firstOrNull { it.name.orEmpty().contains(glassesName, ignoreCase = true) }?.let { return it }
        }
        return connected.firstOrNull()
    }

    private fun toggleA2dpProfile(
        a2dp: BluetoothA2dp,
        device: BluetoothDevice,
        disconnectA2dp: Boolean,
    ): Boolean {
        val methodsTried = listOf(
            {
                val m = a2dp.javaClass.getMethod(
                    if (disconnectA2dp) "disconnect" else "connect",
                    BluetoothDevice::class.java,
                )
                m.invoke(a2dp, device) as? Boolean ?: false
            },
            {
                val m = a2dp.javaClass.getMethod(
                    "setConnectionPolicy",
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType,
                )
                m.invoke(a2dp, device, if (disconnectA2dp) 0 else 100) as? Boolean ?: false
            },
            {
                val m = a2dp.javaClass.getMethod(
                    "setPriority",
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType,
                )
                m.invoke(a2dp, device, if (disconnectA2dp) 0 else 100) as? Boolean ?: false
            },
        )

        for (attempt in methodsTried) {
            val ok = runCatching { attempt() }
                .onFailure { Log.w(TAG, "A2DP method failed: ${it.message}") }
                .getOrDefault(false)
            if (ok) return true
        }
        return false
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
