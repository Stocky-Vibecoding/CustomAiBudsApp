package com.fersaiyan.cyanbridge.devices.tunebuds

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.TimeZone

enum class TuneBudsFrameType(val id: Int) {
    REQUEST(1),
    RESPONSE(2),
    NOTIFICATION(3);

    companion object {
        fun fromId(id: Int): TuneBudsFrameType? = entries.firstOrNull { it.id == id }
    }
}

data class TuneBudsFrame(
    val sequence: Int,
    val command: Int,
    val type: TuneBudsFrameType,
    val payload: ByteArray,
)

data class TuneBudsEncodedCommand(
    val frames: List<ByteArray>,
    val nextSequence: Int,
)

data class TuneBudsStorageInfo(
    val usedMiB: Long,
    val freeMiB: Long,
)

data class TuneBudsMediaCounts(
    val images: Long,
    val videos: Long,
    val audio: Long,
) {
    val total: Long get() = images + videos + audio
}

data class TuneBudsBattery(
    val percent: Int,
    val isCharging: Boolean,
)

data class TuneBudsScreenConfig(
    val isSupported: Boolean,
    val isLandscape: Boolean,
)

/** Pure codec and payload helpers for the Bluetrum AB Mate command protocol. */
object TuneBudsProtocol {
    const val DEFAULT_MAX_PACKET_SIZE = 20
    const val HEADER_SIZE = 5

    const val CMD_DEVICE_INFO = 0x27
    const val CMD_SET_DETECTION = 0x26
    const val CMD_AI_CHAT_STATE = 0xB0
    const val CMD_AI_RECORD_STATE = 0xB1
    const val CMD_AI_RECORD_DATA = 0xB2
    const val CMD_AI_ENVELOPE = 0x55AA
    const val CMD_CAMERA_COPROCESSOR_INFO = 0xE0
    const val CMD_CAMERA_ON = 0xE1
    const val CMD_CAMERA_CLOSE = 0xE2
    const val CMD_AI_PICTURE = 0xE3
    const val CMD_START_VIDEO = 0xE4
    const val CMD_START_AUDIO = 0xE5
    const val CMD_CONFIGURE_WIFI = 0xE6
    const val CMD_FILE_MANAGER = 0xE7
    const val CMD_WIFI_STATE = 0xEC
    const val CMD_START_RTSP = 0xE8
    const val CMD_COPROCESSOR_VERSION = 0xE9
    const val CMD_STORAGE = 0xEA
    const val CMD_FORMAT_STORAGE = 0xEB
    const val CMD_MEDIA_COUNTS = 0xEF
    const val CMD_SET_TIME = 0xF0
    const val CMD_WORK_STATE = 0xF6
    const val CMD_GLASS_CONFIG = 0xF7
    const val CMD_TELEPROMPTER = 0xF8
    const val CMD_TELEPROMPTER_CONTROL = 0xFB

    const val CONFIG_VIDEO_LIMIT_TIME = 0x82
    const val CONFIG_AUDIO_LIMIT_TIME = 0x83

    const val INFO_BATTERY = 0x01
    const val INFO_FIRMWARE_VERSION = 0x02
    const val INFO_IN_EAR_STATE = 0x09
    const val INFO_MAX_PACKET_SIZE = 0xFF
    const val INFO_AI_KIT_SUPPORT = 0xA0
    const val INFO_SERIAL_NUMBER = 0x85
    const val INFO_MODEL = 0x86
    const val INFO_DEVICE_ABILITY = 0xFE
    const val INFO_DEVICE_CAPABILITIES = INFO_DEVICE_ABILITY
    const val INFO_VIDEO_LIMIT = 0x82
    const val INFO_AUDIO_LIMIT = 0x83
    const val INFO_SUPPORT_VOLUME_CONTROL = 0x8D
    const val INFO_CURRENT_VOLUME = 0x8E
    const val INFO_SUPPORT_AUDIO = 0x98
    const val INFO_SUPPORT_APP_LIST = 0x9B
    const val INFO_SUPPORT_DETECTION = 0x96
    const val INFO_SCREEN_CONFIG = 0x9A
    const val INFO_RESOLUTION = 0x93
    const val INFO_SUPPORT_OPUS = 0x9F
    const val INFO_AI_CHAT_SUPPORT = 0x8A
    const val INFO_WIFI_SUPPORT = 0xA3

    fun encode(
        command: Int,
        payload: ByteArray = byteArrayOf(),
        type: TuneBudsFrameType = TuneBudsFrameType.REQUEST,
        maxPacketSize: Int = DEFAULT_MAX_PACKET_SIZE,
        initialSequence: Int = 0,
    ): TuneBudsEncodedCommand {
        require(command in 0..0xFF) { "TuneBuds command must fit in one byte" }
        require(maxPacketSize in (HEADER_SIZE + 1)..260) { "Invalid TuneBuds packet size: $maxPacketSize" }
        require(initialSequence in 0..15) { "TuneBuds sequence must be in 0..15" }
        val maxPayloadSize = maxPacketSize - HEADER_SIZE
        val fragmentCount = if (payload.isEmpty()) 1 else (payload.size + maxPayloadSize - 1) / maxPayloadSize
        require(fragmentCount <= 16) { "TuneBuds command requires more than 16 fragments" }

        var sequence = initialSequence
        val frames = ArrayList<ByteArray>(fragmentCount)
        repeat(fragmentCount) { index ->
            val start = index * maxPayloadSize
            val fragment = if (payload.isEmpty()) {
                byteArrayOf()
            } else {
                payload.copyOfRange(start, minOf(start + maxPayloadSize, payload.size))
            }
            frames += byteArrayOf(
                sequence.toByte(),
                command.toByte(),
                type.id.toByte(),
                ((((fragmentCount - 1) and 0x0F) shl 4) or (index and 0x0F)).toByte(),
                fragment.size.toByte(),
            ) + fragment
            sequence = (sequence + 1) and 0x0F
        }
        return TuneBudsEncodedCommand(frames, sequence)
    }

    fun deviceInfoQuery(vararg infoTypes: Int): ByteArray {
        require(infoTypes.isNotEmpty()) { "At least one TuneBuds device-info type is required" }
        return ByteArray(infoTypes.size * 2).also { payload ->
            infoTypes.forEachIndexed { index, type ->
                require(type in 0..0xFF) { "Device-info type must fit in one byte" }
                payload[index * 2] = type.toByte()
                payload[index * 2 + 1] = 0
            }
        }
    }

    fun parseDeviceInfo(payload: ByteArray): Map<Int, ByteArray> {
        val values = linkedMapOf<Int, ByteArray>()
        var cursor = 0
        while (cursor < payload.size) {
            require(payload.size - cursor >= 2) { "Truncated TuneBuds device-info header" }
            val type = payload[cursor].toInt() and 0xFF
            val length = payload[cursor + 1].toInt() and 0xFF
            cursor += 2
            require(payload.size - cursor >= length) { "Truncated TuneBuds device-info value for 0x${type.toString(16)}" }
            values[type] = payload.copyOfRange(cursor, cursor + length)
            cursor += length
        }
        return values
    }

    fun parseBattery(payload: ByteArray): TuneBudsBattery? {
        val value = payload.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        return TuneBudsBattery(percent = value and 0x7F, isCharging = value and 0x80 != 0)
    }

    fun parseUnsignedValue(payload: ByteArray): Int? = when (payload.size) {
        1 -> payload[0].toInt() and 0xFF
        2 -> (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        else -> null
    }

    fun parseSupportFlag(payload: ByteArray): Boolean? = when (parseUnsignedValue(payload)) {
        0 -> false
        1 -> true
        else -> null
    }

    fun parseDeviceCapabilities(payload
