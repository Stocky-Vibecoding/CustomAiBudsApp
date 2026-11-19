package com.fersaiyan.cyanbridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.ai.CarModeAudioManager
import com.fersaiyan.cyanbridge.ai.GeminiConfig
import com.fersaiyan.cyanbridge.ai.GeminiGlassAssistant
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsState
import com.fersaiyan.cyanbridge.media.ColorPreset
import com.fersaiyan.cyanbridge.media.MediaProcessingOptions
import com.fersaiyan.cyanbridge.media.MetaVideoProcessor
import com.fersaiyan.cyanbridge.media.TuneBudsWifiImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AiBudsMainActivity : ComponentActivity() {
    private lateinit var tuneBuds: TuneBudsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
        }

        tuneBuds = TuneBudsManager.getInstance(applicationContext)
        val carMode = CarModeAudioManager.getInstance(applicationContext)
        val gemini = GeminiGlassAssistant.getInstance(applicationContext)
        gemini.bindToGlasses(tuneBuds)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4F8CFF),
                    secondary = Color(0xFF34D399),
                    background = Color(0xFF0E1117),
                    surface = Color(0xFF161B22),
                    surfaceVariant = Color(0xFF1F2631),
                ),
            ) {
                AiBudsRootScreen(
                    tuneBuds = tuneBuds,
                    carMode = carMode,
                    gemini = gemini,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::tuneBuds.isInitialized && tuneBuds.isConnected()) {
            tuneBuds.refreshStatus()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiBudsRootScreen(
    tuneBuds: TuneBudsManager,
    carMode: CarModeAudioManager,
    gemini: GeminiGlassAssistant,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val glassState by tuneBuds.state.collectAsState()
    val carModeEnabled by carMode.isCarModeEnabled.collectAsState()
    val carModeStatus by carMode.statusMessage.collectAsState()

    // Автообновление статусов очков каждые 15 секунд, пока подключены и не идёт запись
    LaunchedEffect(glassState.protocolState) {
        if (glassState.protocolState == "CONNECTED") {
            while (isActive) {
                delay(15_000L)
                val cur = tuneBuds.state.value
                if (cur.protocolState == "CONNECTED" && !cur.isVideoRecording && !cur.isAudioRecording) {
                    tuneBuds.refreshStatus()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedTab == 0) "AiBuds Studio" else "Медиатека",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = { tuneBuds.refreshStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки и ИИ")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
                    label = { Text("Устройства") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text("Медиа") },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (selectedTab == 0) {
                DeviceTab(
                    state = glassState,
                    carModeEnabled = carModeEnabled,
                    carModeStatus = carModeStatus,
                    tuneBuds = tuneBuds,
                    carMode = carMode,
                    gemini = gemini,
                    onOpenAiSheet = { showSettingsSheet = true },
                )
            } else {
                MediaTab(state = glassState, tuneBuds = tuneBuds)
            }
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
            AiSettingsSheetContent(
                gemini = gemini,
                tuneBuds = tuneBuds,
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceTab(
    state: TuneBudsState,
    carModeEnabled: Boolean,
    carModeStatus: String?,
    tuneBuds: TuneBudsManager,
    carMode: CarModeAudioManager,
    gemini: GeminiGlassAssistant,
    onOpenAiSheet: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val geminiBusy by gemini.isBusy.collectAsState()
    var showConnectDialog by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("aibuds_ui_prefs", Context.MODE_PRIVATE) }
    val savedMac = remember { prefs.getString("last_mac", null) }
    val savedName = remember { prefs.getString("last_name", null) }
    val isConnected = state.protocolState == "CONNECTED"
    val activeName = state.deviceName ?: savedName

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeName ?: state.model ?: "Smart Glasses",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = state.connectionLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isConnected) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (state.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = state.batteryPercent?.let { "$it%" } ?: "--%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isConnected) {
                            OutlinedButton(
                                onClick = { tuneBuds.disconnect() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.BluetoothConnected, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Отключить очки")
                            }
                        } else {
                            Button(
                                onClick = { showConnectDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.Bluetooth, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Подключить очки")
                            }
                            if (!savedMac.isNullOrBlank()) {
                                OutlinedButton(
                                    onClick = { tuneBuds.connect(savedMac, savedName) },
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Быстро")
                                }
                            }
                        }
                    }

                    state.storage?.let { st ->
                        val total = (st.usedMiB + st.freeMiB).coerceAtLeast(1L)
                        val progress = st.usedMiB.toFloat() / total.toFloat()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Память очков", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    "Свободно ${st.freeMiB} МБ из $total МБ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                            )
                        }
                    }

                    state.mediaCounts?.let { counts ->
                        Text(
                            text = "На очках: ${counts.images} фото • ${counts.videos} видео • ${counts.audio} аудио",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    state.lastError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { tuneBuds.takePhoto() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Фото")
                }
                Button(
                    onClick = { tuneBuds.toggleVideo() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (state.isVideoRecording) {
                        ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Icon(
                        if (state.isVideoRecording) Icons.Default.Stop else Icons.Default.Videocam,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isVideoRecording) "Стоп" else "Видео")
                }
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        gemini.ask(
                            prompt = "Опиши кратко, что сейчас перед моими глазами.",
                            withGlassPhoto = true,
                            tuneBudsManager = tuneBuds,
                        )
                        onOpenAiSheet()
                    }
                },
                enabled = !geminiBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (geminiBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (geminiBusy) "Gemini анализирует кадр..." else "Спросить Gemini по фото с очков")
            }

            if (isWide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    CarModeCard(
                        enabled = carModeEnabled,
                        status = carModeStatus,
                        onToggle = { carMode.setCarMode(it, state.deviceAddress, activeName) },
                        modifier = Modifier.weight(1f),
                    )
                    WearDetectCard(
                        enabled = state.inEarDetectionEnabled == true,
                        onToggle = { tuneBuds.setWearDetection(it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                CarModeCard(
                    enabled = carModeEnabled,
                    status = carModeStatus,
                    onToggle = { carMode.setCarMode(it, state.deviceAddress, activeName) },
                    modifier = Modifier.fillMaxWidth(),
                )
                WearDetectCard(
                    enabled = state.inEarDetectionEnabled == true,
                    onToggle = { tuneBuds.setWearDetection(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Лимит записи видео", fontWeight = FontWeight.SemiBold)
                    val options = listOf(60 to "1 мин", 180 to "3 мин", 540 to "9 мин", 720 to "12 мин")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        options.forEach { (sec, label) ->
                            val isSelected = state.videoLimitSeconds == sec || state.videoLimitSeconds == (sec / 60)
                            FilterChip(
                                selected = isSelected,
                                onClick = { tuneBuds.setVideoLimit(sec) },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConnectDialog) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bonded = runCatching { btManager?.adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())

        AlertDialog(
            onDismissRequest = { showConnectDialog = false },
            title = { Text("Выбери очки (Bluetooth)") },
            text = {
                if (bonded.isEmpty()) {
                    Text("Нет сопряжённых Bluetooth-устройств или не выдано разрешение Bluetooth.")
                } else {
                    LazyColumn(
                        modifier = Modifier.height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(bonded, key = { it.address }) { device ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val name = device.name ?: "TuneBuds"
                                        prefs.edit()
                                            .putString("last_mac", device.address)
                                            .putString("last_name", name)
                                            .apply()
                                        tuneBuds.connect(device.address, name)
                                        showConnectDialog = false
                                    },
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(device.name ?: "Без имени", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConnectDialog = false
                        runCatching {
                            context.startActivity(
                                Intent().setClassName(context, "com.fersaiyan.cyanbridge.ui.DeviceBindActivity"),
                            )
                        }
                    },
                ) {
                    Text("Поиск новых (BLE)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConnectDialog = false }) {
                    Text("Закрыть")
                }
            },
        )
    }
}

@Composable
private fun CarModeCard(
    enabled: Boolean,
    status: String?,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Режим Авто (A2DP)", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Музыка на магнитолу, ИИ в очки",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (!status.isNullOrBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun WearDetectCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Visibility, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Датчик снятия очков", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Автопауза при снятии (0x26)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaTab(state: TuneBudsState, tuneBuds: TuneBudsManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val processor = remember { MetaVideoProcessor(context.applicationContext) }
    val importer = remember { TuneBudsWifiImporter.getInstance(context.applicationContext) }
    val importState by importer.state.collectAsState()

    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var options by remember { mutableStateOf(MediaProcessingOptions()) }
    var exportProgress by remember { mutableStateOf<Int?>(null) }
    var showFormatDialog by remember { mutableStateOf(false) }

    fun reloadFiles() {
        val dirs = listOfNotNull(
            importer.getMediaDir(),
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            context.getExternalFilesDir(null),
        )
        files = dirs.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "mp4") }
                .toList()
        }.distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    LaunchedEffect(Unit) { reloadFiles() }
    LaunchedEffect(importState.isRunning) {
        if (!importState.isRunning) reloadFiles()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 600.dp) 4 else 2

        Column(modifier = Modifier.fillMaxSize()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.mediaCounts?.let {
                                "На очках: ${it.total} (${it.images} фото, ${it.videos} видео)"
                            } ?: "Локальные файлы: ${files.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        IconButton(onClick = { reloadFiles() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить список")
                        }
                    }

                    Text(
                        text = importState.error ?: importState.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (importState.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    )

                    if (importState.isRunning) {
                        if (importState.totalFiles > 0) {
                            LinearProgressIndicator(
                                progress = { (importState.currentFilePercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                importer.startImport(tuneBuds)
                                reloadFiles()
                            }
                        },
                        enabled = !importState.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (importState.isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (importState.isRunning) "Идёт перенос по Wi-Fi..." else "Импорт с очков (Wi-Fi)")
                    }

                    OutlinedButton(
                        onClick = { showFormatDialog = true },
                        enabled = !importState.isRunning && state.protocolState == "CONNECTED",
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Очистить память очков")
                    }
                }
            }

            if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Пока нет скачанных фото и видео",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(files, key = { it.absolutePath }) { file ->
                        MediaGridTile(file = file, onClick = { selectedFile = file })
                    }
                }
            }
        }
    }

    selectedFile?.let { file ->
        ModalBottomSheet(onDismissRequest = { if (exportProgress == null) selectedFile = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text("Цветокоррекция", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ColorPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = options.colorPreset == preset,
                            onClick = { options = options.copy(colorPreset = preset) },
                            label = { Text(preset.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = options.upscale2K,
                        onCheckedChange = { options = options.copy(upscale2K = it) },
                    )
                    Text("Апскейл 2K (2048×1536)")
                }

                if (file.extension.equals("mp4", ignoreCase = true)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = options.stabilize,
                            onCheckedChange = { options = options.copy(stabilize = it) },
                        )
                        Text("Стабилизация (кроп 6%)")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = options.deleteDraftAfterSave,
                        onCheckedChange = { options = options.copy(deleteDraftAfterSave = it) },
                    )
                    Text("Удалить черновик после сохранения")
                }

                exportProgress?.let { prog ->
                    LinearProgressIndicator(
                        progress = { prog / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Экспорт в Галерею: $prog%", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        scope.launch {
                            exportProgress = 0
                            runCatching {
                                if (file.extension.equals("mp4", ignoreCase = true)) {
                                    processor.exportVideoToGallery(file, options) { exportProgress = it }
                                } else {
                                    processor.exportPhotoToGallery(file, options)
                                }
                            }.onSuccess {
                                Toast.makeText(context, "Сохранено в Галерею!", Toast.LENGTH_SHORT).show()
                                exportProgress = null
                                selectedFile = null
                                reloadFiles()
                            }.onFailure { err ->
                                Toast.makeText(context, "Ошибка: ${err.message}", Toast.LENGTH_LONG).show()
                                exportProgress = null
                            }
                        }
                    },
                    enabled = exportProgress == null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Сохранить в Галерею")
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showFormatDialog) {
        AlertDialog(
            onDismissRequest = { showFormatDialog = false },
            title = { Text("Очистить память очков?") },
            text = { Text("Все фото и видео на самих очках будут безвозвратно удалены. Скачанные на телефон файлы останутся.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFormatDialog = false
                        tuneBuds.formatStorage()
                        Toast.makeText(context, "Команда очистки отправлена на очки", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFormatDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun MediaGridTile(file: File, onClick: () -> Unit) {
    val isVideo = file.extension.equals("mp4", ignoreCase = true)
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (isVideo) {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        file.absolutePath,
                        MediaStore.Images.Thumbnails.MINI_KIND,
                    )
                } else {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        thumb?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("VIDEO", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun AiSettingsSheetContent(
    gemini: GeminiGlassAssistant,
    tuneBuds: TuneBudsManager,
) {
    val context = LocalContext.current
    val cfg by gemini.config.collectAsState()
    val messages by gemini.messages.collectAsState()
    val templeLog by gemini.lastTempleLog.collectAsState()
    val isBusy by gemini.isBusy.collectAsState()
    val scope = rememberCoroutineScope()

    var apiKey by remember(cfg) { mutableStateOf(cfg.apiKey) }
    var baseUrl by remember(cfg) { mutableStateOf(cfg.baseUrl) }
    var model by remember(cfg) { mutableStateOf(cfg.model) }
    var speak by remember(cfg) { mutableStateOf(cfg.speakResponses) }
    var autoVision by remember(cfg) { mutableStateOf(cfg.autoVisionOnTempleHold) }
    var promptInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Настройки Gemini и ИИ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Gemini API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Прокси / Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Модель (например, gemini-2.5-flash)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Озвучивать ответы (TTS)")
            Switch(checked = speak, onCheckedChange = { speak = it })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Фото с очков при удержании дужки")
            Switch(checked = autoVision, onCheckedChange = { autoVision = it })
        }

        Button(
            onClick = {
                gemini.updateConfig(
                    GeminiConfig(
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        speakResponses = speak,
                        autoVisionOnTempleHold = autoVision,
                    ),
                )
                Toast.makeText(context, "Настройки ИИ сохранены!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Сохранить настройки ИИ")
        }

        Text(
            text = "Диагностика дужки: $templeLog",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        HorizontalDivider()

        Text("Мини-чат", fontWeight = FontWeight.SemiBold)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (msg.isUser) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = (if (msg.isUser) "Вы: " else "Gemini: ") + msg.text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                placeholder = { Text("Спросить Gemini...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    val q = promptInput.trim()
                    if (q.isNotEmpty() && !isBusy) {
                        promptInput = ""
                        scope.launch { gemini.ask(q, withGlassPhoto = false, tuneBudsManager = tuneBuds) }
                    }
                },
                enabled = !isBusy,
            ) {
                Icon(Icons.Default.Send, contentDescription = "Отправить")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
