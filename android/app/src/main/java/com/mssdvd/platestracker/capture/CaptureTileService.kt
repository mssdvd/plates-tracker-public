package com.mssdvd.platestracker.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mssdvd.platestracker.MainActivity

/**
 * Quick Settings tile: one swipe + tap to start/stop capture without hunting for the app.
 * A camera foreground service can't be started from the background, so "start" opens the
 * activity with an auto-start extra; "stop" reaches the running service directly.
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (CaptureService.isRunning.value) {
            // ACTION_STOP, not stopService(): a bound activity would keep the instance (and the
            // camera) alive. startService is legal here because the capture FGS is running.
            startService(
                Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
            )
            render(active = false)
        } else {
            val launch = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 2, launch,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launch)
            }
        }
    }

    private fun render(active: Boolean = CaptureService.isRunning.value) {
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
