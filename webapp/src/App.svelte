<script>
  import { filters, data, auth, ui } from './lib/state.svelte.js'
  import { fetchAll } from './lib/api.js'
  import Filters from './lib/Filters.svelte'
  import Stats from './lib/Stats.svelte'
  import PlateDetail from './lib/PlateDetail.svelte'
  import MapView from './lib/MapView.svelte'

  let timer
  // Debounced refetch whenever the filters or token change.
  $effect(() => {
    JSON.stringify(filters) // register dependency on every filter field
    const token = auth.token
    if (!token) return
    clearTimeout(timer)
    timer = setTimeout(async () => {
      data.loading = true
      data.error = ''
      try {
        const r = await fetchAll(filters, token)
        data.records = r.records
        data.stats = r.stats
      } catch (e) {
        data.error = e.message
      } finally {
        data.loading = false
      }
    }, 300)
  })

  function saveToken(e) {
    auth.token = e.target.value
    localStorage.setItem('pt_token', auth.token)
  }
</script>

<header>
  <h1>plates-tracker</h1>
  <div class="status">
    {#if data.loading}<span class="badge">loading…</span>{/if}
    {#if data.error}<span class="badge err">{data.error}</span>{/if}
    <span class="count">{data.records.length} shown</span>
  </div>
  <input class="token" type="password" placeholder="API token" value={auth.token} oninput={saveToken} />
</header>

<div class="layout">
  <aside class="left"><Filters /></aside>
  <main class="map"><MapView /></main>
  <aside class="right">
    {#if ui.selectedPlate}<PlateDetail />{:else}<Stats />{/if}
  </aside>
</div>

<style>
  :global(body) { margin: 0; }
  header {
    display: flex; align-items: center; gap: 1rem;
    padding: 0.5rem 1rem; background: #11151f; border-bottom: 1px solid #232a38;
  }
  header h1 { font-size: 1rem; margin: 0; color: #e6e9ef; }
  .status { display: flex; gap: 0.5rem; align-items: center; margin-left: auto; }
  .badge { font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 10px; background: #2a3142; color: #cbd2dd; }
  .badge.err { background: #5a2222; color: #ffd7d7; }
  .count { font-size: 0.72rem; color: #9aa3b2; }
  .token { padding: 0.35rem 0.5rem; border-radius: 6px; border: 1px solid #3a4151; background: #1b2030; color: #e6e9ef; width: 160px; }
  .layout { display: grid; grid-template-columns: 240px 1fr 300px; height: calc(100vh - 49px); }
  aside { padding: 0.8rem; overflow-y: auto; background: #141926; }
  .left { border-right: 1px solid #232a38; }
  .right { border-left: 1px solid #232a38; }
  .map { position: relative; }
</style>
