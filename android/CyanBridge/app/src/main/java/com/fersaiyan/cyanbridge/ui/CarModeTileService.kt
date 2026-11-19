package com.fersaiyan.cyanbridge.ui

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fersaiyan.cyanbridge.ai.CarModeAudioManager
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager

class CarModeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val carMode = CarModeAudioManager.getInstance(applicationContext)
        val tuneBuds = TuneBudsManager.getInstance(applicationContext)
        val nextEnabled = !carMode.isCarModeEnabled.value
        carMode.setCarMode(nextEnabled, tuneBuds.state.value.deviceAddress)
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val enabled = CarModeAudioManager.getInstance(applicationContext).isCarModeEnabled.value
        tile.label = "Режим Авто"
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
