package com.mssdvd.platestracker

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mssdvd.platestracker.alpr.Alpr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CONTROL TEST — runs the *full on-device pipeline* (real ORT inference + the Android Bitmap
 * preprocessing the JVM unit tests can't exercise: letterbox via Canvas, RGBA→RGB, BGR→gray,
 * createScaledBitmap) on a known still image, and asserts it reads CN555PL.
 *
 * Why it matters: it decouples the two Gate-B failure modes. If this passes on the phone but a drive
 * reads poorly, the pipeline is sound and the problem is motion/capture. If this fails, the
 * Android-graphics port drifted from onnx_reference.py (which reads CN555PL from this exact image).
 *
 * Control image: app/src/androidTest/assets/control_CN555PL.png — a plate with no bearing on any
 * real vehicle; not sourced from this project's own dashcam footage.
 */
@RunWith(AndroidJUnit4::class)
class AlprControlTest {

    @Test
    fun readsKnownPlateThroughFullOnDevicePipeline() {
        val instr = InstrumentationRegistry.getInstrumentation()
        // Models load from the app-under-test's main assets; control image from the test apk assets.
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bmp = instr.context.assets.open("control_CN555PL.png").use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        assertNotNull("control image failed to decode", bmp)

        val alpr = Alpr(instr.targetContext)
        try {
            val reads = alpr.analyze(bmp!!)
            val texts = reads.map { "${it.text}(${"%.2f".format(it.ocrConfidence)})" }
            assertTrue("no plate detected at all — detector/preprocess broken: $texts", reads.isNotEmpty())

            val hit = reads.firstOrNull { it.text == "CN555PL" }
            assertNotNull("expected CN555PL through full on-device pipeline, got $texts", hit)
            // 2026-07-09: no more format-validity verdict (see PlateRead's class doc) — assert on
            // the OCR model's own region-head guess instead (verified "Italy" via onnx_reference.py).
            assertEquals("CN555PL should read as Italy via the region head", "IT", hit!!.country)
            assertTrue("confidence too low: ${hit.ocrConfidence}", hit.ocrConfidence > 0.6f)
        } finally {
            alpr.close()
        }
    }
}
