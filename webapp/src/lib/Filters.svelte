<script>
  import { filters, resetFilters, data, ui } from './state.svelte.js'
  import { recordsToCsv } from './api.js'

  // datetime-local wants local "YYYY-MM-DDTHH:MM".
  function toLocalInput(date) {
    const tz = date.getTimezoneOffset() * 60_000
    return new Date(date - tz).toISOString().slice(0, 16)
  }

  function preset(days) {
    const since = new Date()
    if (days === 0) since.setHours(0, 0, 0, 0) // today
    else since.setDate(since.getDate() - days)
    filters.since = toLocalInput(since)
    filters.until = ''
  }

  function exportCsv() {
    const blob = new Blob([recordsToCsv(data.records)], { type: 'text/csv' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `sightings-${new Date().toISOString().slice(0, 10)}.csv`
    a.click()
    URL.revokeObjectURL(a.href)
  }
</script>

<div class="filters">
  <h2>Filters</h2>

  <div class="presets">
    <button onclick={() => preset(0)}>Today</button>
    <button onclick={() => preset(7)}>7d</button>
    <button onclick={() => preset(30)}>30d</button>
    <button onclick={() => { filters.since = ''; filters.until = '' }}>All</button>
  </div>

  <label>From
    <input type="datetime-local" bind:value={filters.since} />
  </label>
  <label>To
    <input type="datetime-local" bind:value={filters.until} />
  </label>

  <label>Plate contains
    <input type="text" placeholder="e.g. AB1" bind:value={filters.plate} />
  </label>

  <label>Country
    <select bind:value={filters.country}>
      <option value="">any</option>
      <option value="IT">🇮🇹 Italy</option>
      <option value="FR">🇫🇷 France</option>
      <option value="DE">🇩🇪 Germany</option>
      <option value="CH">🇨🇭 Switzerland</option>
      <option value="AT">🇦🇹 Austria</option>
      <option value="SI">🇸🇮 Slovenia</option>
      <option value="ES">🇪🇸 Spain</option>
      <option value="NL">🇳🇱 Netherlands</option>
      <option value="HR">🇭🇷 Croatia</option>
      <option value="UA">🇺🇦 Ukraine</option>
      <option value="RO">🇷🇴 Romania</option>
    </select>
  </label>

  <div class="row">
    <label>Min speed
      <input type="number" min="0" step="5" placeholder="km/h" bind:value={filters.minSpeed} />
    </label>
    <label>Max speed
      <input type="number" min="0" step="5" placeholder="km/h" bind:value={filters.maxSpeed} />
    </label>
  </div>

  <label>Min confidence: {filters.minConfidence || 0}
    <input type="range" min="0" max="1" step="0.05" bind:value={filters.minConfidence} />
  </label>

  <label class="repeat">
    <input
      type="checkbox"
      checked={filters.minCount === '2'}
      onchange={(e) => (filters.minCount = e.target.checked ? '2' : '')}
    />
    Only plates seen more than once
  </label>
  {#if filters.minCount}
    <label>Seen at least
      <input type="number" min="2" step="1" bind:value={filters.minCount} /> times
    </label>
  {/if}

  <button onclick={resetFilters}>Reset</button>

  <h2 class="section">Map</h2>
  <label class="repeat">
    <input type="checkbox" bind:checked={ui.showHeatmap} />
    Heatmap
  </label>
  <label class="repeat">
    <input type="checkbox" bind:checked={ui.showPoints} />
    Points
  </label>

  <button onclick={exportCsv} disabled={!data.records.length}>
    Export CSV ({data.records.length})
  </button>
</div>

<style>
  .filters { display: flex; flex-direction: column; gap: 0.6rem; }
  .filters h2 { margin: 0 0 0.2rem; font-size: 1rem; }
  .filters h2.section { margin-top: 0.6rem; }
  .presets { display: flex; gap: 0.3rem; }
  .presets button { flex: 1; margin: 0; padding: 0.25rem 0; font-size: 0.72rem; }
  button:disabled { opacity: 0.5; cursor: default; }
  label { display: flex; flex-direction: column; font-size: 0.78rem; gap: 0.2rem; color: #cbd2dd; }
  .row { display: flex; gap: 0.5rem; }
  .row label { flex: 1; }
  input, select { padding: 0.3rem; border-radius: 6px; border: 1px solid #3a4151; background: #1b2030; color: #e6e9ef; font: inherit; }
  input[type='range'] { padding: 0; }
  .repeat { flex-direction: row; align-items: center; gap: 0.4rem; }
  .repeat input { width: auto; }
  button { margin-top: 0.3rem; padding: 0.4rem; border-radius: 6px; border: 1px solid #3a4151; background: #2a3142; color: #e6e9ef; cursor: pointer; }
  button:hover { background: #343c50; }
</style>
