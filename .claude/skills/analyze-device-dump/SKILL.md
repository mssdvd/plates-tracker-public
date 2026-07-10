---
name: analyze-device-dump
description: Pull a fresh device dump, analyze the DB + logcat against the previous dump, update the committed docs with dated field findings, write a REPORT.md next to the dump, then produce a fix plan for the NEW findings. Use when the user asks to analyze a drive test, pull-and-analyze, or work up a field report from the phone.
---

# Analyze device dump

End-to-end field-test workflow: **pull → analyze → update docs → REPORT.md →
fix plan**. It supersedes [`pull-device-dump`](../pull-device-dump/SKILL.md)
(which only pulls) — call that skill for step 1, don't restate it here.

The value is a comparative analysis: every dump is read **against the previous
dated dump**, and the findings that reach the repo are dated inline annotations
in the committed docs, not the (gitignored) report.

## Run the analysis on Fable

Steps 2–5 (analysis, doc edits, REPORT.md, fix plan) **must run on the Fable
model**. Do the pull (step 1) inline — it needs local `adb` and is fast — then
dispatch the rest to a Fable subagent:

```
Agent(subagent_type: "claude", model: "fable",
      description: "analyze device dump",
      prompt: "<the dump dir path> + the steps 2–5 below, verbatim")
```

Pass the concrete `$dir` you created in step 1 and the previous dump dir so the
agent doesn't have to rediscover them. The agent has Bash + Read/Edit/Write; it
runs the queries, edits the docs, writes REPORT.md, and returns the fix plan.

## Step 1 — Pull (inline, not on Fable)

Invoke the **pull-device-dump** skill. It creates
`device-dumps/$(date +%Y-%m-%d_%H%M%S)/` with `sightings.db`, `logcat_all.txt`,
`app_log.txt`, and `settings.preferences_pb`. `app_log.txt` (since 2026-07-10)
is the app's own persistent mirror of every `Log.*` call and isn't subject to
logd's ring-buffer resets, so **it's now the primary log source** — read it
instead of `logcat_all.txt`, which is still worth a glance for non-app system
context but is not reliable for app lines pulled well after the drive.

Set for the rest of the workflow:
```bash
CUR=device-dumps/<new dir>/sightings.db
PREV=device-dumps/<previous dated dir>/sightings.db   # ls -d device-dumps/*/ | tail -2 | head -1
```

## Step 2 — Analyze

All queries are validated against real dumps and reproduce the numbers in the
two existing REPORT.md files. `sightings` schema:
`id, plate_text, read_kind, confidence, captured_at, lat, lon, speed_kmh,
accuracy_m, country, source_device, synced, raw_text, buffered_v2,
device_temp_c, battery_pct`. Cross-DB diff is done by `ATTACH`-ing PREV and
filtering to ids not present there — that "new rows" set is the drive under test.

```bash
# Delta vs previous dump — the new rows are what this drive produced
sqlite3 $CUR "ATTACH '$PREV' AS prev;
  SELECT COUNT(*) AS new_rows FROM sightings s
  WHERE s.id NOT IN (SELECT id FROM prev.sightings);"

# Junk: empty and short (<7 char) reads among new rows
sqlite3 $CUR "ATTACH '$PREV' AS prev; SELECT
  SUM(plate_text='' OR plate_text IS NULL) empty,
  SUM(LENGTH(plate_text)<7) short, COUNT(*) total
  FROM sightings s WHERE s.id NOT IN (SELECT id FROM prev.sightings);"

# Duplicates: same plate promoted more than once in this drive
sqlite3 $CUR "ATTACH '$PREV' AS prev; SELECT plate_text, COUNT(*) c
  FROM sightings s WHERE s.id NOT IN (SELECT id FROM prev.sightings)
  AND plate_text<>'' GROUP BY plate_text HAVING c>1 ORDER BY c DESC;"

# Country-label sanity: non-IT label on a structurally-Italian 7-char read
# (the known region-head-at-the-boundary gap — a wrong country / false beep)
sqlite3 $CUR "ATTACH '$PREV' AS prev; SELECT plate_text, country, confidence
  FROM sightings s WHERE s.id NOT IN (SELECT id FROM prev.sightings)
  AND country<>'IT' AND plate_text GLOB '[A-Z][A-Z][0-9][0-9][0-9][A-Z][A-Z]';"

# Country distribution + confidence + path (buffered_v2=burst, 0=live)
sqlite3 $CUR "ATTACH '$PREV' AS prev; SELECT country, buffered_v2,
  COUNT(*), ROUND(AVG(confidence),3) FROM sightings s
  WHERE s.id NOT IN (SELECT id FROM prev.sightings) GROUP BY 1,2;"

# Session segmentation: gaps >60s between consecutive reads = restarts/sessions
sqlite3 $CUR "ATTACH '$PREV' AS prev;
  WITH new AS (SELECT captured_at FROM sightings s
    WHERE s.id NOT IN (SELECT id FROM prev.sightings) ORDER BY captured_at),
  o AS (SELECT captured_at, LAG(captured_at) OVER (ORDER BY captured_at) p FROM new)
  SELECT p, captured_at, CAST((julianday(captured_at)-julianday(p))*86400 AS INT) gap_s
  FROM o WHERE gap_s>60;"

# Device health: temp rise + battery drain across the drive
sqlite3 -header $CUR "ATTACH '$PREV' AS prev; SELECT MIN(device_temp_c) tmin,
  MAX(device_temp_c) tmax, MAX(battery_pct) bat_hi, MIN(battery_pct) bat_lo,
  MIN(captured_at) t0, MAX(captured_at) t1 FROM sightings s
  WHERE s.id NOT IN (SELECT id FROM prev.sightings) AND plate_text<>'';"

# Persisted capture stats — the only telemetry that survives a drive. Covers
# only the LAST session; carries the v1/v2 + burst/live + thermal buckets.
strings -n 2 device-dumps/<new dir>/settings.preferences_pb | grep -iE 'v2=|thermal|prom|far'

# Correlation (PROJECT GOAL — keep first-class, not a footnote): plates seen on
# >1 distinct day. For each, check the reads cluster in space (repeat encounter).
sqlite3 $CUR "SELECT plate_text, COUNT(DISTINCT substr(captured_at,1,10)) days,
  COUNT(*) n FROM sightings WHERE plate_text<>'' AND country='IT'
  GROUP BY plate_text HAVING days>1 ORDER BY days DESC, n DESC;"

# Diagnostics: app log + sync backlog
wc -l device-dumps/<new dir>/app_log.txt                    # sanity: non-empty, covers the drive window
grep -E ' [EW]/' device-dumps/<new dir>/app_log.txt || true  # warnings/errors worth triaging
sqlite3 $CUR "SELECT synced, COUNT(*) FROM sightings GROUP BY synced;"
```

Interpret, don't just tabulate: a new junk/dup class is a **regression**; a
clean drive **validates** a prior fix but says nothing about recall (a gate that
drops real cars looks identical). Note session/thermal cause where the data
allows; `app_log.txt` should cover this now (see [`pull-device-dump`](../pull-device-dump/SKILL.md)'s
notes) — flag it explicitly if that file is unexpectedly empty or short for
the drive's duration.

## Step 3 — Update the committed docs

The REPORT.md is local-only (`device-dumps/` is gitignored). The lasting trail
is **dated inline annotations** in the relevant doc section, matching the
existing house style:

- Region-head / OCR / country findings → `docs/model-specs.md`
- Capture pipeline / burst path / thermal / dedup → `docs/android-app.md`

Format: `**YYYY-MM-DD (context) field check:** …` (or `⚠️`/`🔴` for a live
defect), one or two sentences, ending with a link back to the dump:
`` (`device-dumps/<new dir>/REPORT.md`) ``. Update the *existing* line about
that mechanism rather than appending a new paragraph; if a prior fix is now
validated or a gap is now closed, say so on the same line.

## Step 4 — Write REPORT.md

Write `device-dumps/<new dir>/REPORT.md`. Follow the two existing reports as the
template. Required sections: title (`# Field report — <date> <label> (dump
\`<dir>\`)`), **TL;DR** (what validated, what's the new top issue), a
**fix-by-fix scorecard vs the previous report's recommendations**, session
composition (n=new rows), device/thermal, **correlation signal**, diagnostics
gaps, and **Recommendations (ranked)**. State sample-size and recall caveats
honestly. Never `git add` under `device-dumps/`.

## Step 5 — Generate the fix plan

A ranked plan covering only findings **new** since the previous dump — skip
anything already captured by a prior report's recommendations or the docs.
Respect the standing constraints: **server/sync work is on hold**; **thermal is
the current top issue**, so a thermal finding outranks a cosmetic one. For each
item: the finding, the file/component to touch, and rough effort. Write it to
`device-dumps/<new dir>/FIX_PLAN.md` (colocated with the dump so it travels with
the evidence) and surface the ranked list in the final reply.

## Notes
- Adjust the IT-centric GLOB / `country='IT'` filters if the fleet mix changes.
- If two dumps were pulled the same day, `PREV` is the prior *folder*, not
  yesterday — pick it by folder sort order, not by date arithmetic.
