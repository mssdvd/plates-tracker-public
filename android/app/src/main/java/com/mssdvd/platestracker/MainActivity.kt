package com.mssdvd.platestracker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.mssdvd.platestracker.capture.CaptureService
import com.mssdvd.platestracker.databinding.ActivityMainBinding
import com.mssdvd.platestracker.settings.AppSettings
import com.mssdvd.platestracker.sync.SyncWorker
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI host for the capture pipeline, which lives in CaptureService so it survives screen-off while
 * driving. The activity binds for the live preview + HUD, owns the permission flow, and edits the
 * sync settings (server URL + token).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: CaptureService? = null
    private var bound = false
    private var stateJob: Job? = null
    private var blackout = false

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // Notifications are nice-to-have; camera + location are not.
            val essential = grants.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
            if (essential.values.all { it }) startCapture()
            else Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as CaptureService.LocalBinder).service
            service = s
            s.attachPreview(if (blackout) null else binding.previewView.surfaceProvider)
            stateJob?.cancel()
            stateJob = lifecycleScope.launch {
                launch {
                    s.state.collect { render(it) }
                }
                // Sync happens off-process-lifecycle (WorkManager); poll the counts it changes.
                while (true) {
                    delay(5_000)
                    s.refreshCounts()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener {
            if (service?.state?.value?.running == true) stopCapture() else ensurePermissionsAndStart()
        }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.bannerConfig.setOnClickListener { showSettings() }
        binding.btnDim.setOnClickListener { setBlackout(true) }
        binding.blackout.setOnClickListener { setBlackout(false) }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        render(CaptureService.HudState())
        maybeAutoStart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeAutoStart(intent)
    }

    /** The QS tile launches the activity with this extra: start capturing right away. */
    private fun maybeAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) != true) return
        intent.removeExtra(EXTRA_AUTO_START)
        if (!CaptureService.isRunning.value) ensurePermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        // Reattach to a service that's already capturing (e.g. screen was off).
        bindService(Intent(this, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
        bound = true
        updateConfigBanner()
    }

    override fun onStop() {
        if (bound) {
            service?.attachPreview(null) // capture continues headless
            stateJob?.cancel()
            unbindService(connection)
            bound = false
            service = null
        }
        super.onStop()
    }

    private fun ensurePermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCapture() else requestPermissions.launch(missing.toTypedArray())
    }

    private fun startCapture() {
        ContextCompat.startForegroundService(this, Intent(this, CaptureService::class.java))
        if (!bound) {
            bindService(Intent(this, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE)
            bound = true
        } else {
            service?.attachPreview(binding.previewView.surfaceProvider)
        }
    }

    private fun stopCapture() {
        // Not stopService(): while we're bound the service instance would outlive it with the
        // camera still running. ACTION_STOP makes the service tear capture down right away.
        startService(
            Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
        )
    }

    private fun render(s: CaptureService.HudState) {
        binding.overlay.setResults(if (s.running) s.reads else emptyList(), s.frameW, s.frameH)
        binding.btnToggle.text = getString(if (s.running) R.string.stop else R.string.start)
        binding.btnDim.isVisible = s.running
        // A dash-mounted screen must not time out mid-drive.
        if (s.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!s.running && blackout) setBlackout(false)

        // High-visibility banner while burst/ring is suspended (thermal SEVERE+, see
        // CaptureService.analyzeV2/scanLive) — the small HUD token below was easy to miss while
        // driving.
        binding.thermalBanner.isVisible = s.running && s.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        if (binding.thermalBanner.isVisible) {
            binding.thermalBanner.text = getString(R.string.thermal_banner, thermalName(s.thermalStatus))
        }

        val best = s.reads.maxByOrNull { it.ocrConfidence }
        binding.hud.text = buildString {
            if (s.running) {
                if (s.hotStart) append("⚠ started hot — cool the phone\n")
                append("latency ${s.latencyMs} ms  (~${if (s.latencyMs > 0) 1000 / s.latencyMs else 0} fps)\n")
                append("plate ${best?.let { "${it.text} ${"%.2f".format(it.ocrConfidence)} ${it.country}" } ?: "—"}\n")
                append("gps ${if (s.hasGps) "✓" else "✗"}")
                if (s.droppedNoGps > 0) append("  dropped ${s.droppedNoGps}")
                if (s.burstPending > 0) append("  burst ×${s.burstPending}")
                append("\n")
                s.battTempC?.let {
                    append("batt %.1f°C".format(it))
                    if (s.thermalStatus > 0) append("  thermal ${thermalName(s.thermalStatus)}")
                    append("\n")
                }
            } else {
                append("stopped\n")
            }
            append("today ${s.todayCount}   queued ${s.queueCount}")
            // Last session's v1/v2 + thermal summary — visible after a drive too (refreshCounts
            // pulls it from disk once stopped), since logcat's small ring buffer doesn't keep it.
            if (s.captureStats.isNotBlank()) append("\n${s.captureStats}")
        }

        // Flash the freshest recorded plate big for a few seconds (promotions are rare events;
        // the state stream ticks every processed frame, so the flash clears on its own).
        val newest = s.recent.firstOrNull()
        val flash = s.running && newest != null &&
            SystemClock.elapsedRealtime() - newest.atMs < FLASH_MS
        binding.flashGroup.isVisible = flash && !blackout
        if (flash) {
            binding.bigPlate.text = newest!!.plate
            binding.bigPlateSub.text = buildString {
                append(newest.country)
                newest.year?.let { append("  ~$it") }
                if (newest.readKind != "exact") append("  ✎ corrected")
            }
        }

        binding.recentLog.isVisible = s.running && s.recent.isNotEmpty() && !blackout
        binding.recentLog.text = s.recent.joinToString("\n") { d ->
            buildString {
                append("${d.clock}  ${d.plate}")
                if (d.readKind != "exact") append(" ✎")
                append("  ${d.country} ${"%.2f".format(d.confidence)}")
                d.year?.let { append("  ~$it") }
            }
        }

        if (blackout) {
            binding.blackoutHud.text = buildString {
                append("recording  today ${s.todayCount}  queued ${s.queueCount}")
                s.battTempC?.let { append("  %.1f°C".format(it)) }
                newest?.let { append("\n${it.clock}  ${it.plate}") }
            }
        }
    }

    /**
     * Blackout mode for long drives: capture keeps running, but the preview surface is detached
     * (no decode-to-screen work) and the panel goes near-black — battery and thermals both win.
     */
    private fun setBlackout(on: Boolean) {
        if (blackout == on) return
        blackout = on
        binding.blackout.isVisible = on
        service?.attachPreview(if (on) null else binding.previewView.surfaceProvider)
        window.attributes = window.attributes.apply {
            screenBrightness =
                if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun updateConfigBanner() {
        lifecycleScope.launch {
            binding.bannerConfig.isVisible = !AppSettings.read(this@MainActivity).isComplete
        }
    }

    private fun thermalName(status: Int): String = when (status) {
        1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"
        6 -> "shutdown"; else -> "none"
    }

    private fun showSettings() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val url = view.findViewById<EditText>(R.id.inputUrl)
        val token = view.findViewById<EditText>(R.id.inputToken)
        val wifi = view.findViewById<CheckBox>(R.id.checkWifiOnly)
        val bufferedCapture = view.findViewById<CheckBox>(R.id.checkCaptureV2)
        val result = view.findViewById<TextView>(R.id.testResult)
        view.findViewById<Button>(R.id.btnTest).setOnClickListener {
            val base = url.text.toString().trim().trimEnd('/')
            if (base.isBlank()) {
                result.setText(R.string.test_no_url)
            } else {
                result.setText(R.string.testing)
                lifecycleScope.launch {
                    result.text = testConnection(base, token.text.toString().trim())
                }
            }
        }
        lifecycleScope.launch {
            val cfg = AppSettings.read(this@MainActivity)
            url.setText(cfg.serverUrl)
            token.setText(cfg.apiToken)
            wifi.isChecked = cfg.wifiOnly
            bufferedCapture.isChecked = cfg.captureV2
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.settings)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        AppSettings.save(
                            this@MainActivity,
                            url.text.toString(), token.text.toString(), wifi.isChecked,
                            bufferedCapture.isChecked,
                        )
                        // Kick a sync so a queue built up before configuration drains right away.
                        SyncWorker.enqueue(this@MainActivity, wifi.isChecked)
                        updateConfigBanner()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Hit an authenticated endpoint so the check validates URL *and* token in one go. */
    private suspend fun testConnection(base: String, token: String): String =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL("$base/stats").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    when (val code = conn.responseCode) {
                        in 200..299 -> "✓ server reachable, token accepted"
                        401 -> "✗ server reachable, but the token was rejected"
                        else -> "✗ unexpected HTTP $code — is the URL right?"
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                "✗ unreachable: ${e.message ?: e.javaClass.simpleName}"
            }
        }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        private const val FLASH_MS = 3_000L
    }
}
