package com.mssdvd.platestracker

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.mssdvd.platestracker.alpr.PlateValidator
import com.mssdvd.platestracker.data.Sighting
import com.mssdvd.platestracker.data.SightingStore
import com.mssdvd.platestracker.databinding.ActivityHistoryBinding
import com.mssdvd.platestracker.location.AddressResolver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * On-device history browser over the local sighting log (synced rows are kept, not purged):
 * distinct plates newest-first, then every sighting of a tapped plate. Locations show as a
 * reverse-geocoded address when the Geocoder resolves one, raw coordinates otherwise — resolution
 * is lazy (only for the plate being viewed) and cached in AddressResolver.
 */
class HistoryActivity : AppCompatActivity() {

    private class Row(var title: String, var subtitle: String)

    private class RowAdapter(context: Context, val rows: MutableList<Row>) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            v.findViewById<TextView>(android.R.id.text1).text = rows[position].title
            v.findViewById<TextView>(android.R.id.text2).text = rows[position].subtitle
            return v
        }
    }

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var store: SightingStore
    private var openPlate: String? = null // null = plate list, else that plate's sightings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SightingStore.get(this)

        binding.btnBack.setOnClickListener { if (openPlate != null) showPlates() else finish() }
        onBackPressedDispatcher.addCallback(this) {
            if (openPlate != null) showPlates() else finish()
        }
        showPlates()
    }

    private fun showPlates() {
        openPlate = null
        val summaries = store.plateSummaries()
        binding.title.text = getString(R.string.history_title, summaries.size)
        binding.empty.isVisible = summaries.isEmpty()
        val rows = summaries.map { s ->
            val year = PlateValidator.estimateRegistrationYear(s.plate, s.country)
            Row(
                title = "${s.plate}  ${s.country}" + (year?.let { "  ~$it" } ?: ""),
                subtitle = "${s.count}×   ${localTime(s.firstSeen)} → ${localTime(s.lastSeen)}",
            )
        }
        binding.list.adapter = RowAdapter(this, rows.toMutableList())
        binding.list.setOnItemClickListener { _, _, pos, _ -> showSightings(summaries[pos].plate) }
    }

    private fun showSightings(plate: String) {
        openPlate = plate
        val sightings = store.history(plate)
        binding.title.text = "$plate — ${sightings.size}"
        binding.empty.isVisible = false
        val rows = sightings.map { s ->
            Row(
                title = localTime(s.capturedAt) +
                    "   ${"%.2f".format(s.confidence)}" +
                    correctionNote(s) +
                    (s.speedKmh?.let { "   ${it.toInt()} km/h" } ?: ""),
                subtitle = coords(s.lat, s.lon, s.accuracyM),
            )
        }.toMutableList()
        val adapter = RowAdapter(this, rows)
        binding.list.adapter = adapter
        binding.list.setOnItemClickListener(null)

        // Swap coordinates for addresses as the geocoder answers (cache makes revisits instant).
        sightings.forEachIndexed { i, s ->
            AddressResolver.resolve(this, s.lat, s.lon) { address ->
                if (address != null && openPlate == plate) {
                    rows[i].subtitle = address
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    /** "✎ was FH8T4LW" — the OCR read before confusion correction, when there was one. */
    private fun correctionNote(s: Sighting): String {
        if (s.readKind == "exact") return ""
        val raw = s.rawText?.takeIf { it.isNotBlank() && it != s.plateText }
        return if (raw != null) "   ✎ was $raw" else " ✎"
    }

    private fun coords(lat: Double, lon: Double, accuracyM: Double?): String =
        "%.5f, %.5f".format(Locale.ROOT, lat, lon) +
            (accuracyM?.let { "  (±${it.toInt()} m)" } ?: "")

    private fun localTime(utc: String): String = runCatching {
        Instant.parse(utc).atZone(ZoneId.systemDefault()).format(TS)
    }.getOrDefault(utc)

    companion object {
        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")
    }
}
