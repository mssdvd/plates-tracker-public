package com.mssdvd.platestracker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Size
import android.view.Display
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.mssdvd.platestracker.AppLog
import com.mssdvd.platestracker.MainActivity
import com.mssdvd.platestracker.R
import com.mssdvd.platestracker.alpr.Alpr
import com.mssdvd.platestracker.alpr.PlateRead
import com.mssdvd.platestracker.alpr.PlateValidator
import com.mssdvd.platestracker.data.Sighting
import com.mssdvd.platestracker.data.SightingStore
import com.mssdvd.platestracker.location.LocationProvider
import com.mssdvd.platestracker.settings.AppSettings
import com.mssdvd.platestracker.sync.SyncWorker
import com.mssdvd.platestracker.tracking.DedupEngine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * Foreground service owning the capture pipeline: CameraX -> Alpr -> DedupEngine -> SightingStore
 * -> SyncWorker, with the latest GPS fix attached at promotion. The camera binds to the service
 * lifecycle so capture survives the activity (screen-off / backgrounding while driving); the
 * activity binds back for the live preview + HUD.
 */
class CaptureService : LifecycleService() {

    /** One promoted sighting, as shown in the on-screen log / big-plate flash. */
    data class Detection(
        val plate: String,
        val country: String,
        val confidence: Float,
        val atMs: Long,       // SystemClock.elapsedRealtime() of promotion (drives the flash)
        val clock: String,    // wall-clock HH:mm:ss for the log
        val readKind: String, // "exact" | "corrected"
        val year: Int?,       // estimated registration year from the series prior
    )

    /** Everything the activity needs to render the overlay + HUD. */
    data class HudState(
        val running: Boolean = false,
        val reads: List<PlateRead> = emptyList(),
        val frameW: Int = 1,
        val frameH: Int = 1,
        val latencyMs: Int = 0,
        val todayCount: Int = 0,
        val queueCount: Int = 0,
        val hasGps: Boolean = false,
        val droppedNoGps: Int = 0,
        val recent: List<Detection> = emptyList(), // newest first, capped
        val battTempC: Float? = null,
        val batteryPct: Int? = null,
        val thermalStatus: Int = 0, // PowerManager.THERMAL_STATUS_*
        val syncStatus: String = "", // last SyncWorker outcome, e.g. "ok 12:04 (+6)"
        val burstPending: Int = 0, // capture v2: ring windows queued for burst re-scan
        val captureStats: String = "", // this session's v1/v2 + thermal summary, see buildCaptureStats()
    )

    inner class LocalBinder : Binder() {
        val service: CaptureService get() = this@CaptureService
    }

    private val binder = LocalBinder()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(HudState())
    val state: StateFlow<HudState> get() = _state

    @Volatile
    private var alpr: Alpr? = null
    private val dedup = DedupEngine()
    private lateinit var location: LocationProvider
    private lateinit var store: SightingStore
    private var tone: ToneGenerator? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    @Volatile
    private var exposure: ExposureController? = null // shutter-priority cap; null until camera binds
    private var surfaceProvider: Preview.SurfaceProvider? = null // main thread only
    private var lastProcess = 0L
    private var emaLatency = 0f
    private var droppedNoGps = 0
    private var lastTempRead = 0L
    private var zoomPass = false // scan thread only; see nextZoomPass() for the actual schedule
    private var closeBoxRecentFrames = 0 // scan thread only; counts down after a close-range hit
    private var framesSinceFullFrame = 0 // scan thread only; drives the full-frame keep-alive
    @Volatile
    private var thermalStatus = 0 // written on main (listener), read on the analysis thread
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    // Capture v2 (buffered): every frame feeds a RAM ring of encoded 4K, far-plate hits re-scan
    // the surrounding seconds on the burst thread. All null/idle when the setting is off.
    private var captureV2 = false
    private var ring: AuRing? = null
    private var encoder: FrameRingEncoder? = null
    private var burst: BurstProcessor? = null
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val scanBusy = AtomicBoolean(false)
    @Volatile
    private var ptsOffsetMs = 0L // elapsedRealtime - camera pts: maps burst pts onto dedup time
    private var warnedRotation = false
    private var lastRingFeed = 0L // elapsedRealtime of the last ring feed; see RING_FEED_INTERVAL_MS

    // Diagnostics (docs/android-app.md component 8's blind spot): which analyze() path actually ran,
    // how often burst got triggered/used, and time-in-thermal-bucket, since none of this survived
    // in logcat long enough to check after a drive. Scan thread only except onThermalStatus (main).
    private var framesV1 = 0L
    private var framesV2 = 0L
    private var farHits = 0L
    private var burstPromotions = 0
    private var livePromotions = 0
    private val thermalBucketMs = LongArray(3) // [ok, severe, critical+] — see thermalBucket()
    private var lastStatsSave = 0L

    override fun onCreate() {
        super.onCreate()
        location = LocationProvider(this)
        store = SightingStore.get(this)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { // in-app button / notification action / QS tile
            shutdownCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (_state.value.running) return START_STICKY
        startForeground(
            NOTIFICATION_ID, buildNotification(0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        _state.value = _state.value.copy(running = true, todayCount = todayCount(), queueCount = store.unsyncedCount())
        isRunning.value = true
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME) }.getOrNull()
        location.start()
        // Event-driven thermal detection (fires immediately with the current status on
        // registration): the HUD shows it, and analyze() backs the frame rate off with it.
        if (Build.VERSION.SDK_INT >= 29) {
            val listener = PowerManager.OnThermalStatusChangedListener { onThermalStatus(it) }
            thermalListener = listener
            getSystemService(PowerManager::class.java)
                ?.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener)
        }
        captureV2 = runBlocking { AppSettings.read(this@CaptureService).captureV2 }
        warnedRotation = false
        framesV1 = 0L
        framesV2 = 0L
        farHits = 0L
        burstPromotions = 0
        livePromotions = 0
        thermalBucketMs.fill(0L)
        lastStatsSave = 0L
        if (captureV2) {
            val r = AuRing(RING_BYTES)
            ring = r
            val e = FrameRingEncoder(r)
            encoder = e
            burst = BurstProcessor(r, e, { alpr }) { read, ptsUs ->
                val now = ptsUs / 1_000 + ptsOffsetMs
                val promotion = synchronized(dedup) { dedup.observe(read, now) }
                if (promotion != null) record(promotion, source = "burst")
            }
        }
        // The instance can be restarted after ACTION_STOP while a client keeps it bound — the
        // models survive that round trip, so only load them once.
        if (alpr == null) analysisExecutor.execute {
            val t0 = SystemClock.elapsedRealtime()
            alpr = Alpr(applicationContext)
            AppLog.i(TAG, "ALPR ready in ${SystemClock.elapsedRealtime() - t0} ms")
        }
        startCamera()
        SyncWorker.enqueue(this, wifiOnly())
        return START_STICKY
    }

    /**
     * The activity hands its PreviewView surface in while it's on screen. The provider is kept so
     * it can be applied when the camera finishes its async bind — the activity usually attaches
     * before Preview exists, which used to leave the screen black until a re-attach.
     */
    fun attachPreview(provider: Preview.SurfaceProvider?) {
        ContextCompat.getMainExecutor(this).execute {
            surfaceProvider = provider
            preview?.setSurfaceProvider(provider)
        }
    }

    @AndroidXOptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            if (!_state.value.running) return@addListener // stopped before the camera came up
            // Listener runs on the main executor, same as attachPreview's writes.
            preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
            // 4K: OCR reads from the full-res frame, and the drive test showed 1080p runs out of
            // plate pixels at ~10 m — far too close for oncoming traffic. Falls back to the
            // closest supported size on devices without a 4K analysis stream.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(3840, 2160), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Pin to the default display's rotation *now*, instead of leaving CameraX to infer a
            // target rotation for a headless Service with no window of its own to query — that
            // inference could silently produce rotationDegrees != 0 for the whole session, which
            // disables buffered-capture-v2/burst (falls back to analyzeV1, see analyze()) with
            // only a one-time log to show for it. The car mount doesn't change mid-drive, so a
            // one-shot read here (not a live listener) is enough.
            targetRotation()?.let { analysisBuilder.setTargetRotation(it) }
            // Feed AE's per-frame exposure time to the shutter cap (AUTO-mode signal; MANUAL results
            // just echo our own request, which the controller ignores).
            Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(aeCaptureCallback)
            val analysis = analysisBuilder.build().also { it.setAnalyzer(analysisExecutor, ::analyze) }
            provider.unbindAll()
            val camera =
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            exposure = ExposureController(Camera2CameraControl.from(camera.cameraControl))
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * The default display's current rotation (`Surface.ROTATION_*`), read directly from
     * [DisplayManager] rather than through an Activity/Window — this Service has neither, and
     * CameraX's own target-rotation inference for a windowless use case is what left
     * `rotationDegrees` non-deterministic in the first place (see the call site in [startCamera]).
     */
    private fun targetRotation(): Int? =
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation

    /** AE exposure/ISO per capture result, forwarded to the shutter cap. Runs on a camera thread. */
    private val aeCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            exposure?.onAutoResult(exp, iso)
        }
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (captureV2 && proxy.imageInfo.rotationDegrees == 0) {
                framesV2++
                analyzeV2(proxy)
                return
            }
            if (captureV2 && !warnedRotation) {
                warnedRotation = true
                AppLog.w(
                    TAG,
                    "rotation ${proxy.imageInfo.rotationDegrees}° — the ring stores frames as " +
                        "delivered, so buffered capture is off; falling back to live-only scan"
                )
            }
            framesV1++
            analyzeV1(proxy)
        } catch (t: Throwable) {
            AppLog.e(TAG, "analyze failed", t)
        } finally {
            proxy.close()
        }
    }

    /** v1 (live-only) path: throttle, scan inline on the analysis thread. */
    private fun analyzeV1(proxy: ImageProxy) {
        val engine = alpr ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcess < analysisIntervalMs()) return // thermal-adaptive throttle
        lastProcess = now
        val upright = proxy.toBitmap().rotate(proxy.imageInfo.rotationDegrees)
        scanLive(engine, upright, now, ptsUs = null)
    }

    /**
     * v2 (buffered) path: the RAM ring's encoder is fed on a ~15fps clock (a real bench run showed
     * this device's delivered analysis-frame rate is well above the ~30fps this code used to
     * assume, so a simple every-other-frame toggle would have under-thinned it — a time-based gate
     * hits the target regardless of the actual native rate). ~15fps matches what BurstProcessor
     * already thins ring footage to before it ever uses it (STRIDE_US in BurstProcessor.kt), so
     * encoding faster than that just costs YUV memcpy + hardware-encode cycles burst discards
     * anyway. The live scan is decoupled onto [scanExecutor] so a 200-300 ms inference doesn't
     * stall the ring.
     */
    private fun analyzeV2(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val ptsUs = proxy.imageInfo.timestamp / 1_000
        // Camera pts is monotonic but not guaranteed to be boottime: track the offset so burst
        // reads (stamped in camera pts) land on the dedup/GPS clock (elapsedRealtime).
        ptsOffsetMs = now - ptsUs / 1_000
        // SEVERE: bursts are suspended anyway, so stop paying for encode heat too.
        if (now - lastRingFeed >= RING_FEED_INTERVAL_MS && thermalStatus < PowerManager.THERMAL_STATUS_SEVERE) {
            lastRingFeed = now
            encoder?.feed(proxy)
        }
        val engine = alpr ?: return
        if (now - lastProcess < analysisIntervalMs()) return
        if (!scanBusy.compareAndSet(false, true)) return // previous scan still chewing
        lastProcess = now
        val upright = proxy.toBitmap() // rotationDegrees == 0 on this path
        scanExecutor.execute {
            try {
                scanLive(engine, upright, now, ptsUs)
            } catch (t: Throwable) {
                AppLog.e(TAG, "scan failed", t)
            } finally {
                scanBusy.set(false)
            }
        }
    }

    /** One live-frame scan, shared by both paths; [ptsUs] != null enables the burst trigger. */
    private fun scanLive(engine: Alpr, upright: Bitmap, now: Long, ptsUs: Long?) {
        val t0 = SystemClock.elapsedRealtime()
        // Full-frame catches close plates anywhere; the zoom pass keeps far plates (oncoming
        // traffic near the vanishing point) above the detector's minimum size — ~2.5x the
        // detection range for the same per-frame compute. Dedup merges reads across passes.
        // See nextZoomPass(): coverage isn't a strict 50/50 split, since a fast-closing far
        // plate has a much tighter window in range than a close one does.
        zoomPass = nextZoomPass()
        val reads = engine.analyze(upright, if (zoomPass) centerZoom(upright) else null)
        if (!zoomPass) {
            val farMax = upright.width * FAR_BOX_MAX_W_4K / 3840
            if (reads.any { it.box.x2 - it.box.x1 >= farMax }) closeBoxRecentFrames = CLOSE_PRIORITY_FRAMES
        }
        val dt = (SystemClock.elapsedRealtime() - t0).toFloat()
        emaLatency = if (emaLatency == 0f) dt else emaLatency * 0.8f + dt * 0.2f

        // Brightness feedback for the shutter cap (only acts while it holds the camera in MANUAL).
        exposure?.onFrameLuma(meanLuma(upright))

        for (read in reads) {
            val promotion = synchronized(dedup) { dedup.observe(read, now) }
            if (promotion != null) record(promotion)
        }

        // A small box = a far plate the live pass will likely never OCR in time — hand the
        // surrounding seconds of ring footage to the burst thread (suspended while SEVERE).
        if (ptsUs != null && thermalStatus < PowerManager.THERMAL_STATUS_SEVERE) {
            val farMax = upright.width * FAR_BOX_MAX_W_4K / 3840
            if (reads.any { it.box.x2 - it.box.x1 < farMax }) {
                farHits++
                burst?.onFarHit(ptsUs)
            }
        }

        if (now - lastTempRead > 2_000) {
            thermalBucketMs[thermalBucket(thermalStatus)] += now - lastTempRead
            lastTempRead = now
            readTemperature()
        }
        val stats = buildCaptureStats()
        if (now - lastStatsSave > STATS_SAVE_INTERVAL_MS) {
            lastStatsSave = now
            val ctx = applicationContext
            runBlocking { AppSettings.saveCaptureStats(ctx, stats) }
        }
        _state.value = _state.value.copy(
            reads = reads,
            frameW = upright.width,
            frameH = upright.height,
            latencyMs = emaLatency.toInt(),
            hasGps = location.fresh() != null,
            droppedNoGps = droppedNoGps,
            burstPending = burst?.pending ?: 0,
            captureStats = stats,
        )
    }

    /**
     * Which pass runs next. A strict 50/50 alternation (the old behavior) spends half of every
     * fast-closing far plate's brief window in range on a full-frame pass it doesn't need — full
     * frame exists for close plates, which stay in useful range far longer. So: while a close
     * plate was seen recently ([closeBoxRecentFrames] counts down — it may still be in frame, and
     * a far one could be approaching at the same time), keep alternating as before. Otherwise
     * spend most frames on the zoom pass, taking a full-frame "keep-alive" look only every
     * [FULL_FRAME_KEEPALIVE_STRIDE]th frame — that's the only way to notice a *newly* appearing
     * close plate (e.g. one pulling out right in front) to begin with.
     */
    private fun nextZoomPass(): Boolean {
        if (closeBoxRecentFrames > 0) {
            closeBoxRecentFrames--
            return !zoomPass
        }
        framesSinceFullFrame++
        val fullFrame = framesSinceFullFrame >= FULL_FRAME_KEEPALIVE_STRIDE
        if (fullFrame) framesSinceFullFrame = 0
        return !fullFrame
    }

    /** [ok, severe, critical+] bucket index for [thermalBucketMs], matching PowerManager.THERMAL_STATUS_*. */
    private fun thermalBucket(status: Int): Int = when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> 2
        status >= PowerManager.THERMAL_STATUS_SEVERE -> 1
        else -> 0
    }

    /**
     * This session's v1-vs-v2, burst-usage, and thermal-dwell summary (docs/android-app.md
     * component 8's blind spot per the false-negatives investigation: none of this was visible
     * after a drive, only in logcat's small ring buffer). Cheap to compute; called every processed
     * frame so the HUD is live, only persisted to disk every [STATS_SAVE_INTERVAL_MS].
     */
    private fun buildCaptureStats(): String {
        val totalFrames = framesV1 + framesV2
        val v2Pct = if (totalFrames == 0L) 0 else (framesV2 * 100 / totalFrames).toInt()
        val thermalTotal = thermalBucketMs.sum()
        fun pct(ms: Long) = if (thermalTotal == 0L) 0 else (ms * 100 / thermalTotal).toInt()
        return "v2=${framesV2}f($v2Pct%) v1=${framesV1}f far=$farHits\n" +
            "burstProm=$burstPromotions liveProm=$livePromotions\n" +
            "thermal ok=${pct(thermalBucketMs[0])}% severe=${pct(thermalBucketMs[1])}% " +
            "crit=${pct(thermalBucketMs[2])}%"
    }

    @Synchronized // scan thread in both paths, plus the burst thread in v2
    private fun record(p: DedupEngine.Promotion, source: String = "live") {
        if (source == "burst") burstPromotions++ else livePromotions++
        // Burst promotions land seconds after the pass: stamp them with the fix nearest their
        // first frame, not the fix of "now" (75 m apart at 90 km/h).
        val fix = location.nearest(p.firstSeenMs) ?: location.fresh()
        if (fix == null) {
            // The server requires lat/lon; a sighting at a made-up location is worse than none.
            droppedNoGps++
            AppLog.w(TAG, "dropping ${p.plateText}: no fresh GPS fix")
            return
        }
        store.insert(
            Sighting(
                id = p.id,
                plateText = p.plateText,
                rawText = p.rawText,
                readKind = p.readKind,
                confidence = p.confidence.toDouble(),
                capturedAt = Instant.now().toString(),
                lat = fix.lat,
                lon = fix.lon,
                speedKmh = fix.speedKmh,
                accuracyM = fix.accuracyM,
                country = p.country,
                sourceDevice = SOURCE_DEVICE,
                bufferedV2 = source == "burst",
                deviceTempC = _state.value.battTempC?.toDouble(),
                batteryPct = _state.value.batteryPct,
            )
        )
        val today = todayCount()
        val detection = Detection(
            plate = p.plateText,
            country = p.country,
            confidence = p.confidence,
            atMs = SystemClock.elapsedRealtime(),
            clock = LocalTime.now().format(CLOCK_FORMAT),
            readKind = p.readKind,
            year = PlateValidator.estimateRegistrationYear(p.plateText, p.country),
        )
        // Eyes-free confirmation while driving; a different tone flags a foreign plate.
        tone?.startTone(
            if (p.country == "IT") ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, 150
        )
        _state.value = _state.value.copy(
            todayCount = today,
            queueCount = store.unsyncedCount(),
            recent = (listOf(detection) + _state.value.recent).take(RECENT_LIMIT),
        )
        notify(today)
        SyncWorker.enqueue(this, wifiOnly())
        AppLog.i(TAG, "recorded ${p.plateText} (${p.readKind}, ${p.frames} frames)")
    }

    /**
     * The zoom pass's window: a centered square, 40% of the frame width. Far plates cluster
     * around the vanishing point (an oncoming car 25 m out sits ~250 px off-center at 4K), and
     * anything that has slid outside this square is close enough for the full-frame pass.
     */
    private fun centerZoom(frame: Bitmap): Rect {
        val side = minOf(frame.height, frame.width * 2 / 5)
        val cx = frame.width / 2
        val cy = frame.height / 2
        return Rect(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    }

    /** Mean luma over a sparse grid (green channel ≈ luma) — cheap brightness proxy for the cap. */
    private fun meanLuma(frame: Bitmap): Int {
        val step = 16
        var sum = 0L
        var n = 0
        var y = 0
        while (y < frame.height) {
            var x = 0
            while (x < frame.width) {
                sum += (frame.getPixel(x, y) ushr 8) and 0xFF
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    /** Battery temperature (the practical whole-device proxy) and charge level; thermal status
     *  comes by listener, separately. Both are stamped onto sightings recorded around this time
     *  (see [record]) — diagnostics for the false-negatives investigation. */
    private fun readTemperature() {
        val batt = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        _state.value = _state.value.copy(
            battTempC = if (tenths == Int.MIN_VALUE) null else tenths / 10f,
            batteryPct = if (level < 0 || scale <= 0) null else level * 100 / scale,
        )
    }

    private fun onThermalStatus(status: Int) {
        if (status == thermalStatus) return
        thermalStatus = status
        _state.value = _state.value.copy(thermalStatus = status)
        AppLog.i(TAG, "thermal status -> $status, analysis interval ${analysisIntervalMs()} ms")
    }

    /** Thermal fps throttling disabled 2026-07-10 as an experiment — see memory note. */
    private fun analysisIntervalMs(): Long = MIN_INTERVAL_MS

    /** Refresh queue/today counts + last sync outcome (the activity polls this). */
    fun refreshCounts() {
        // While running, scanLive already keeps captureStats live every processed frame — don't
        // clobber it with the (up to 30s stale, or absent) persisted value here. Once stopped,
        // scanLive isn't updating it anymore, so this is what lets you check the last drive's
        // v1/v2 + thermal summary after the fact.
        val stats = if (_state.value.running) _state.value.captureStats else
            runBlocking { AppSettings.readCaptureStats(this@CaptureService) }
        _state.value = _state.value.copy(
            todayCount = todayCount(),
            queueCount = store.unsyncedCount(),
            syncStatus = runBlocking { AppSettings.readSyncStatus(this@CaptureService) },
            captureStats = stats,
        )
    }

    private fun todayCount(): Int =
        store.countSince(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toString())

    private fun wifiOnly(): Boolean = runBlocking { AppSettings.read(this@CaptureService).wifiOnly }

    private fun buildNotification(todayCount: Int): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Recording plates — $todayCount today")
            .setOngoing(true)
            .setContentIntent(tap)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun notify(todayCount: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(todayCount))
    }

    /**
     * Tears capture down immediately. stopSelf()/stopService() alone are not enough: while the
     * activity keeps the service bound (BIND_AUTO_CREATE), the instance stays alive after them, so
     * without this the camera kept recording and the UI showed "Stop" until the screen cycled off.
     */
    private fun shutdownCapture() {
        if (!_state.value.running) return
        isRunning.value = false
        // Final save regardless of the periodic interval, so a manual stop never loses this
        // session's v1/v2 + thermal summary — the whole point is it survives after the drive.
        runBlocking { AppSettings.saveCaptureStats(applicationContext, buildCaptureStats()) }
        exposure?.reset() // drop any MANUAL override before the camera unbinds
        exposure = null
        cameraProvider?.unbindAll()
        preview = null
        burst?.close() // before the encoder: an in-flight burst still reads the ring
        burst = null
        encoder?.release()
        encoder = null
        ring = null
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let {
            getSystemService(PowerManager::class.java)?.removeThermalStatusListener(it)
        }
        thermalListener = null
        location.stop()
        tone?.release()
        tone = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        _state.value = _state.value.copy(running = false, reads = emptyList())
    }

    override fun onDestroy() {
        shutdownCapture()
        // Drain the scan thread before closing the models it may still be running.
        scanExecutor.shutdown()
        runCatching { scanExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        analysisExecutor.execute { alpr?.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    }

    companion object {
        /** Whether capture is active, readable without binding (the QS tile checks it). */
        val isRunning = MutableStateFlow(false)

        const val ACTION_STOP = "com.mssdvd.platestracker.action.STOP"

        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val TONE_VOLUME = 80 // 0..100
        private const val TARGET_FPS = 8
        private const val MIN_INTERVAL_MS = 1000L / TARGET_FPS
        private const val SOURCE_DEVICE = "android-phone"
        private const val RECENT_LIMIT = 8
        // Capture v2: ~6-7 s of 4K HEVC at 20 Mbps (duration is bitrate-bound, not fps-bound, so
        // this held even after thinning the feed to ~15fps — see FrameRingEncoder.kt).
        private const val RING_BYTES = 18 * 1024 * 1024
        // Burst trigger: boxes narrower than this (at 4K width, scaled otherwise) are "far" —
        // ~90 px is roughly 12+ m out, where the live scan's OCR success collapses.
        private const val FAR_BOX_MAX_W_4K = 90
        // Ring feed clock (analyzeV2): time-based, not frame-count-based, since the actual
        // delivered analysis-frame rate varies by device and was observed well above the ~30fps
        // this used to assume — see analyzeV2()'s doc comment.
        private const val RING_FEED_INTERVAL_MS = 66L // ~15fps
        // nextZoomPass(): frames of elevated full-frame/zoom alternation after a close-range hit
        // (~2s at the 8fps target), and how often a full-frame "keep-alive" look runs otherwise.
        private const val CLOSE_PRIORITY_FRAMES = 16
        private const val FULL_FRAME_KEEPALIVE_STRIDE = 4
        // How often the v1/v2 + thermal summary is written to disk during a session (also saved
        // unconditionally on stop) — frequent enough to survive a crash, rare enough not to spam
        // DataStore writes on every processed frame.
        private const val STATS_SAVE_INTERVAL_MS = 30_000L
        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
