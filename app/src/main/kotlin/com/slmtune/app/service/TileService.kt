package com.slmtune.app.service

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import com.slmtune.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.service.quicksettings.TileService as AndroidTileService

/** The quick settings tile: one tap connects or disconnects. */
class TileService : AndroidTileService() {
    private var job: Job? = null

    override fun onStartListening() {
        job = CoroutineScope(Dispatchers.Main).launch {
            VpnState.status.collect { st ->
                qsTile?.apply {
                    state = when (st) {
                        VpnState.Status.Started -> Tile.STATE_ACTIVE
                        VpnState.Status.Stopped -> Tile.STATE_INACTIVE
                        else -> Tile.STATE_UNAVAILABLE
                    }
                    subtitle = if (st == VpnState.Status.Started) VpnState.serverName.value else null
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
    }

    override fun onClick() {
        when (VpnState.status.value) {
            VpnState.Status.Started -> BoxService.stop(this)
            VpnState.Status.Stopped -> {
                if (VpnService.prepare(this) != null) {
                    // First time: the system asks for VPN permission inside the app.
                    val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("connect", true)
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(i)
                } else {
                    BoxService.start(this)
                }
            }
            else -> {}
        }
    }
}
