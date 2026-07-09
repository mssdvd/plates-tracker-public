<script>
  import { onMount } from 'svelte'
  import maplibregl from 'maplibre-gl'
  import 'maplibre-gl/dist/maplibre-gl.css'
  import { data, ui } from './state.svelte.js'

  let mapEl
  let map
  let loaded = $state(false)

  // Keyless raster style using OpenStreetMap tiles (fine for personal/dev use).
  const style = {
    version: 8,
    sources: {
      osm: {
        type: 'raster',
        tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
        tileSize: 256,
        attribution: '© OpenStreetMap contributors',
      },
    },
    layers: [{ id: 'osm', type: 'raster', source: 'osm' }],
  }

  function featureCollection(records) {
    return {
      type: 'FeatureCollection',
      features: records.map((r) => ({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [r.lon, r.lat] },
        properties: { plate: r.plate_text, time: r.captured_at, conf: r.confidence },
      })),
    }
  }

  onMount(() => {
    map = new maplibregl.Map({ container: mapEl, style, center: [12.5, 42.5], zoom: 4.5 })
    map.addControl(new maplibregl.NavigationControl(), 'top-right')
    map.on('load', () => {
      map.addSource('sightings', { type: 'geojson', data: featureCollection([]) })
      map.addLayer({
        id: 'heat',
        type: 'heatmap',
        source: 'sightings',
        maxzoom: 12,
        paint: { 'heatmap-radius': 18, 'heatmap-opacity': 0.6 },
      })
      map.addLayer({
        id: 'pts',
        type: 'circle',
        source: 'sightings',
        minzoom: 9,
        paint: {
          'circle-radius': 5,
          'circle-color': '#e0533d',
          'circle-stroke-width': 1,
          'circle-stroke-color': '#fff',
        },
      })
      // Selected plate's points, drawn on top of the regular dots.
      map.addLayer({
        id: 'sel',
        type: 'circle',
        source: 'sightings',
        filter: ['==', ['get', 'plate'], ''],
        paint: {
          'circle-radius': 8,
          'circle-color': '#ffd23f',
          'circle-stroke-width': 2,
          'circle-stroke-color': '#111',
        },
      })
      map.on('click', 'pts', (e) => {
        const p = e.features[0].properties
        ui.selectedPlate = p.plate // opens the drill-down panel
        new maplibregl.Popup()
          .setLngLat(e.lngLat)
          .setHTML(`<b>${p.plate}</b><br>${new Date(p.time).toLocaleString()}<br>conf ${(+p.conf).toFixed(2)}`)
          .addTo(map)
      })
      map.on('mouseenter', 'pts', () => (map.getCanvas().style.cursor = 'pointer'))
      map.on('mouseleave', 'pts', () => (map.getCanvas().style.cursor = ''))
      loaded = true
    })
    return () => map?.remove()
  })

  // Push filtered records into the map, and fit to them on the first non-empty load.
  let fitted = false
  $effect(() => {
    const recs = data.records
    if (!loaded || !map?.getSource('sightings')) return
    map.getSource('sightings').setData(featureCollection(recs))
    if (recs.length && !fitted) {
      const b = new maplibregl.LngLatBounds()
      for (const r of recs) b.extend([r.lon, r.lat])
      map.fitBounds(b, { padding: 50, maxZoom: 13, duration: 0 })
      fitted = true
    }
  })

  // Layer toggles + selection highlight.
  $effect(() => {
    const { showHeatmap, showPoints, selectedPlate } = ui
    if (!loaded) return
    map.setLayoutProperty('heat', 'visibility', showHeatmap ? 'visible' : 'none')
    map.setLayoutProperty('pts', 'visibility', showPoints ? 'visible' : 'none')
    map.setFilter('sel', ['==', ['get', 'plate'], selectedPlate])
  })

  // One-shot fly-to requests (from the plate-detail history rows).
  $effect(() => {
    const target = ui.flyTo
    if (!loaded || !target) return
    map.flyTo({ center: target, zoom: Math.max(map.getZoom(), 13) })
    ui.flyTo = null
  })
</script>

<div bind:this={mapEl} class="map"></div>

<style>
  .map { width: 100%; height: 100%; }
  :global(.maplibregl-popup-content) { color: #111; font-size: 0.8rem; }
</style>
