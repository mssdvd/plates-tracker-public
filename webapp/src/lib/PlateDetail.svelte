<script>
  import { auth, ui } from './state.svelte.js'
  import { fetchPlateHistory } from './api.js'
  import { registrationYear } from './series.js'

  let history = $state([])
  let loading = $state(false)
  let error = $state('')

  const FLAGS = { IT: '🇮🇹', FR: '🇫🇷', DE: '🇩🇪', CH: '🇨🇭', AT: '🇦🇹', SI: '🇸🇮', ES: '🇪🇸', NL: '🇳🇱', HR: '🇭🇷', UA: '🇺🇦', RO: '🇷🇴' }

  // Records come back newest-first from the API.
  const first = $derived(history.at(-1))
  const last = $derived(history[0])

  $effect(() => {
    const plate = ui.selectedPlate
    if (!plate || !auth.token) return
    loading = true
    error = ''
    fetchPlateHistory(plate, auth.token)
      .then((r) => (history = r))
      .catch((e) => (error = e.message))
      .finally(() => (loading = false))
  })

  const fmt = (t) => new Date(t).toLocaleString([], { dateStyle: 'short', timeStyle: 'short' })
</script>

<div class="detail">
  <div class="head">
    <h2 class="plate">{ui.selectedPlate}</h2>
    <button class="close" onclick={() => (ui.selectedPlate = '')} title="Back to stats">✕</button>
  </div>

  {#if loading}
    <p class="muted">loading…</p>
  {:else if error}
    <p class="err">{error}</p>
  {:else if !history.length}
    <p class="muted">No sightings recorded.</p>
  {:else}
    <div class="cards">
      <div class="card"><span class="num">{history.length}</span><span class="lbl">sightings</span></div>
      <div class="card">
        <span class="num">{FLAGS[last.country] ?? '🏳️'}</span>
        <span class="lbl">{last.country ?? '?'}</span>
      </div>
    </div>

    <dl>
      <dt>First seen</dt><dd>{fmt(first.captured_at)}</dd>
      <dt>Last seen</dt><dd>{fmt(last.captured_at)}</dd>
      {#if registrationYear(ui.selectedPlate, last.country)}
        <dt>Series</dt>
        <dd>registered ~{registrationYear(ui.selectedPlate, last.country)}</dd>
      {/if}
    </dl>

    <h3>History</h3>
    <ul class="rows">
      {#each history as r (r.id)}
        <li>
          <button class="row" onclick={() => (ui.flyTo = [r.lon, r.lat])} title="Show on map">
            <span class="when">{fmt(r.captured_at)}</span>
            <span class="meta">
              {r.speed_kmh != null ? `${Math.round(r.speed_kmh)} km/h` : '—'}
              · conf {(+r.confidence).toFixed(2)}
              {#if r.read_kind === 'corrected'}· corrected{/if}
            </span>
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .detail { display: flex; flex-direction: column; gap: 0.5rem; }
  .head { display: flex; align-items: center; justify-content: space-between; }
  h2.plate { margin: 0; font-size: 1.1rem; font-family: ui-monospace, monospace; letter-spacing: 0.06em; }
  .close { background: #2a3142; border: 1px solid #3a4151; color: #e6e9ef; border-radius: 6px; padding: 0.15rem 0.5rem; cursor: pointer; }
  .close:hover { background: #343c50; }
  .muted { color: #788; font-size: 0.85rem; }
  .err { color: #ffd7d7; font-size: 0.8rem; }
  .cards { display: flex; gap: 0.5rem; }
  .card { flex: 1; background: #1b2030; border: 1px solid #2c3344; border-radius: 8px; padding: 0.5rem; display: flex; flex-direction: column; }
  .num { font-size: 1.5rem; font-weight: 700; }
  .lbl { font-size: 0.7rem; color: #9aa3b2; }
  dl { display: grid; grid-template-columns: auto 1fr; gap: 0.15rem 0.6rem; margin: 0; font-size: 0.78rem; }
  dt { color: #9aa3b2; }
  dd { margin: 0; }
  h3 { margin: 0.4rem 0 0; font-size: 0.8rem; color: #9aa3b2; font-weight: 600; }
  .rows { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
  .row { width: 100%; display: flex; flex-direction: column; align-items: flex-start; gap: 0.1rem; padding: 0.3rem 0.4rem; background: #1b2030; border: 1px solid #2c3344; border-radius: 6px; color: #e6e9ef; cursor: pointer; font: inherit; text-align: left; }
  .row:hover { background: #232a3c; }
  .when { font-size: 0.75rem; }
  .meta { font-size: 0.68rem; color: #9aa3b2; }
</style>
