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

That's it — no commit step. Each pull just leaves a new dated folder
behind.

## Notes

- Logcat's main buffer was raised to 16 MiB via `adb logcat -G 16M`,
  but that setting is not persisted across reboots on this user build
  (`persist.logd.size` isn't shell-settable) — re-run `-G 16M`, or set
  it under Developer options > "Logger buffer sizes", if a reboot
  happened since the last pull and recent entries look truncated.
- `device-dumps/` is intentionally excluded from the main repo's git
  history (see top-level `.gitignore`) — never `git add` anything
  under it from the main repo root.
- If `run-as` fails, check the app is actually a debug build
  (`adb shell run-as com.mssdvd.platestracker id` should succeed) and
  that the package id above still matches.
- Old dated folders aren't pruned automatically; delete stale ones by
  hand if `device-dumps/` grows too large.
