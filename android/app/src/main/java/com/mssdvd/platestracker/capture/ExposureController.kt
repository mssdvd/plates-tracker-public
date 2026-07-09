package com.mssdvd.platestracker.capture

import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

/**
 * Shutter-priority exposure cap for oncoming-plate capture. CameraX's default auto-exposure
 * stretches the sensor exposure time to tens of ms in low light; at a highway closing speed an
 * oncoming plate then smears across the frame and the detector never sees a plate-shaped box (the
 * 2026 dusk drives recorded zero sightings). Capping the exposure time trades that motion blur for
 * sensor noise, which the detector and OCR tolerate far better.
 *
 * The cap is *conditional*, so daytime is untouched: while auto-exposure keeps the shutter under
 * [CAP_EXPOSURE_NS] the controller stays in AUTO and issues no overrides at all. It only takes the
 * camera to MANUAL once AE would cross the cap, and hands control straight back when the scene is
 * bright enough that even the ISO floor would over-expose at the capped shutter.
 *
 * Two feedback signals drive it: the AE-reported exposure time from capture results (valid only in
 * AUTO — in MANUAL the result just echoes our own request), and the mean frame luma the analyzer
 * already computes (the brightness signal while AE is off). Thread-safe: capture results arrive on
 * a camera thread, luma on the scan thread.
 */
@AndroidXOptIn(ExperimentalCamera2Interop::class)
class ExposureController(private val control: Camera2CameraControl) {

    private enum class Mode { AUTO, MANUAL }

    private var mode = Mode.AUTO
    private var iso = 0                 // current MANUAL sensitivity; 0 until the first switch
    private var overCapFrames = 0       // consecutive AUTO results above the cap (debounce)

    /**
     * One auto-exposure result. Ignored in MANUAL (the values just mirror our request). In AUTO,
     * a run of frames above the cap flips to MANUAL, seeding ISO so the capped-shutter frame keeps
     * the brightness AE had reached: iso' = iso * (autoExposure / cap).
     */
    @Synchronized
    fun onAutoResult(exposureNs: Long, autoIso: Int) {
        if (mode != Mode.AUTO) return
        if (exposureNs <= CAP_EXPOSURE_NS) { overCapFrames = 0; return }
        if (++overCapFrames < DEBOUNCE_FRAMES) return

        val scaled = (autoIso * exposureNs.toDouble() / CAP_EXPOSURE_NS).toInt()
        iso = scaled.coerceIn(ISO_MIN, ISO_MAX)
        mode = Mode.MANUAL
        overCapFrames = 0
        apply()
        Log.i(TAG, "AE ${exposureNs / 1000}us > cap -> MANUAL, iso=$iso")
    }

    /**
     * Mean luma (0..255) of an analyzer frame. Only meaningful in MANUAL, where it closes the
     * exposure loop by nudging ISO toward the target band. If the frame is bright at the ISO floor
     * the scene has out-grown the cap, so hand control back to AE.
     */
    @Synchronized
    fun onFrameLuma(luma: Int) {
        if (mode != Mode.MANUAL) return
        when {
            luma > LUMA_HIGH && iso <= ISO_MIN -> {
                mode = Mode.AUTO
                clear()
                Log.i(TAG, "scene bright at iso floor -> AUTO")
            }
            luma > LUMA_HIGH -> adjustIso(DOWN)
            luma < LUMA_LOW -> adjustIso(UP)
        }
    }

    /** Reset to AUTO and drop overrides (call on stop / rebind so the next run starts clean). */
    @Synchronized
    fun reset() {
        if (mode == Mode.AUTO) return
        mode = Mode.AUTO
        clear()
    }

    private fun adjustIso(factor: Double) {
        val next = (iso * factor).toInt().coerceIn(ISO_MIN, ISO_MAX)
        if (next == iso) return
        iso = next
        apply()
    }

    private fun apply() {
        control.captureRequestOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, CAP_EXPOSURE_NS)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .build()
    }

    private fun clear() {
        // Empty options re-enable CameraX's AE (AE_MODE_ON) — back to stock daytime behavior.
        control.captureRequestOptions = CaptureRequestOptions.Builder().build()
    }

    companion object {
        private const val TAG = "ExposureController"
        // 4 ms: ~0.15 m of smear at a 140 km/h closing speed, vs ~1.3 m at a 33 ms AE ceiling.
        private const val CAP_EXPOSURE_NS = 4_000_000L
        private const val DEBOUNCE_FRAMES = 3      // ignore a momentary AE spike (a passing shadow)
        private const val ISO_MIN = 100
        private const val ISO_MAX = 3200           // device clamps to its own sensor range
        private const val LUMA_LOW = 90            // target luma band on the capped-shutter frame
        private const val LUMA_HIGH = 170
        private const val UP = 1.35
        private const val DOWN = 0.75
    }
}
