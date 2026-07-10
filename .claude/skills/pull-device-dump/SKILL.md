---
name: pull-device-dump
description: Pull the on-device sightings.db and logcat from the field-test Android phone into a dated folder under device-dumps/. Use when the user asks to pull logs/db from the phone, grab a device dump, or investigate a real-world drive test.
---

# Pull device dump

Pulls the app's SQLite history and the device log into a timestamped
subfolder of `device-dumps/` at the repo root. Versioning is just the
folder name (one snapshot per pull, nothing overwritten) — no git, no
index/database. `device-dumps/` is in the top-level `.gitignore`
because the DB/logs contain real license plates.

## Prerequisites

- Device reachable over USB: `adb devices -l` shows the field-test phone.
- App build is debuggable, so `run-as` works without root.
- Package id: `com.mssdvd.platestracker` (read from
  `android/app/build.gradle.kts` if it may have changed).

## Steps

1. Create a dated dump folder (sortable timestamp, second precision so
   two pulls never collide):
   ```bash
   dir="device-dumps/$(date +%Y-%m-%d_%H%M%S)"
   mkdir -p "$dir"
   ```
2. Pull the DB:
   ```bash
   adb shell run-as com.mssdvd.platestracker cat databases/sightings.db \
     > "$dir/sightings.db"
   ```
3. Pull the full log (all buffers, includes the crash buffer):
   ```bash
   adb logcat -b all -d > "$dir/logcat_all.txt"
   ```
4. Pull the app's own persistent log — since 2026-07-10 every `Log.*` call is
   mirrored to a bounded file under app-private storage (`AppLog.kt`), so it
   survives the logd resets that wipe logcat (see Notes). It's the log to
   read; treat `logcat_all.txt` as a secondary source, useful only for
   non-app system lines:
   ```bash
   adb shell run-as com.mssdvd.platestracker cat files/app_log.txt \
     > "$dir/app_log.txt"
   ```
5. Pull the app's DataStore prefs — `last_capture_stats` in it is the
   per-session v1/v2 + thermal summary and often the only telemetry
   that survives a drive (2026-07-10: it alone explained a silent
   burst-path suspension). Note it only keeps the *last* session:
   ```bash
   adb shell run-as com.mssdvd.platestracker \
     cat files/datastore/settings.preferences_pb \
     > "$dir/settings.preferences_pb"
   ```
   It's binary protobuf; `strings -n 2` on it is readable enough.

That's it — no commit step. Each pull just leaves a new dated folder
behind.

## Notes

- Logcat's main buffer was raised to 16 MiB via `adb logcat -G 16M`,
  but the setting does not stick: it's lost across reboots
  (`persist.logd.size` isn't shell-settable) and was found reset to
  256 KiB on 2026-07-10 *without* a reboot (likely a logd restart) —
  retention was ~2.5 min, so a pull 2 h after the drive had zero app
  lines. `app_log.txt` (step 4) is the fix for this — it's app-owned
  storage, not the OS ring buffer, so it isn't affected by logd resets
  and survives until the app's own 5 MiB rotation. Still worth running
  `adb logcat -G 16M` before a drive for the non-app system context
  logcat alone can give you, but the app-side history no longer
  depends on it.
- `device-dumps/` is intentionally excluded from the main repo's git
  history (see top-level `.gitignore`) — never `git add` anything
  under it from the main repo root.
- If `run-as` fails, check the app is actually a debug build
  (`adb shell run-as com.mssdvd.platestracker id` should succeed) and
  that the package id above still matches.
- Old dated folders aren't pruned automatically; delete stale ones by
  hand if `device-dumps/` grows too large.
