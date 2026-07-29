package com.pickupcode.app.service

import android.service.quicksettings.TileService
import android.util.Log

class PickupCodeTileService : TileService() {

    override fun onClick() {
        super.onClick()
        PickupCodeAccessibilityService.triggerRequested.set(true)
        Log.d("PickupCodeTile", "触发标记已设置")
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = android.service.quicksettings.Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
