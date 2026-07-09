<script>
  import { data, ui } from './state.svelte.js'

  const stats = $derived(data.stats)
  const perHourMax = $derived(stats ? Math.max(1, ...Object.values(stats.per_hour)) : 1)
  const perDayEntries = $derived(stats ? Object.entries(stats.per_day).slice(-14) : [])
  const perDayMax = $derived(perDayEntries.length ? Math.max(1, ...perDayEntries.map(([, c]) => c)) : 1)
  // Country breakdown, most-seen first. "?" = plates with no country recorded.
  const countryEntries = $derived(
    stats && stats.by_country ? Object.entries(stats.by_country).sort(([, a], [, b]) => b - a) : [],
  )
  const countryMax = $derived(countryEntries.length ? Math.max(1, ...countryEntries.map(([, c]) => c)) : 1)

  const FLAGS = { IT: '🇮🇹', FR: '🇫🇷', DE: '🇩🇪', CH: '🇨🇭', AT: '🇦🇹', SI: '🇸🇮', ES: '🇪🇸', NL: '🇳🇱', HR: '🇭🇷', UA: '🇺🇦', RO: '🇷🇴' }
  const flag = (c) => FLAGS[c] ?? '🏳️'
</script>

<div class="stats">
  <h2>Stats</h2>
  {#if !stats}
    <p class="muted">No data.</p>
  {:else}
    <div class="cards">
      <div class="card"><span class="num">{stats.total_sightings}</span><span class="lbl">sightings</span></div>
      <div class="card"><span class="num">{stats.unique_plates}</span><span class="lbl">unique plates</span></div>
    </div>

    <h3>By hour of day</h3>
    <div class="hours">
      {#each Array(24) as _, h}
        <div class="hbar" title={`${h}:00 — ${stats.per_hour[h] ?? 0}`}>
          <div class="fill" style={`height:${((stats.per_hour[h] ?? 0) / perHourMax) * 100}%`}></div>
          {#if h % 6 === 0}<span class="tick">{h}</span>{/if}
        </div>
      {/each}
    </div>

    <h3>Recent days</h3>
    <div class="days">
      {#each perDayEntries as [day, count]}
        <div class="drow">
          <span class="day">{day.slice(5)}</span>
          <div class="track"><div class="dfill" style={`width:${(count / perDayMax) * 100}%`}></div></div>
          <span class="dcount">{count}</span>
        </div>
      {/each}
    </div>

    {#if countryEntries.length}
      <h3>By country</h3>
      <div class="days">
        {#each countryEntries as [code, count]}
          <div class="drow">
            <span class="day">{flag(code)} {code}</span>
            <div class="track"><div class="cfill" style={`width:${(count / countryMax) * 100}%`}></div></div>
            <span class="dcount">{count}</span>
          </div>
        {/each}
      </div>
    {/if}

    <h3>Most-seen plates</h3>
    <ol class="top">
      {#each stats.top_plates as p}
        <li>
          <button class="plate" onclick={() => (ui.selectedPlate = p.plate)} title="Show history">
            {p.plate}
          </button>
          <span class="cnt">{p.count}×</span>
        </li>
      {/each}
    </ol>
  {/if}
</div>

<style>
  .stats { display: flex; flex-direction: column; gap: 0.5rem; }
  h2 { margin: 0; font-size: 1rem; }
  h3 { margin: 0.6rem 0 0.1rem; font-size: 0.8rem; color: #9aa3b2; font-weight: 600; }
  .muted { color: #788; font-size: 0.85rem; }
  .cards { display: flex; gap: 0.5rem; }
  .card { flex: 1; background: #1b2030; border: 1px solid #2c3344; border-radius: 8px; padding: 0.5rem; display: flex; flex-direction: column; }
  .num { font-size: 1.5rem; font-weight: 700; }
  .lbl { font-size: 0.7rem; color: #9aa3b2; }
  .hours { display: flex; align-items: flex-end; gap: 2px; height: 70px; }
  .hbar { flex: 1; height: 100%; display: flex; flex-direction: column; justify-content: flex-end; position: relative; }
  .fill { background: #e0533d; border-radius: 2px 2px 0 0; min-height: 1px; }
  .tick { position: absolute; bottom: -14px; left: 0; font-size: 0.6rem; color: #788; }
  .days { display: flex; flex-direction: column; gap: 2px; margin-top: 0.5rem; }
  .drow { display: flex; align-items: center; gap: 0.4rem; font-size: 0.72rem; }
  .day { width: 36px; color: #9aa3b2; }
  .track { flex: 1; background: #1b2030; border-radius: 4px; height: 10px; }
  .dfill { background: #4a8; height: 100%; border-radius: 4px; }
  .cfill { background: #5a8dd6; height: 100%; border-radius: 4px; }
  .dcount { width: 22px; text-align: right; color: #cbd2dd; }
  .top { margin: 0.2rem 0 0; padding-left: 1.2rem; display: flex; flex-direction: column; gap: 0.15rem; }
  .top li { display: flex; justify-content: space-between; font-size: 0.78rem; }
  .plate { font-family: ui-monospace, monospace; letter-spacing: 0.04em; background: none; border: none; color: #e6e9ef; padding: 0; font-size: inherit; cursor: pointer; }
  .plate:hover { color: #ffd23f; text-decoration: underline; }
  .cnt { color: #9aa3b2; }
</style>
