# Changelog / 更新日志

Newest first. Entries cover behaviour and flashing changes; pure refactors and
comment cleanups are left to the git history.
最新的在最上面。这里只记录行为变化和烧录方式变化，纯重构和注释整理请查 git 历史。

---

## English

### 2026-09-26 — App 3.4.0 driving-footprint visual refresh

- Replaces year-relative calendar intensity with fixed daily-distance bands: below 25 km, 25–49 km, 50–99 km and 100 km or more. A 100 km day is therefore always shown at the highest intensity across every month and year.
- Restyles Driving Footprint to match Bookkeeping, including paired metric cards, softened category colour, pill-shaped revision and year controls, clearer calendar guidance and consistently aligned trip cards.
- Changes historical trip statistics from red to the shared driving/bookkeeping blue accent while preserving the continuous scroll layout and tap-to-open details.

### 2026-09-26 — App 3.3.3 driving-calendar fixes

- Stops the current-year driving calendar at the end of the current week instead of drawing empty future months.
- Adds a no-activity-to-high-activity colour legend beneath the calendar.
- Makes the overview, calendar and historical trip rows one continuous vertically scrolling list, restoring reliable access to trip details on smaller screens.

### 2026-09-26 — App 3.3.2 refined bookkeeping interface

- Replaces the bookkeeping page's default system-style buttons with a compact pill-shaped segmented control while preserving tap and horizontal-swipe navigation across Fuel, Maintenance, Daily and Statistics.
- Introduces category-coloured primary actions, paired metric cards, clearer empty states and fully tappable record rows with stronger date, amount and status hierarchy.
- Refreshes the Statistics page with a gradient monthly-total hero, labelled annual category proportions and a lighter 12-month stacked chart with grid lines, current-month emphasis and zero-data guidance.

### 2026-09-25 — App 3.3.1 vector license-plate artwork

- Replaces the enlarged low-resolution JPEG character templates with 67 bundled outline glyphs rendered directly through Android paths.
- Fits every province outline to the GA 36-2018 45 mm × 90 mm character dimensions and preserves rectangular strokes that were previously omitted during vector extraction.
- Raises the home-screen perspective source from 880 × 280 to 1760 × 560 pixels and enables filtered mipmap preparation to keep small projected strokes crisp.
- Keeps the canonical 880 × 280 flat plate and the model-specific four-corner installation pipeline, while removing bitmap-source scaling artifacts before the final perspective transform.

### 2026-09-25 — App 3.2.33 / Firmware 3.2.16 driving calendar, bookkeeping and multi-phone continuity

- Adds a Codex-style yearly driving activity calendar and vehicle-scoped totals for trips, active days, distance, duration and fuel.
- Replaces the Fuel tab with swipeable Fuel, Maintenance, Daily and Statistics bookkeeping sections, including monthly/yearly visual summaries and 5,000 km / six-month maintenance reminders with explicit initialization gates.
- Upgrades trip sync to protocol 3: the gauge retains a fixed 64-record rolling history while each phone resumes from its own durable local cursor, so another phone's ACK no longer deletes records the original phone still needs.

### 2026-09-24 — App 3.2.32 Huawei BLE callback 108 recovery

- Treats the non-standard asynchronous BLE scan callback error `108` as a recoverable Huawei/HarmonyOS vendor-stack failure instead of leaving the wake scan on the same failing configuration.
- Progressively falls back from exact-address controller matching to the gauge's advertised `0x1FFA` service UUID and finally the universally supported `ALL_MATCHES` callback, automatically stopping and re-registering the system-owned PendingIntent scan.
- Verifies every UUID-filtered scan result against the saved gauge MAC before starting the foreground connection service, so the compatibility path cannot connect to another device advertising the same service.

### 2026-09-24 — App 3.2.31 screen-off wake recovery

- Makes a verified Companion Device Manager association the primary Android 12+ wake path. A saved BLE address is no longer misreported as a system association; the connection page identifies a missing association and offers an exact-address repair flow.
- Uses the advertised `0x1FFA` service UUID for companion discovery, consumes `EXTRA_ASSOCIATION` on vendor implementations, and supports Android 16's association-id observation and `DevicePresenceEvent` callback while retaining the Android 12-15 service.
- A fresh system BLE appearance signal now replaces a stale vendor `autoConnect` GATT request with an immediate direct connection instead of being ignored merely because a dormant GATT object still exists.
- Holds a bounded CPU wake lock only across the signal-triggered connection handshake, then hands over to the existing connected-session wake lock and releases both on failure or disconnect.
- Adds an opt-in exact one-shot recovery alarm for Android 12+ when the operating system rejects the direct background foreground-service start; the regular 15-minute watchdog remains inexact and low-power.

### 2026-09-24 — App 3.2.30 model-specific final plate calibration

- Applies the final manually calibrated four-corner plate planes independently for ZD8 and ZC6, correcting the home vehicle plate's position, scale and perspective relationship.
- Retains the two-stage runtime pipeline: generate one complete GA 36-2018 flat plate bitmap first, then perspective-install that bitmap on the selected vehicle's front bumper.

### 2026-09-23 — App 3.2.29 corrected plate-to-vehicle scale

- Reduces both model-specific plate planes by roughly 12-15% after comparing plate width against the visible grille width in registered-car references; the plate now occupies about 35% instead of roughly 41%.
- Retains the corrected approximately 2.6:1 projected shape, emblem centreline alignment, grille-edge height and near/far perspective relationship.

### 2026-09-23 — App 3.2.28 corrected projected plate proportions

- Fixes the previous calibration guard, which kept the three-quarter projection too close to the flat plate's 3.14:1 ratio and therefore made the plate look vertically compressed.
- Accounts for the home artwork's roughly 30-35 degree bumper yaw: both ZD8 and ZC6 now render near 2.5-2.7:1, while retaining the calibrated centre, grille-edge placement and shorter/higher far edge.

### 2026-09-23 — App 3.2.27 second ZD8 plate-plane calibration

- Cross-checks official model material and multiple front/three-quarter photos of road-registered ZD8 cars, then moves the plate upward to straddle the grille's upper edge and narrows it to roughly one quarter of the visible front fascia.
- Keeps the plate centred along the emblem/grille centreline while preserving the shorter, higher far edge required by the home artwork's three-quarter perspective; the separately calibrated ZC6 plane is unchanged.

### 2026-09-23 — App 3.2.26 corrected home-vehicle plate projection

- Recalibrates the ZD8 and ZC6 plate quadrilaterals from real front and three-quarter vehicle references, correcting the previously over-wide and vertically compressed projection.
- Aligns the plate with the bumper centreline and grille plane, preserves the real 440 x 140 mm plate's projected proportions and near/far edge relationship, and adds a narrow dark mounting carrier so the plate no longer appears to float.

### 2026-09-23 — App 3.2.25 update progress and consolidated vehicle settings

- Shows a visible percentage progress bar while Android downloads an App update from GitHub Releases.
- Adds a second-level `Vehicle settings` page under `My vehicle` and moves the home vehicle name, ZD8/ZC6 model selector, and automatic refuel-detection threshold into it without changing their storage or gauge-sync behavior.
- Updates the `Since refuel` explanation to display the current gauge threshold and removes the obsolete fixed 5 L / nominal 50 L wording.

### 2026-09-23 — App 3.2.24 installed custom plate on the home vehicle

- Renames the settings `Vehicle display` card to `My vehicle`, moves the plate tool into it, and renames the feature `Custom plate`.
- Requires a complete seven-character conventional-car plate; incomplete input now reports an error without producing artwork.
- Adds an in-feature visibility switch and perspective-installs the generated flat plate artwork on model-specific front-bumper mounting planes for both ZD8 and ZC6 home vehicles.

### 2026-09-23 — App 3.2.23 GA 36-2018 plate-glyph correction

- Replaces the Android condensed system font in the small conventional-car plate preview with bundled, MIT-licensed Chinese number-plate glyph templates.
- Draws each glyph in the 45 mm x 90 mm slots and at the fixed positions from GA 36-2018 Figure 1; the templates remain an on-screen reconstruction, not official anti-counterfeit production dies.

### 2026-09-21 — App 3.2.20 visible trip-revision save action

- Moves the historical trip data editor's Cancel and Save actions into a fixed footer while only the fields scroll, so long forms cannot push the system dialog buttons off-screen on compact or vendor-customized Android layouts.

### 2026-09-21 — App 3.2.19 / Firmware 3.2.14 refuel-history trimming and legacy trip revision

- Adds an explicit `Delete data before first node` action that discards only the oldest completed since-refuel interval, without merging it into later or live statistics.
- Persists the change on the gauge, advertises support through a settings capability bit, and confirms the history revision before removing the phone's matching local interval.
- Allows legacy trip records with unavailable speed/RPM/acceleration extrema to keep those fields blank during data revision, and computes average consumption when that field is left blank.

### 2026-09-21 — App 3.2.18 custom-trip names and history

- Adds optional phone-only custom-trip names with maintenance, wash and long-trip presets or an eight-character custom value; an untouched reset keeps the plain `Custom Trip` title.
- Archives the previous custom interval and its existing name before every reset, then applies the selected name to the new interval.
- Adds a custom-trip detail/history page without changing the gauge protocol, display or firmware.

### 2026-09-21 — App 3.2.17 / Firmware 3.2.13 vertical trip subpages

- Places `TRIP OVERVIEW` and `TRIP INTERVALS` in one horizontal carousel position, with Overview as the default view.
- Swiping up from Overview opens Intervals and swiping down returns; horizontal navigation from either view goes directly to Fuel or Trip History.
- Entering the trip position from either neighboring page always returns to Overview, so Intervals no longer behaves as a separate carousel page.

### 2026-09-21 — App 3.2.16 / Firmware 3.2.12 configurable refuel threshold

- Restores the automatic refuel-detection default from 5 L to 10 L and migrates existing gauges to that value once.
- Adds a 5–20 L, 1 L-step setting in the App, queued behind normal synchronization and verified by reading the value back from the gauge.
- Keeps the existing two-independent-sample debounce, persists the selected threshold on the gauge, and disables the control safely when connected to older firmware.

### 2026-09-21 — App 3.2.15 / Firmware 3.2.11 RPM-zero checkpoint

- Adds an immediate checkpoint when a valid RPM sample transitions from above zero to zero.
- Coalesces simultaneous RPM-zero, speed-zero and one-minute triggers into one NVS save, and restarts the periodic interval only after a successful edge save.
- Ignores an initial or invalid zero-RPM sample so boot and stale OBD data do not create unnecessary writes.

### 2026-09-21 — App 3.2.14 driving notification

- Reuses the existing vehicle snapshot to show `Driving HH:MM    X.X km travelled` in the foreground-service notification while the engine is running.
- Restores connection status when driving stops or the link closes, and preserves higher-priority OTA progress messages without adding BLE reads or gauge work.

### 2026-09-20 — App 3.2.13 background trip refresh

- Keeps the CPU awake only while the gauge GATT link is connected, so the 30-second background vehicle/trip polling continues after the screen turns off.
- Releases the non-reference-counted partial wake lock immediately on disconnect, OTA Bluetooth handoff or service shutdown; disconnected standby behavior is unchanged.

### 2026-09-20 — App 3.2.12 / Firmware 3.2.10 trip intervals page

- Adds a swipe-accessible `TRIP INTERVALS` gauge page after `TRIP OVERVIEW`, showing distance, driving time and fuel for both since-refuel and custom-trip intervals.
- Persists the custom-trip baseline on the gauge and synchronizes an existing or newly reset App baseline through an optional 20-byte BLE command, while remaining compatible with older firmware.
- Retries a failed custom-baseline NVS write at the next regular checkpoint so a transient flash error cannot silently discard the reset.

### 2026-09-20 — App 3.2.11 independent trip start/end revision

- Makes time revision available for every historical trip, including records whose original gauge time was valid.
- Provides independent date/time controls for both start and end, with chronological and driving-duration validation.
- Preserves both revised boundaries across later gauge synchronization and unrelated test-data revisions instead of recalculating end from start plus driving duration.

### 2026-09-20 — App 3.2.10 / Firmware 3.2.9 trip finalization timing

- Preserves real monotonic elapsed time for the 15-minute engine-off finalizer even across a long statistics-task or OTA scheduling gap; fuel and mileage integration still reject stale intervals.
- Re-anchors the archived trip end to the last engine-running sample, and preserves the persisted boundary across a quick gauge reboot with no new engine samples, so the detail page shows ignition-off time instead of sync or finalization time.
- Pulls trip history immediately when the live vehicle snapshot changes from an active trip to empty, removing the regular history cycle's delay.

### 2026-09-20 — App 3.2.9 / Firmware 3.2.8 persistent OBD status

- Keeps the gauge `OBD` indicator visible on data pages: green while OBD is connected and red while disconnected.
- Exposes deletion of the newest since-refuel history node without requiring revision mode; older-node deletion remains available in revision mode and preserves the existing merge-and-sync behavior.

### 2026-09-19 — Firmware 3.2.7 three-state time indicator

- Shows `TIME` in red until the gauge receives valid phone time during the current boot.
- After synchronization, shows `TIME` in green while the phone is connected and yellow after it disconnects.

### 2026-09-18 — Firmware 3.2.6 phone-link color and OTA finalization

- Shows `TIME` in red whenever the phone time-service link is disconnected and green while it is connected; the former yellow disconnected state is removed.
- Reduces the OTA HTTP staging and socket receive buffers from 256 KiB to 16 KiB and 32 KiB respectively, preserving internal RAM for Wi-Fi and flash finalization.
- Returns the final firmware-upload HTTP response before a separate installation task waits five seconds, stops HTTP/Wi-Fi, writes the inactive OTA slot, verifies its SHA-256 readback and switches the boot partition.

### 2026-09-18 — App 3.2.8 signing correction / Firmware 3.2.5

- Restores the established Android signing certificate so 3.2.8 can update 1.0.0–3.2.7 in place without removing local App data.
- Pins debug builds to the project-specific keystore and fails the build if it is missing, preventing silent per-user certificate changes.

### 2026-09-18 — App 3.2.7 / Firmware 3.2.4 refuel-node merge

- Adds deletion of an individual reset node in since-refuel revision mode, with a destructive-action confirmation.
- Merges a deleted middle node into the following completed interval, or a deleted newest node into the live interval.
- Persists a gauge-side history revision so the App reconciles deletion instead of restoring the node during synchronization.
- Lowers automatic refuel detection from 10 L to 5 L while retaining confirmation by two independent fuel-level samples.

### 2026-09-18 — App 3.2.6 / Firmware 3.2.3 hides the gauge OTA entry

- Removes the `OTA Mode` button from the gauge information page so OTA cannot be entered accidentally from the gauge UI.
- Keeps the phone's explicit parked-confirmation OTA path and all OTA recovery behavior unchanged.
- Does not change OBD polling, trip statistics, or normal data synchronization.

### 2026-09-18 — App 3.2.5 fuel update timestamp

- Shows the actual sample time beside `Updated` when a new gauge fuel-level sample has arrived.
- Shows `Not updated · Last update` with that same most recent sample time once no newer fuel sample is available.

### 2026-09-18 — App 3.2.4 / Firmware 3.2.2 trip and refuel intervals

- Makes the live current-trip card open the same detail presentation as completed trips, without redundant tap hints.
- Marks fuel as updated only when a new gauge fuel-level sample sequence arrives.
- Adds optional since-refuel and local custom-trip home cards, with revision and manual reset controls for the independent since-refuel history.
- Detects a nominal 10 L or larger fuel increase on the gauge using two samples, stores it in a separate NVS blob, and synchronizes it through optional low-priority GATT reads without changing OBD polling.
- Allows incomplete manual fuel entries to be saved as drafts and excludes them from every fuel statistic.

### 2026-09-17 — App 3.2.3 / Firmware 3.2.1 persistent phone-link indicator

- Changes `TIME` to the live phone time-service link: green while the phone App is connected and yellow while disconnected.
- Keeps the existing red-on-fault `OBD` behavior and the exact former `NO SIGNAL` footprint.
- Shows the fixed indicators on Trip History and Trip Overview without moving either page's content.
- Reuses the existing atomic phone GATT state; adds no BLE request, PID poll, task, lock, or flash write.

### 2026-09-17 — App 3.2.2 boot and gauge-signal cold-start recovery

- Restores wake registrations on locked boot, first unlock, normal boot, package replacement, Bluetooth enable, and Huawei quick boot broadcasts.
- Re-arms the one-shot BLE wake scan and schedules a short watchdog retry when a vendor system rejects foreground-service startup from a gauge signal.
- Records broadcast receipt, actual foreground-service startup, and rejection class separately for actionable diagnostics.
- Adds an in-app wake-registration repair action while preserving the existing Huawei/HarmonyOS startup-management shortcut.

### 2026-09-17 — App 3.2.1 resilient OTA hotspot reconnect

- Keeps Wi-Fi in high-performance mode and holds a bounded CPU wake lock for the manual OTA session.
- Treats Android `Network` loss as terminal for that network object, reconnects to the gauge hotspot, queries the authenticated receive offset, and resumes on a fresh connection.
- Cancels blocked HTTP I/O immediately on link loss and only gives up after repeated reconnects without transfer progress.
- Leaves the normal BLE/OBD acquisition and synchronization paths unchanged.

### 2026-09-17 — Firmware/App 3.2.0 OBD and clock indicators

- Replaced the former `NO SIGNAL` overlay with fixed `OBD` and `TIME` red warnings in the same footprint. Each warning disappears independently once its condition is healthy.
- `OBD` reuses the existing ELM/master-link state. `TIME` reads a lock-free, in-memory “phone synchronized this boot” flag. The change adds no PID request, BLE transaction, acquisition task, lock contention or flash write and does not alter any page layout.

### 2026-09-17 — App 3.1.8 signal-to-clock wake path

- The system-owned exact-address BLE scan now uses balanced/aggressive first-match detection and tracks match loss, allowing a later gauge power-on to trigger a new appearance callback sooner.
- A gauge appearance signal now starts a direct connection to the bound address instead of launching a redundant second scan. Time synchronization remains the first GATT operation after service discovery, ahead of MTU, trip, vehicle or settings traffic.
- Companion-device presence uses the same fast path. Android force-stop and vendor-disabled background activity remain unavoidable platform limits.

### 2026-09-17 — App 3.1.7 selectable, data-safe firmware rollback

- Added a historical-firmware picker under the gauge update card, initially offering firmware 2.6.0. Merely connecting, scanning or selecting a version never enters OTA; rollback still requires a separate parked/manual confirmation.
- The bundled 2.6.0 rollback image retains its period UI and vehicle behaviour while backporting current NVS/trip-detail/odometer compatibility plus OTA recovery, so current data is not interpreted by the obsolete storage schema and the gauge can later update through the App again.
- Rollback keeps the existing hardware/partition identity checks, full image SHA-256 validation, inactive-slot write/readback and mandatory reboot out of OTA mode.

### 2026-09-17 — Firmware/App 3.1.6 rounded gauge presentation

- The device-information odometer now shows completed whole kilometres only, for example `5488 km`.
- Every other gauge-side precision reduction now rounds to nearest instead of truncating: live/average fuel economy, trip and lifetime distance, lifetime fuel, displayed trip minutes, battery voltage, AFR and whole-degree brake temperature. Stored statistics and BLE payloads retain their original precision.
- The firmware is embedded in the Android package for the existing explicit, manual App OTA flow; no direct USB flash is required.

### 2026-09-17 — Firmware/App 3.1.5 odometer above OTA Mode

- Moved the synchronized odometer to the device-information page, immediately above its `OTA Mode` button. The telemetry information page and fuel page contain no odometer row.
- The gauge renders only the value and unit, for example `5488.6 km`, with no ODO, estimate or calibration annotation. The Android display switch remains authoritative and data collection is unchanged.

### 2026-09-17 — Firmware/App 3.1.4 odometer on information page

- Moved the synchronized/calibrated odometer from the fuel page to a dedicated, fully visible row at the bottom of the information page.
- The row reads the gauge's persisted odometer snapshot instead of waiting for optional factory PID 01 A6 support. The Android display switch still hides it on both the gauge information page and app home; OBD polling, trip statistics and flash-write frequency are unchanged.

### 2026-09-17 — Firmware/App 3.1.3 visible home-page odometer

- The odometer at the bottom of the fuel home page now uses a larger, high-contrast label and is explicitly kept above optional bezel artwork. The former 16 px dark-grey style was effectively invisible at normal in-car AMOLED brightness.
- The synchronized phone switch still hides the odometer on both home pages. This is a presentation-only change and adds no OBD request, statistics task or periodic flash write.

### 2026-09-17 — Firmware/App 3.1.2 reliable BLE-to-WiFi OTA hand-off

- After acknowledging the explicit manual OTA command, the gauge now stops its BLE/OBD and ESP-NOW radio work, releases the Bluetooth controller and internal memory, and only then starts the OTA SoftAP. This matches the already reliable on-gauge OTA-screen path.
- The expected BLE disconnect is the transport hand-off: Android scans for `OBD-Gauge-OTA-*`, retrieves the random token from `/ota/discover`, then repeats device compatibility and image-integrity checks before upload.
- OTA entry remains manual and non-persistent. Startup failure, transfer error, disconnect or timeout reboots into normal acquisition; NVS settings, statistics, pending trips and bootmedia are not erased.

### 2026-09-16 — Firmware/App 3.1.1 resilient OTA handshake

- Starting the OTA SoftAP no longer runs inside the firmware GATT callback. A one-shot task starts it after the acknowledged write has returned, and repeated start commands are harmlessly ignored.
- If an older firmware accepted the OTA command but BLE coexistence hid the Android write callback or disconnected the link, the app now searches for the already-running `OBD-Gauge-OTA-*` hotspot and retrieves the per-session token from `/ota/discover` instead of aborting the update.
- Recovery still verifies the full device manifest and embedded firmware SHA-256 before any inactive OTA partition is selected. If no hotspot exists, the attempt times out safely and normal BLE/OBD operation resumes.

### 2026-09-16 — Firmware/App 3.1.0 synchronized odometer controls

- The fuel/start page can show an estimated odometer. Android's mileage display switch now hides or shows the value on both the app home and gauge, and phone calibration is copied to the gauge through a dedicated CRC-protected characteristic.
- Odometer commands wait for the normal GATT synchronization chain to become idle. The BLE callback only validates and queues a fixed-size event; the UI task performs the rare user-triggered NVS write, without adding OBD requests, tasks or periodic flash writes.
- Trip details now include a test-only revision action for distance, duration, fuel and driving extrema. Revisions stay in the phone database, survive retransmission of the same trip, and are never written back to the gauge.

### 2026-09-16 — Firmware/App 3.0.0 trip details

- Each completed trip now retains maximum speed, maximum RPM, peak acceleration and peak deceleration. Average speed is derived from recorded distance and engine-running duration.
- The Android trip list opens a dedicated detail page with overview, speed/RPM, longitudinal acceleration, fuel and timestamp sections. Existing phone history is migrated in place; values that old firmware never recorded remain clearly unavailable.
- The gauge reuses the existing 200 ms statistics pass. Hot-path work is limited to integer comparisons and an integer acceleration calculation only when the whole-km/h OBD speed changes; it adds no OBD request, task, heap allocation or flash-write interval.
- Trip BLE protocol v2 extends records from 40 to 48 bytes while the app continues to accept v1 records. Existing NVS trip/history fields keep their original layout and the new detail fields are appended, preserving unfinished, retained and queued trips during upgrade.

### 2026-09-16 — Android 2.9.4 reliable scroll restoration

- Long pages restore their saved position only after Android has measured the rebuilt content.
- Rapid firmware-scan status broadcasts can no longer overwrite the saved position with the temporary zero position of a not-yet-laid-out replacement page.

### 2026-09-16 — Android 2.9.3 direct manifest parsing and stable scrolling

- The gauge manifest now reads its fixed fields directly. Firmware version comes from `firmware.version`; hardware safety fields come from `device`, with no regular-expression parsing.
- Rebuilding a page after a button action or BLE status update now restores that page's previous scroll position instead of jumping to the top.

### 2026-09-16 — Android 2.9.2 firmware scan hardening

- Firmware-manifest parsing, local cache writes and update assessment are isolated from the Bluetooth callback; a phone-side preference failure can no longer turn a valid manifest into a GATT failure.
- Firmware status strings left by incompatible development builds are migrated safely during an in-place upgrade.
- A scan failure remains local to the update card and does not disconnect the gauge or interrupt clock, trip, vehicle or settings traffic.

### 2026-09-16 — Android 2.9.1 isolated firmware scanning

- Firmware checks reuse identity data already read on the current GATT connection instead of adding avoidable traffic.
- A missing manifest, a busy vendor Bluetooth stack, malformed data, or a scan timeout now ends only the optional check. It never closes a healthy phone link and never replays the scan after reconnecting.
- Normal clock, trip, vehicle and settings synchronization resumes on the same connection; gauge OBD collection and trip/fuel statistics are unchanged.

### 2026-09-16 — Firmware/App 2.9.0 engine-off trip finalization

- An active trip now completes during the same power session after 15 continuous minutes without a running-engine RPM sample; gauge power loss is no longer required.
- Restarting the engine inside the 15-minute window cancels the pending split and continues the same trip. Completion immediately checkpoints both history and the phone-sync queue.
- The existing pending-trip boot recovery remains in place for unexpected power loss before the engine-off deadline.

### 2026-09-16 — Android 2.8.1 retained fuel and compact mileage

- A snapshot without a valid fuel level now keeps the last valid gauge reading per paired device instead of blanking fuel, range and the progress bar.
- Home mileage is reduced to `5000km`; only an uncalibrated estimate adds `· 未校准`.

### 2026-09-16 — Firmware/App 2.8.0 manual, resumable OTA

- Merely connecting or scanning never enters OTA. The gauge pauses OBD acquisition only after the user explicitly confirms **Enter OTA and update** in the app.
- Wi-Fi upload chunks are reduced to 64 KiB and retry from the byte offset confirmed by the gauge. The complete image is SHA-256 checked before flashing and read back from the inactive OTA slot before its boot partition is selected.
- Success, cancellation, transfer errors and inactivity all end in a reboot to normal mode. OTA state is never persisted, so a power cycle after an unexpected phone disconnect cannot strand the gauge outside normal data acquisition.

### 2026-09-16 — Android 2.7.1 update recovery and mileage calibration

- Firmware scans now have a 30-second deadline and recover an interrupted `checking` state after either the service or app process restarts, so a failed scan cannot permanently disable the controls.
- Scan and installation actions both show a parked-vehicle confirmation because OBD acquisition and trip/statistics recording may pause during either operation.
- The home page estimates odometer mileage from saved trips. Connection & Settings can calibrate it to the vehicle's current reading; subsequent new trips are added locally without writing gauge data.

### 2026-09-16 — Firmware/App 2.7.0 embedded safe OTA

- Android now embeds the matching AMOLED 1.75-B application image and exposes update scanning under Connection & Settings.
- Updates require an exact BLE manifest match, local image size/SHA-256 validation, and a second identity check over the gauge's temporary Wi-Fi network.
- Uploads wait for normal synchronization to become idle and target only the inactive OTA application slot. NVS, trip/statistics data and boot media are not uploaded or erased; failed boots use the existing rollback path.

### 2026-09-15 — Android 2.6.2 post-time-sync crash isolation

- Low-fuel notification failures on vendor Android builds no longer terminate the BLE service.
- Vehicle/settings/profile read processing is isolated so a post-clock runtime exception restarts only the connection.
- Gauge-driven ZD8/ZC6 home changes are posted outside the status broadcast and renderer failures are contained. Gauge firmware remains 2.6.0.

### 2026-09-15 — Android 2.6.1 connection crash hardening

- Vehicle hero bitmaps are retained for the Activity lifetime instead of manually recycling the previous image during a gauge-driven ZD8/ZC6 page rebuild.
- Vendor failures while registering background scans/receivers or publishing foreground-service notifications/status broadcasts are isolated from the GATT data path.
- Cached vehicle/settings snapshots now tolerate incompatible SharedPreferences value types left by development builds. Gauge firmware remains 2.6.0.

### 2026-09-15 — Firmware/App 2.6.0: ZC6 and ZD8 only

- The gauge vehicle roller now contains only `ZC6` and `ZD8`; phone selection uses the same two stable profile IDs.
- ZC6 now follows the same single serial OBD/PID architecture as the supported ZD8 profile instead of entering ATMA/CAN monitoring. Its FA20-specific Toyota Mode 21 oil-temperature request remains enabled.
- Legacy profiles remain in source but are blocked at the UI, NVS normalization, runtime setter, and BLE command boundaries. A stored legacy selection migrates to ZD8.

### 2026-09-15 — Android 2.5.1 ZC6 artwork and startup stability

- Replaced the opaque ZC6 hero bitmap with a real-alpha cutout, matching the ZD8 card integration without an extra rectangular backdrop.
- Vehicle artwork is decoded at the card's practical resolution to reduce the home-page memory peak.
- Vehicle-profile preferences migrate string values left by intermediate builds instead of throwing `ClassCastException`; receiver lifecycle calls are guarded for vendor Android stacks. Gauge firmware compatibility remains 2.5.0.

### 2026-09-15 — Android/gauge ZD8 and ZC6 vehicle selection

- Android 2.5.0 exposes only the supported BRZ ZD8 6MT and ZC6 6MT profiles. The home subtitle and generation-specific 3D vehicle artwork follow the phone selection immediately.
- Added the dedicated write-only `0x0009` characteristic. Its six-byte versioned and CRC-protected command is queued only after normal synchronization is idle, then confirmed through the existing `0x0007` settings snapshot. Offline choices remain pending until the gauge reconnects.
- The gauge BLE callback only validates and queues the profile. Profile cache reset and NVS persistence run in the UI task, so setting traffic does not block OBD transport or trip/fuel statistics.
- ZD8 remains the road-tested configuration. The existing first-generation BRZ/GT86 CAN profile is now labeled as ZC6 in the app but still requires road validation in this project.

### 2026-09-15 — Firmware 2.4.0 identity, fuel startup page and clearer locks

- MultiGauge remains frozen at `MASTER / 1 / VIDEO`; disabled roller bodies and selected rows now use an explicit grey palette.
- NVS configuration v10 migrates the startup page to FUEL once. The normal boot-page selector remains available for later deliberate changes.
- Device Info now shows `FW: 2.4.0`; builds without readable Git history use the honest `BUILD: local` label instead of `unknown-0-unknown`.
- Android 2.4.0 reads the existing read-only device manifest only after normal synchronization is idle and displays the instrument firmware version. This optional metadata read never forces a healthy data connection to reconnect.

### 2026-09-15 — Local vehicle name and frozen MultiGauge configuration

- Android 2.3.9 adds a local home-page vehicle name. It defaults to “我的BRZ STI”, removes line breaks, and is limited to nine characters so the large heading stays on one line.
- The on-gauge MultiGauge page remains visible but all three rollers are disabled and have no value-change callbacks. Runtime and persistence are normalized to `MASTER / 1 / VIDEO`, including attempts through existing internal setters.

### 2026-09-15 — Isolated phone brightness control

- Android 2.3.8 enables only the 10–100% display-brightness field; every other gauge setting remains disabled.
- Added the dedicated write-only `0x0008` characteristic with a six-byte versioned, length-tagged, CRC-protected command. Android submits once on Apply, waits for the normal GATT chain to become idle, and verifies the result through the read-only `0x0007` snapshot.
- The gauge GATT callback only validates and non-blockingly queues the request. Display/NVS work runs through the existing UI event consumer; brightness failures never force a healthy vehicle/trip connection to reconnect, and no trip or fuel statistic is modified.

### 2026-09-14 — Read-only gauge settings form and synchronization

- Added read-only characteristic `0x0007`: the gauge copies its already-loaded RAM settings into one CRC-protected snapshot without NVS access or runtime-state changes.
- Android always presents every known parameter under Connection & Settings → Gauge Settings as familiar setting controls. Before synchronization they show a waiting placeholder; after connection they display the gauge's actual values.
- Every control is disabled. This revision intentionally has no settings write characteristic, change handler, save/apply action, or Android command that can modify gauge settings.

### 2026-09-14 — Android low-fuel warning and system-bar fix

- Fixed the Android 8.1+ theme override so it inherits the light app theme; system-bar colors, icon appearance, navigation divider and contrast enforcement now match the page instead of leaving a black strip.
- Below 25% effective fuel, the estimated-range value turns yellow and the phone emits one low-fuel notification per low-fuel event. The reminder is armed again only after fuel returns to 25% or above.
- Added the requested estimated-range disclaimer to the bottom of the range card.

### 2026-09-14 — Trip gap temporarily locked to 15 minutes

- The existing TRIP GAP row remains visible but is disabled and fixed at 15 MIN. Configuration loading and saving both normalize the persisted value to 15 minutes, while the field remains in place for a later unlock.

### 2026-09-13 — Process-independent Android gauge wake-up

- The Android companion registers an exact-address `PendingIntent` BLE scan so gauge advertising can wake the connection service after the ordinary app process has been reclaimed.
- Boot, unlock, Bluetooth-on, package replacement, binding and watchdog paths refresh the system scan registration; companion-device presence and the foreground service remain as independent fallbacks.
- Connection settings now show the system BLE wake registration and the latest gauge-triggered wake event. Android force-stop remains intentionally outside the recovery contract.

### 2026-09-12 — Robust short power-cycle trip merge

- Trip-gap decisions now compare the previous trip end with the reconstructed start of the next powered session, so a delayed phone connection no longer causes a false split.
- An unresolved pending trip survives additional USB/UART resets instead of being finalized on the next boot. The change adds no task, allocation, BLE transaction or flash-write cycle.

### 2026-09-04 — Status-assisted fuel estimation

- Added PID 01 03 support detection/polling and fuel-input freshness/reset handling. Status 4 requires low throttle/load and sufficient RPM/speed before an estimated cutoff; cold, closed-loop, fault and missing status cannot force zero.
- Preserve ECU 01 5E priority (including zero), otherwise use MAF/commanded AFR with the existing gasoline density assumption. The fuel page identifies ECU, MAF, estimated cutoff, engine-off and unavailable data.
- Use measured monotonic intervals and fractional carry for fuel/distance/time, reject long gaps and stale inputs, and preserve the 60-second/stop-edge save policy and persisted trip format.
- Simulator shares the production estimator. Host tests cover cutoff/enrichment, invalid inputs, ECU priority, elapsed-time integration and cache freshness. Road validation is still required.

### 2026-09-04 — Fix blank inferred gear with serial ELM327 responses

- Run pairing on both RPM and speed arrival, replacing the speed-only 180 ms gate with a bounded 750 ms window. Each response is consumed at most once.
- Keep inferred gear for up to 3 seconds between valid pairs; reject fresh ratio mismatches, expire old candidates and clear pairing state on reconnect/profile change.
- Added host tests executing the actual firmware cache across six gears, response ordering/delays, shifts, stale data and direct-signal fallback. Not yet road-validated.

### ZD8 6MT responsive gear inference

- Footwell-OBD mode no longer enters ATMA for gateway-blocked CAN `0x241`, eliminating periodic RPM/speed stalls.
- Speed polling increases from one to four samples per OBD round while retaining high-priority interleaved RPM queries.
- Gear inference now uses near-synchronous raw speed/RPM samples, tighter tolerances and two-sample confirmation; uncertain clutch/shift states display `--`.
- Optional PID `01 A4` must return two consecutive closely matching ratios before it can override inference.

### Resumable phone trip archive

- Completed fuel trips now retain start/end Unix timestamps and enter a 64-record BLE queue until acknowledged by the phone.
- Device Info service `0x1FFA` adds trip metadata, cursor/control and CRC-protected record characteristics; a future-ID ACK is rejected to protect the persisted cursor.
- Added an Android companion source project that auto-syncs phone time, stores records in SQLite before ACK, resumes after disconnects, and displays date, start/end time, duration, distance and consumption.

### On-device OTA: BLE service + WiFi transfer

- A new **OTA Mode** screen (reached from the device-info page) turns the gauge into an update target: it publishes the OTA BLE service (`0x1FFB`) and starts a WiFi SoftAP (`OBD-Gauge-OTA-XXXX`) whose HTTP endpoint accepts SHA256-verified firmware and boot-animation uploads.
- Firmware is written to the inactive OTA slot and marked valid only after a 15 s post-boot self-check, so a crash during early boot rolls back to the previous build automatically.
- Boot animation now updates transactionally: the incoming `boot_block` pair is staged, committed, and the previous animation recovered if an update is interrupted. RS485 and ESP-NOW are paused during the transfer to free the CPU.
- The device-info page now shows the firmware build tag instead of the ESP-IDF/LVGL version numbers.

### RaceChrono toggle and boot-animation modes

- New **RACECHRONO** toggle in Settings: off leaves the device in a minimal BLE mode (Info + OTA services only, no advertising), on restores the full RaceChrono + pairing + OTA service set.
- Boot-animation modes simplified to **OFF / RACE / VIDEO**; VIDEO plays the `boot_block` flashed from the phone app (replacing the old REI/SHINJI/ASUKA slots).

### UI consistency fixes

- A white ring border and rounded roller/slider corners are now applied consistently across the config pages.
- Removed the redundant titles on the INFO CUSTOM / TEMP CUSTOM pages and fixed the settings-page title overlapping the notch image.

### OBD data fixes

- The ELM327 client now runs single-threaded, ZC6 CAN debug logging was removed, and brake/oil warning alerts are throttled.
- ZD8 gear selection now prefers fresh CAN `0x241`, then optional standard PID `01 A4`; unsupported, invalid, or stale direct data automatically falls back to RPM/speed ratio inference.
- Fixed the fallback ratio constant, which previously multiplied the final-drive ratio twice and could prevent a real vehicle from matching any gear.

### OTA-ready layout and device manifest

- Partition layout now uses `ota_0` + `ota_1` + `bootmedia`, so the firmware can roll back after a bad update.
- The bootmedia partition is back at `0x620000` in the current layout.
- A read-only BLE device-manifest service (`0x1FFA`) now exposes board/build info for the companion app's hardware compatibility check.
- Build metadata now includes git branch, commit count, and short hash so release manifests can compare versions by build number.

### Themes are now data, not code

Adding a UI theme no longer touches a single C file. A theme is a folder under `themes/` holding a `theme.toml` manifest — eight decorative colors plus optional artwork — and one appended line in `themes/registry.txt`. `tools/gen_themes.py` runs at CMake configure time: it validates every manifest, converts PNG artwork into LVGL image arrays, and emits `ui_theme_generated.c`.

- **Artwork.** `ring` (360x360, alpha) replaces the drawn bezel, `needle` replaces the drawn meter needle (LVGL rotates it around a pivot declared in the manifest; art must point right), and `dial` (360x360) becomes the page background. Anything omitted falls back to the drawn shape and its color role, so a colour-only theme is still a single file.
- **Slot stability.** `themes/registry.txt` pins slot -> id and is append-only. NVS stores the slot number, so reordering it would silently re-skin every existing device on the next OTA — invisible in local testing. The generator hard-fails on reordering, gaps, a non-`default` slot 0, or a registry line whose folder is missing.
- **Build-time validation** covers artwork dimensions, needle pivot bounds, missing files, duplicate roller names, unknown keys, and a total artwork budget (1536 KB, since every registered theme's art is linked in unconditionally). Every failure points at a file and line.
- Artwork conversion needs Pillow only when a PNG's SHA-256 changes; the generated C is checked in, so an ordinary build has no third-party Python dependency. `--check` verifies freshness for CI.
- **Fixed:** the Settings theme roller built its option list in a fixed 96-byte buffer and silently truncated once enough themes were registered. It now uses an exactly sized buffer, so truncation is structurally impossible.
- **Fixed:** leaving the RPM warning restored a hardcoded black background, which would permanently blank a themed dial face. All three restore paths now reapply the theme background.

Authoring guide: [themes/README.md](themes/README.md) (bilingual). Framework internals: [docs/THEMING.md](docs/THEMING.md).

### Multi-gauge linked RPM warning

Three gauges can now light up in sequence as revs climb, instead of all strobing at once. The 1000 rpm below the warning threshold is split into thirds; each gauge ramps black to red across its own third according to its configured position, and at the threshold all three strobe together. Every unit derives its own segment from the same ESP-NOW-synced RPM, so no extra inter-gauge messaging is needed and they stay in sync naturally.

- New LINKED toggle on the RPM warning page, mutually exclusive with the existing single-gauge flash.
- Changing the threshold on one gauge broadcasts it to the others (new ESP-NOW control packet).
- The test button drives a synthetic RPM ramp across all gauges (5 s rise, 0.8 s hold, 2.5 s fall).
- NVS config version 1 -> 2: adds `rpm_warn_linked_en`, and migrates the default theme index 1 -> 0 so existing devices keep their current look.
- RPM strobe redraw interval was 1 ms; it is now 25 ms.

### Partition layout and boot animation

- App partition shrunk 6 MB -> 4 MB; the bootmedia SPIFFS partition grew 9.8 MB -> 11.9 MB.
- **Flashing change: bootmedia moves from `0x620000` to `0x420000`.** Update your flash command and scripts.
- Boot animation re-encoded on a 300x300 grid (was 240x240) with a new `delta_varint_rgb565_black_v2` stream format.


### Multi-gauge: real BLE pairing replaces the MAC-bind button

The old "BIND MASTER" button worked by grabbing whichever master's ESP-NOW broadcast the slave happened to be receiving at the moment — with no way to pick a specific one when multiple masters are nearby (e.g. a track day with several cars running the same product). The master now advertises a real BLE peripheral (`SkyGauge-XXYY`); the slave discovers and binds to it through the existing BLE scan page (now doubling as a "FIND MASTER" screen when the device role is SLAVE), and reconnects automatically on every following boot.

- New `gauge_pair_ble_client.c/h` (slave-side one-shot BLE pairing client) and `ble_adv_util.c/h` (shared BLE advertisement-name parsing, deduplicated out of the OBD BLE client).
- `racechrono_ble_diy.c` gained an independent pairing GATT service alongside the existing RaceChrono service, sharing one BLE advertisement.
- `ui_ScreenPageMultiGauge.c` no longer has BIND MASTER / UNBIND buttons.
- Boot flow: an unbound slave lands on the pairing screen; a bound slave skips straight to its gauge display.

### Fixed: master watchdog reboot during OBD protocol detection

Root-caused a board reboot seen during testing: the blocking wait for an ELM327 response could sit for up to 3 seconds without feeding the task watchdog. A run of consecutive protocol auto-detect timeouts could add up past the 5 s TWDT window and reboot the board mid-poll. Fixed by resetting the watchdog inside that wait loop.

### Other fixes

- Slave-side BLE scan state could get stuck once its 15 s scan window elapsed, silently blocking retry/rescan.
- Leaving the pairing screen in slave mode stopped the wrong BLE scan API, leaving a scan running in the background.
- I2C device cache could read out of bounds once more than 8 addresses were queried (latent crash, not yet hit in practice).
- LCD init could read an uninitialized register value if the QSPI probe failed.

### Improvements

- "NO SIGNAL" indicator on the gauge pages when BLE/ESP-NOW data goes stale.
- Gear display now prefers the CAN-decoded precise gear over the RPM/speed estimate when a vehicle profile provides one.
- Mileage/trip statistics are runtime-only now (no longer written to flash every 30 s) — nothing displayed them, so it was pure flash wear.
- Removed unused `fsm.h` state-machine scaffolding and an unused OBD-data "dirty flag" tracking layer — neither was ever wired up to anything.
- De-duplicated a shared BLE-advertisement-name parser, a screen ring border, and a dark roller LVGL style across ~18 screens; throttled a full chart redraw and a few gauge pages to only refresh when actually visible or actually changed.

---

## 中文

### 2026-09-26 — App 3.4.0 驾驶足迹视觉与里程口径升级

- 日历深浅不再按当年最大里程相对缩放，改为每日累计里程固定分档：低于 25 km、25～49 km、50～99 km、100 km 及以上；跨月份、跨年份可直接比较，达到 100 km 始终显示最深色。
- 驾驶足迹整体统一为记账页风格：双列指标卡、柔和主题色、胶囊式修订与年份切换、清晰的日历说明及对齐一致的历史记录卡。
- 历史行程的统计强调色由红色改为驾驶/记账共用的蓝色，同时保留整页连续滚动和点击记录进入详情。

### 2026-09-26 — App 3.3.3 驾驶日历修复

- 当前年份的驾驶日历只显示到本周日，不再绘制后续月份的大量空白方格。
- 日历下方新增从无记录、活动较少到活动较多的颜色深浅图例。
- 将驾驶总览、日历和历史行程改为同一个连续纵向滚动列表，修复小屏设备上历史记录区域被挤到零高度、无法进入详情的问题。

### 2026-09-26 — App 3.3.2 记账界面精修

- 将记账页原有的系统风格按钮改为紧凑的胶囊分段导航，同时完整保留加油、保养、日常和统计四个栏目的点击与左右滑动切换。
- 加入按栏目区分的主操作色、双列数据卡、统一空状态和整行可点击记录卡，强化日期、金额与记录状态的视觉层级。
- 统计页新增渐变月度总额卡、带金额和百分比的年度分类构成，以及包含网格、当前月份强调和空数据提示的轻量 12 个月堆叠图。

### 2026-09-25 — App 3.3.1 矢量高清车牌

- 将原先放大的低分辨率 JPG 字符模板替换为 App 内置的 67 个轮廓字形，由 Android 路径直接绘制。
- 省份汉字轮廓按 GA 36-2018 的 45 mm × 90 mm 字符尺寸生成，并补齐此前矢量提取遗漏的矩形笔画。
- 首页透视安装使用的平面车牌由 880 × 280 提升至 1760 × 560 像素，并启用滤波、抖动和 mipmap 准备，减少小尺寸投影后的笔画发虚。
- 保留 880 × 280 平面号牌和分车型四点透视安装流程，在最终投影前消除位图源放大造成的锯齿与模糊。

### 2026-09-25 — App 3.2.33 / 固件 3.2.16 驾驶日历、用车记账与多手机连续同步

- “驾驶足迹”新增类似 Codex 活动图的年度驾驶日历，并按当前车辆汇总行程数、驾驶日、总里程、总时长和燃油。
- 底部“加油”升级为可左右滑动的“记账”，包含加油、保养、日常和统计四栏；新增月度/年度分类可视化及需分别初始化的 5000 km / 6 个月保养提醒。
- 行程同步协议升级为 v3：仪表固定保留最近 64 条汇总记录，每台手机按自己的本地持久游标补齐；另一台手机的 ACK 不再删除原手机尚未取得的记录。

### 2026-09-24 — App 3.2.32 华为 BLE 回调错误 108 恢复

- 将非标准的异步 BLE 扫描回调错误 `108` 识别为可恢复的华为 / HarmonyOS 蓝牙栈异常，不再沿用同一套失败配置等待重试。
- 自动从精确 MAC 控制器匹配逐级降级为仪表持续广播的 `0x1FFA` 服务 UUID 过滤，必要时再使用兼容性最高的 `ALL_MATCHES` 回调，并重新注册系统持有的 PendingIntent 扫描。
- UUID 兼容扫描收到结果后仍会核对已保存的仪表 MAC，只有绑定仪表才能启动前台连接服务，不会误连广播相同服务的其他设备。

### 2026-09-24 — App 3.2.31 息屏与长时间后台唤醒恢复

- 将经过验证的系统伴生设备关联设为 Android 12 以上的主唤醒链路；不再把“仅保存 BLE 地址”误报成系统关联，连接页会识别关联缺失，并可按当前 MAC 地址发起修复。
- 伴生发现改用仪表广播中稳定存在的 `0x1FFA` 服务 UUID，兼容厂商系统通过 `EXTRA_ASSOCIATION` 返回关联；Android 16 使用 association ID 与 `DevicePresenceEvent` 新接口，同时保留 Android 12～15 服务。
- 系统收到新的仪表 BLE 出现信号时，不再因为厂商蓝牙栈遗留的 `autoConnect` GATT 对象非空而忽略广播；会关闭旧请求并立即按绑定地址直连。
- 仅在“广播到达至连接完成”这段握手期间持有限时 CPU 唤醒锁，连接成功后交给原有连接期唤醒锁，失败、断开或服务退出均会释放。
- Android 12 及以上若拒绝从后台直接启动前台服务，可由用户授权一次性的精确恢复闹钟作为兜底；原 15 分钟后台自检仍为低功耗非精确闹钟。

### 2026-09-24 — App 3.2.30 分车型最终校准车牌安装面

- 分别采用本地可视化微调后确认的 ZD8、ZC6 四角坐标，修正首页车辆车牌的位置、大小和透视关系。
- 保持两阶段运行流程：先按 GA 36-2018 版式生成完整平面车牌位图，再将该位图透视安装到所选车型的前保险杠。

### 2026-09-23 — App 3.2.29 修正车牌与车辆大小比例

- 按带牌实车中“车牌宽度 ÷ 可见中网宽度”重新校准，两款车型车牌整体缩小约 12%～15%，由此前约占中网 41% 调整到约 35%。
- 保留约 2.6:1 的正确投影形状、车标中心线对齐、中网上沿高度以及近远边关系。

### 2026-09-23 — App 3.2.28 修正车牌投影比例

- 修复上一版把斜前方投影限制得过于接近平面号牌 3.14:1 原始比例、导致牌面纵向压扁的问题。
- 按车辆底图约 30°～35°的保险杠偏航计算，ZD8、ZC6 投影调整为约 2.5～2.7:1，并保留已校准的中心位置、中网上沿位置及远侧边略短略高的关系。

### 2026-09-23 — App 3.2.27 ZD8 车牌安装平面二次校准

- 结合官方车型资料及多组带牌 ZD8 正面、斜前方实车照片，把首页车牌上移到中网上沿并收窄到可见前脸约四分之一。
- 车牌中心继续对齐车标—中网中心线，远侧边保持略短、略高；已独立匹配较高格栅平面的 ZC6 参数不变。

### 2026-09-23 — App 3.2.26 修正首页车辆车牌投影

- 依据实车正面和斜前方参考照片重新标定 ZD8、ZC6 的车牌四角点，修复此前横向过宽、纵向过度压扁的问题。
- 车牌现在与保险杠中心线和中网平面一致，保持 440 × 140 mm 号牌合理的投影比例及近远边关系，并增加窄幅深色安装座，消除悬浮感。

### 2026-09-23 — App 3.2.25 下载进度与车辆设置归并

- Android 从 GitHub Release 下载 App 更新时显示清晰的实时百分比进度条。
- “我的车辆”新增“车辆设置”二级页面，首页车辆名称、ZD8/ZC6 车型和自动加油识别阈值统一移动到该页，原保存、仪表同步和回读功能不变。
- “上次加油以来”说明改为显示仪表当前阈值，删除已过时的固定 5 L / 50 L 标称油箱描述。

### 2026-09-23 — App 3.2.24 首页车辆安装自定义车牌

- 设置页“车辆显示”更名为“我的车辆”，车牌功能移入该栏并更名为“自定义车牌”。
- 小型燃油汽车号牌必须输入完整 7 位；位数不足时直接报错且不生成图像。
- 自定义车牌页增加首页显示开关；先生成标准平面车牌，再按 ZD8、ZC6 各自前保险杠牌照位进行四点透视变换，使车牌安装在车身正确位置。

### 2026-09-23 — App 3.2.23 GA 36-2018 号牌字形修正

- 小型燃油汽车号牌预览不再使用 Android 系统压缩字体，改为内置 MIT 授权的中国号牌专用字形复刻资源。
- 按 GA 36-2018 图 1 的 45 mm × 90 mm 字符槽位和固定位置逐字绘制；该资源仅供屏幕模拟，并非公安机关防伪专用生产模具。

### 2026-09-21 — App 3.2.20 行程修订保存键可见性修复

- 历史行程数据修订窗口改为仅输入区滚动，“取消”和“保存”固定显示在窗口底部，避免长表单在小屏或厂商定制系统上把系统按钮栏挤出屏幕。

### 2026-09-21 — App 3.2.19 / 固件 3.2.14 加油历史裁剪与旧行程修订修复

- “上次加油以来”新增“删除第一个节点以前的数据”，仅丢弃最早重置点之前的已完成区间，不合并到后续区间或当前累计。
- 仪表持久化此操作，通过设置能力位声明支持，并在历史修订号变化后才删除手机端对应副本。
- 历史行程测试数据修订允许旧记录缺失最高速度、转速和加减速字段；平均油耗留空时按里程与燃油自动计算，避免旧记录无法保存。

### 2026-09-21 — App 3.2.18 自定义行程名称与历史

- 自定义行程增加手机端可选名称，可用保养、洗车、长途预设或最多 8 个字符的自定义输入；直接重置仍显示“自定义行程”。
- 每次重置前保存旧区间及其原名称，选择的新名称用于重置后开始累计的区间。
- 增加自定义行程详情和独立历史页面，不修改仪表协议、仪表显示或固件。

### 2026-09-21 — App 3.2.17 / 固件 3.2.13 行程纵向子页面

- `TRIP OVERVIEW` 与 `TRIP INTERVALS` 合并到横向轮播的同一个位置，默认显示 Overview。
- Overview 上滑进入 Intervals，Intervals 下滑返回；两个视图左右滑动都直接前往油耗页或历史行程页。
- 从任一相邻页面进入行程位置时始终回到 Overview，Intervals 不再作为独立的横向页面。

### 2026-09-21 — App 3.2.16 / 固件 3.2.12 可调加油识别阈值

- 自动加油识别默认阈值从 5 L 恢复为 10 L，已有仪表升级后一次性迁移到 10 L。
- App 仪表设置新增 5～20 L、步进 1 L 的调节项；命令等待正常同步空闲后发送，并回读仪表确认。
- 保留两次独立油位样本确认，选择值持久化在仪表；连接旧固件时该控件安全禁用。

### 2026-09-21 — App 3.2.15 / 固件 3.2.11 转速归零保存

- 有效转速从大于 0 降为 0 时立即增加一次保存。
- 转速归零、车速归零和一分钟定期保存同时发生时合并为一次 NVS 写入，边沿保存成功后重新开始一分钟计时。
- 首次读到 0 转速或转速数据无效时不触发，避免开机和陈旧 OBD 数据产生多余写入。

### 2026-09-21 — App 3.2.14 驾驶状态通知

- 发动机运行时复用既有车辆快照，在常驻服务通知中显示 `驾驶中 HH:MM    已行驶 X.X km`。
- 停止驾驶或断开后恢复连接状态，OTA 期间仍优先显示更新进度；不增加 BLE 读取或仪表负载。

### 2026-09-20 — App 3.2.13 后台行程刷新

- 仅在仪表 GATT 已连接期间保持 CPU 运行，使 App 熄屏后仍能继续每 30 秒读取车辆和行程信息。
- 仪表断开、OTA 蓝牙交接或服务退出时立即释放非引用计数的局部唤醒锁，未连接时的待机行为不变。

### 2026-09-20 — App 3.2.12 / 固件 3.2.10 行程区间页面

- 在 `TRIP OVERVIEW` 后新增可左滑进入的 `TRIP INTERVALS` 仪表页面，同时显示自上次加油和自定义行程的里程、驾驶时间及耗油量。
- 仪表持久化自定义行程基线，App 通过可选的 20 字节 BLE 命令迁移既有基线并同步后续重置；旧固件不支持时不会影响其他同步。
- 自定义基线写入 NVS 偶发失败时，会在下一次常规定期保存中自动重试，避免重置静默丢失。

### 2026-09-20 — App 3.2.11 历史行程起止时间独立修订

- 所有历史行程均可进入时间修订，包括原本已有仪表可靠时间的记录。
- 开始和结束分别提供日期/时间控件，并校验先后顺序与已记录驾驶时长。
- 后续仪表同步或修订其他测试数值时，保留人工修订的开始和结束边界，不再用“开始＋驾驶时长”覆盖结束时间。

### 2026-09-20 — App 3.2.10 / 固件 3.2.9 行程封存时间修正

- 15 分钟熄火封存使用真实单调经过时间；即使统计任务或 OTA 造成长时间调度间隔，也不再把这段时间压缩成约 2 秒，油耗和里程积分仍会拒绝过期样本。
- 封存前再次将结束时间锚定到最后一次发动机运行样本；熄火后仪表快速重启且没有新发动机样本时，也保留重启前的边界。详情页因此显示熄火时间，而不是授时或 15 分钟后的封存时间。
- App 发现实时快照从“有当前行程”切换为“无当前行程”时立即拉取历史，消除常规同步周期的等待。

### 2026-09-20 — App 3.2.9 / 固件 3.2.8 OBD 状态常显

- 仪表数据页的 `OBD` 标识保持显示：OBD 已连接为绿色，断开为红色。
- “上次加油以来”历史最上方的最近节点无需进入修订模式即可删除；更早节点仍在修订模式中删除，并沿用既有的合并和仪表同步逻辑。

### 2026-09-19 — 固件 3.2.7 三态授时指示灯

- 仪表本次启动尚未收到手机有效时间时，`TIME` 显示红色。
- 完成授时后，手机连接时显示绿色，手机断开后显示黄色。

### 2026-09-18 — 固件 3.2.6 手机连接颜色与 OTA 收尾修复

- 手机授时链路断开时 `TIME` 改为红色，连接时保持绿色，移除原先错误的黄色断开状态。
- OTA HTTP 临时接收缓冲由 256 KiB 降至 16 KiB，Socket 接收窗口限制为 32 KiB，为 Wi-Fi 与 Flash 收尾保留内部内存。
- 最后一块固件先完成 HTTP 成功响应，再由独立任务等待五秒、关闭 HTTP/Wi-Fi、写入备用 OTA 分区、回读校验 SHA-256 并切换启动分区，避免热点过早断开让手机误报失败。

### 2026-09-18 — App 3.2.8 签名修复 / 固件 3.2.5

- 恢复既有 Android 签名证书，3.2.8 可直接覆盖 1.0.0～3.2.7，保留手机端本地数据。
- debug 构建固定使用项目专用 keystore；密钥缺失时直接终止构建，避免因 Windows 执行账户不同而静默更换证书。

### 2026-09-18 — App 3.2.7 / 固件 3.2.4 加油节点删除与合并

- “上次加油以来”的修订模式可删除单个重置节点，并有不可撤销的二次确认。
- 删除中间节点时合并到后一个已完成区间；删除最新节点时合并到当前区间。
- 仪表持久化历史修订号，App 据此协调删除结果，防止同步时恢复已删节点。
- 自动加油识别阈值从 10 L 降为 5 L，同时保留两次独立油量样本确认。

### 2026-09-18 — App 3.2.6 / 固件 3.2.3 隐藏仪表 OTA 入口

- 仪表信息页不再创建或显示 `OTA Mode` 按钮，避免从仪表界面误触进入更新模式。
- 手机端需要驻车确认和二次确认的手动 OTA 流程及恢复机制保持不变。
- 不改变 OBD 轮询、行程统计或正常数据同步。

### 2026-09-18 — App 3.2.5 油量更新时间修正

- 仪表送达新油量样本后，在“已更新”旁显示该样本的实际更新时间。
- 尚无更晚样本时显示“未更新 · 上次更新”，并继续显示最近一次油量样本的时间。

### 2026-09-18 — App 3.2.4 / 固件 3.2.2 行程与加油以来统计

- 首页“本次行程”可进入详情，驾驶足迹不再显示多余的“点击查看行程详情”。
- 只有仪表燃油 PID 样本序号变化时才标记油量“已更新”，否则显示上次实际油量更新时间。
- 可分别开启“上次加油以来”和“自定义行程”首页卡片；加油以来历史支持手机端修订和手动重置。
- 仪表连续两次确认按 50 L 标称油箱折算不少于 10 L 的油量增加后，写入独立 NVS 记录，并通过可选的低优先级 GATT 同步；不改变 OBD 查询或普通行程累计。
- 手动加油记录可缺省加油量和花费暂存，暂存记录不参与任何油耗与费用统计。

### 2026-09-17 — 固件/App 3.2.0 OBD 与授时状态指示

- 原 `NO SIGNAL` 顶层提示替换为同一区域内固定位置的红色 `OBD` 和 `TIME`。OBD 信号正常或本次开机已完成手机授时时，对应提示分别隐藏。
- `OBD` 复用已有 ELM/主表链路状态，`TIME` 读取无锁的内存授时标志；不增加 PID、BLE 请求、采集任务、锁竞争或 Flash 写入，也不改变任何页面布局。

### 2026-09-17 — App 3.1.8 仪表信号直接唤醒授时

- 系统托管的精确地址 BLE 扫描改为平衡扫描与积极首次匹配，并跟踪仪表离开状态，使仪表下次上电时能更快产生新的出现回调。
- 手机收到仪表出现信号后直接连接已绑定地址，不再重复扫描；服务发现完成后，授时仍是第一项 GATT 操作，早于 MTU、行程、车辆和设置同步。
- 系统伴生设备出现回调复用同一快速路径。Android 强行停止和厂商禁止后台活动仍是无法绕过的系统限制。

### 2026-09-17 — App 3.1.7 可选择的数据安全固件回滚

- “仪表固件更新”中新增历史版本选择，首个选项为 2.6.0。连接、扫描或选择版本都不会自动进入 OTA；只有单独完成驻车二次确认后才会手动开始回滚。
- 内置的 2.6.0 回滚镜像保留当时的界面与车辆逻辑，同时回迁当前 NVS、行程详情、里程兼容和 OTA 恢复机制，避免新版数据被旧存储结构重置，并确保之后仍可通过 App 升回新版本。
- 回滚继续执行完整硬件/分区匹配、镜像 SHA-256 校验、备用分区写入与回读，并在成功、失败、断开或超时后退出 OTA。

### 2026-09-17 — 固件/App 3.1.6 仪表显示统一四舍五入

- 设备信息页总里程只显示已经完成的整数公里，例如 `5488 km`。
- 其余仪表数值在降低显示精度时统一四舍五入，不再截断：包括即时/平均油耗、单次及累计里程、累计燃油、行程分钟、电压、AFR 和整数制动温度。底层统计值与 BLE 数据保持原精度。
- 固件内置于 Android 安装包，通过现有的 App 手动 OTA 流程推送，无需 USB 直刷。

### 2026-09-17 — 固件/App 3.1.5 里程放置于 OTA Mode 上方

- 将同步里程移至设备信息页，位于 `OTA Mode` 按钮正上方；数据参数信息页和油耗页均不再显示里程。
- 仪表只显示数值和单位，例如 `5488.6 km`，不附加 ODO、估算或校准说明。手机显示开关继续控制该行，数据采集逻辑不变。

### 2026-09-17 — 固件/App 3.1.4 信息页里程

- 将同步/校准里程从油耗页移到仪表信息页底部的独立可见区域。
- 信息页直接读取仪表持久化里程快照，不再等待车辆是否支持可选的原厂里程 PID 01 A6。手机显示开关仍会同时控制仪表信息页与 App 首页；OBD 采集、行程统计和 Flash 写入频率均不变。

### 2026-09-17 — 固件/App 3.1.3 首页里程清晰显示

- 油耗首页底部里程改为更大的高对比文字，并明确放在可选表圈素材的上层。原来的 16px 深灰样式在车内常用 AMOLED 亮度下几乎不可见。
- 手机端同步开关关闭时仍会同时隐藏仪表和 App 首页里程。本次只调整显示，不增加 OBD 请求、统计任务或周期性 Flash 写入。

### 2026-09-17 — 固件/App 3.1.2 OTA BLE→Wi‑Fi 可靠交接

- 仪表确认用户手动发出的 OTA 指令后，先停止 BLE/OBD 与 ESP-NOW 无线任务，释放蓝牙控制器及内部内存，然后才启动 OTA 热点；该顺序与仪表本地 OTA 页面中已验证的路径一致。
- 预期的 BLE 断开现在就是传输方式交接：Android 搜索 `OBD-Gauge-OTA-*`，通过 `/ota/discover` 获取本次随机令牌，并在上传前重新核对设备兼容性和镜像完整性。
- OTA 入口仍只能手动触发且不持久化。热点启动失败、传输异常、断开或超时都会重启回正常采集；不会擦除 NVS 设置、累计统计、待同步行程和 bootmedia。

### 2026-09-16 — 固件/App 3.1.1 OTA 握手恢复

- 固件不再在 GATT 回调中同步启动 OTA 热点，而是在已确认写入返回后交给一次性任务启动；重复启动命令会被安全忽略。
- 旧固件已经接受 OTA 命令、但 BLE/Wi‑Fi 共存导致 Android 未收到写入回调或链路断开时，App 会搜索已经运行的 `OBD-Gauge-OTA-*` 热点，并通过 `/ota/discover` 重新取得本次会话令牌，不再直接提示握手中断。
- 恢复后仍会复核完整设备清单和内置固件 SHA‑256，之后才允许写入备用 OTA 分区；若热点实际上没有启动，连接超时后会安全结束并恢复正常 BLE/OBD 工作。

### 2026-09-16 — 固件/App 3.1.0 里程显示与同步校准

- 仪表油耗/启动首页可显示估算里程。App 的里程显示开关会同时控制 App 首页和仪表显示；手机校准值通过独立、带 CRC 的特征同步到仪表。
- 里程命令会等待正常 GATT 同步链路空闲。BLE 回调只校验定长数据并提交事件，低频的用户主动 NVS 写入由 UI 任务执行；不增加 OBD 请求、任务或周期性 Flash 写入。
- 行程详情新增仅供测试的数值修订，可修改里程、时长、燃油和驾驶极值。修订值只保存在手机数据库，相同行程重新同步时不会被覆盖，也不会写回仪表。

### 2026-09-16 — 固件/App 3.0.0 行程详情

- 每个已完成行程新增最高速度、最高转速、最大加速和最大减速；平均时速由已记录里程与发动机运行时长计算。
- Android 行程列表可点击进入独立详情页，分区展示概览、速度/转速、纵向加速度、燃油和时间。手机数据库原地升级；旧固件从未采集的字段会明确显示为不可用。
- 仪表复用原有 200 ms 统计周期。高频路径仅增加整数比较，并只在 OBD 整数车速变化时计算一次整数加速度；不增加 OBD 请求、任务、堆分配或 Flash 写入频率。
- 行程 BLE 协议 v2 将记录从 40 字节扩展到 48 字节，App 同时兼容 v1。NVS 原行程结构保持原字段顺序，新详情字段只追加在末尾，升级时保留未结束、待合并及待同步行程。

### 2026-09-16 — Android 2.9.4 可靠恢复页面位置

- 长页面改为等待 Android 完成内容测量后再恢复原滚动位置。
- 固件扫描连续产生状态刷新时，新页面布局前的临时零位置不再覆盖用户原来的位置。

### 2026-09-16 — Android 2.9.3 直接解析清单与滚动位置保持

- 仪表清单改为按固定字段直接读取：固件版本取自 `firmware.version`，更新安全所需的硬件信息取自 `device`，不再使用正则表达式。
- 点击按钮或收到 BLE 状态后即使页面需要重建，也会恢复该页面原来的滚动位置，不再跳回顶部。

### 2026-09-16 — Android 2.9.2 固件扫描异常隔离

- 仪表固件清单解析、本地缓存和更新判断与蓝牙回调隔离；手机端偏好缓存失败不再把有效清单误判为 GATT 异常。
- 覆盖安装时安全迁移旧测试版可能留下的错误类型固件状态。
- 扫描失败只影响更新卡片提示，不断开仪表，也不中断授时、行程、车辆或设置数据传输。

### 2026-09-16 — Android 2.9.1 固件扫描连接隔离

- 检查更新优先复用本次 GATT 连接已经读取的固件信息，避免增加不必要的蓝牙事务。
- 清单缺失、手机蓝牙栈繁忙、数据格式错误或检查超时时，只结束可选的固件扫描；不再关闭健康连接，也不会在重连后重复扫描。
- 正常授时、行程、车辆和设置同步在同一连接上继续；仪表 OBD 采集以及油耗、行程统计逻辑没有改动。

### 2026-09-16 — 固件/App 2.9.0 熄火自动结算行程

- 当前行程在同一次通电期间连续 15 分钟没有有效发动机运行 RPM 后自动结束，不再要求仪表先断电。
- 15 分钟内重新启动发动机会取消待结束状态并继续同一次行程；结算后立即写入历史和手机同步队列。
- 意外断电发生在 15 分钟期限之前时，原有的待确认行程恢复机制继续提供保护。

### 2026-09-16 — Android 2.8.1 保留油量与精简里程

- 新快照未包含有效油量时，App 按已绑定仪表保留最后一次有效读数，不再清空油量、续航和进度条。
- 首页里程精简为 `5000km`；只有尚未校准的估算值才追加 `· 未校准`。

### 2026-09-16 — 固件/App 2.8.0 手动、可续传 OTA

- 连接或扫描更新都不会进入 OTA；只有用户在 App 中明确确认“手动进入 OTA 并更新”后，仪表才暂停 OBD 采集并进入一次性 OTA。
- Wi-Fi 上传块缩小到 64 KiB；传输失败时按仪表确认的字节偏移续传。完整镜像通过 SHA-256 后才写入非活动 OTA 分区，并在切换启动分区前执行 Flash 回读校验。
- 更新成功、取消、传输错误和空闲超时均以重启回到正常模式结束。OTA 状态从不持久化，因此手机意外断开后给仪表重新上电也不会滞留在 OTA 模式。

### 2026-09-16 — Android 2.7.1 更新恢复与里程校准

- 固件扫描增加 30 秒期限，并在服务或 App 进程重启后恢复残留的 `checking` 状态，失败的扫描不会永久禁用操作按钮。
- 检查与安装两个入口均增加驻车确认，明确说明期间可能暂停 OBD 采集、行程记录和统计，禁止行驶中操作。
- 首页按手机中保存的行程估算里程；可在“连接与设置”按车辆当前读数校准，之后本地累加新行程，不写入仪表。

### 2026-09-16 — 固件/App 2.7.0 内置安全 OTA

- Android App 内置匹配 AMOLED 1.75-B 的主应用镜像，并在“连接与设置”提供版本扫描和更新入口。
- 更新前强制核对 BLE 设备清单、镜像大小与 SHA‑256，并在连接仪表临时热点后再次核对设备身份。
- 更新请求等待正常同步空闲，只写非活动 OTA 应用分区；不会上传或擦除 NVS、行程/统计数据和 bootmedia，启动失败沿用固件回滚机制。

### 2026-09-15 — Android 2.6.2 授时后闪退隔离

- 厂商 Android 系统拒绝低油量通知时，不再终止 BLE 服务。
- 车辆、设置和车型的回读处理增加异常边界，授时后的运行异常只重连 BLE，不退出 App。
- 仪表驱动的 ZD8/ZC6 首页切换改为状态广播结束后重建，并隔离图片与厂商渲染器异常。配套仪表固件仍为 2.6.0。

### 2026-09-15 — Android 2.6.1 连接闪退保护

- 仪表同步车型并触发 ZD8/ZC6 首页重建时，不再手动回收仍可能处于绘制队列的旧车辆位图；两张车型图改为随 Activity 生命周期缓存。
- 厂商系统若拒绝后台扫描、接收器注册、前台服务通知或状态广播，这些辅助功能的异常不再终止 GATT 数据链路。
- 车辆与仪表设置缓存兼容开发版本可能留下的错误 SharedPreferences 类型。配套仪表固件仍为 2.6.0。

### 2026-09-15 — 固件/App 2.6.0：仅保留 ZC6 与 ZD8

- 仪表车型滚轮只显示 `ZC6`、`ZD8`，手机使用相同的两个稳定 profile 编号。
- ZC6 改为与已适配 ZD8 相同的单路串行 OBD/PID 架构，不再进入 ATMA/CAN 监听；同时保留 FA20 专用的 Toyota Mode 21 机油温度请求。
- 其他历史 profile 继续保留在源码中，但在 UI、NVS 归一化、运行时 setter 和 BLE 命令四层入口全部冻结；旧配置若选中其他 profile，会迁移为 ZD8。

### 2026-09-15 — Android 2.5.1 ZC6 素材与启动稳定性

- 将不透明的 ZC6 首页图替换为带真实 Alpha 通道的透明抠图，与 ZD8 一样直接融入卡片，不再出现额外矩形背景。
- 车辆素材按卡片实际需要的分辨率采样解码，降低首页内存峰值。
- 车型偏好会迁移中间测试版可能留下的字符串值，不再因 `SharedPreferences` 类型不一致抛出 `ClassCastException`；同时保护厂商系统上的广播接收器生命周期调用。配套仪表固件仍为 2.5.0。

### 2026-09-15 — 手机与仪表 ZD8 / ZC6 车型切换

- Android 2.5.0 只开放 BRZ ZD8 6MT 与 ZC6 6MT 两种车型；首页副标题和对应代际的 3D 车辆图会立即跟随手机选择。
- 新增独立只写 BLE `0x0009` 特征。六字节命令包含版本、长度和 CRC，等待正常同步空闲后发送，再通过原有 `0x0007` 设置快照回读确认；离线选择会保留到仪表重新连接。
- 仪表 BLE 回调只校验并入队，车型缓存重置和 NVS 保存由 UI 任务执行，不阻塞 OBD 传输，也不修改行程或燃油统计。
- ZD8 仍是已完成实车验证的配置；App 中的 ZC6 对应固件原有一代 BRZ/GT86 CAN 配置，本项目仍需实车验证。

### 2026-09-15 — 固件 2.4.0 标识、油耗启动页与锁定视觉

- MultiGauge 继续固定为 `MASTER / 1 / VIDEO`，禁用滚轮的主体和选中行均改用明确的灰色背景。
- NVS 配置 v10 在升级时将启动默认页一次性迁移为 FUEL；之后仍可通过原有启动页选项主动改成其他页面。
- 设备信息页显示 `FW: 2.4.0`；无法读取 Git 历史的本地构建显示为 `BUILD: local`，不再出现 `unknown-0-unknown`。
- Android 2.4.0 在正常数据同步空闲后读取已有的只读设备清单，并显示仪表固件版本；这一可选读取失败不会导致健康的数据连接重连。

### 2026-09-15 — 本地车辆名称与冻结的 MultiGauge 配置

- Android 2.3.9 增加本地首页车辆名称，默认“我的BRZ STI”；自动移除换行并限制最多 9 个字符，避免大标题在小屏换行。
- 仪表保留 MultiGauge 页面，但三个滚轮全部禁用且不再注册数值变更回调。运行值和持久化值统一固定为 `MASTER / 1 / VIDEO`，现有内部设置入口也不能改变它们。

### 2026-09-15 — 隔离式手机亮度控制

- Android 2.3.8 只开放 10%～100% 仪表亮度，其余仪表设置继续禁用。
- 新增独立的只写特征 `0x0008`，命令固定六字节，包含版本、长度和 CRC。手机只在点击应用时提交一次，等待正常 GATT 同步链路空闲后发送，再通过只读 `0x0007` 快照确认实际值。
- 仪表 GATT 回调只校验并无等待入队，显示与 NVS 操作由原有 UI 事件消费者执行；亮度操作失败不会让正常车辆/行程链路重连，也不会修改任何行程或油耗统计。

### 2026-09-14 — 冻结式仪表设置界面与只读同步

- 新增只读特征 `0x0007`：仪表把已加载的 RAM 设置复制为带 CRC 的快照，不访问 NVS，也不改变运行状态。
- Android 在“连接与设置 → 仪表设置”中始终以选择框、开关和数值框列出全部已知参数；同步前显示等待占位，连接后填入仪表实际值。
- 所有控件均为禁用状态。本阶段明确不提供设置写入特征、变更监听、保存/应用操作或任何可修改仪表设置的 Android 命令。

### 2026-09-14 — Android 低油量提醒与系统栏修复

- 修复 Android 8.1 以上限定主题未继承浅色主主题的问题，并统一系统栏颜色、图标明暗、导航栏分隔线和对比度，消除页面边缘的黑条。
- 有效剩余油量低于 25% 时，预估续航数字变为黄色，手机针对每次低油量状态发送一次通知；油量恢复到 25% 或以上后才会重新允许提醒。
- 在预估续航模块底部增加指定的续航估算免责声明。

### 2026-09-14 — 行程间隔暂时锁定为 15 分钟

- 保留设置页现有的 `TRIP GAP` 行，但禁止点击并固定显示 `15 MIN`。配置加载与保存时都会把持久化数值归一为 15 分钟，同时保留字段以便以后恢复可调。

### 2026-09-13 — Android 进程退出后由仪表唤醒

- 手机端新增按绑定仪表地址精确过滤的 `PendingIntent` BLE 系统扫描；普通 App 进程被回收后，仪表开始广播仍可唤醒连接服务。
- 手机启动/解锁、蓝牙开启、应用升级、重新绑定和后台自检都会刷新系统扫描注册，并保留伴生设备唤醒与前台服务作为独立兜底。
- 连接设置页新增系统 BLE 唤醒注册状态和最近一次仪表唤醒记录；Android“强行停止”仍需用户手动重新打开 App。

### 2026-09-12 — 短时断电行程合并稳健性修复

- 行程间隔改为比较上次结束时间与本次上电后推算的实际驾驶开始时间，手机延迟连接不再导致误拆分。
- 在手机完成判定前再次发生 USB/UART 复位时，待判定行程会继续保留。修复没有新增任务、内存分配、BLE 操作或 flash 写入周期。

### ZD8 6MT 快速挡位推算

- 脚部 OBD 模式不再为被网关屏蔽的 CAN `0x241` 进入 ATMA，消除周期性的转速/速度停顿。
- 每轮速度采样由1次提高到4次，同时保留高优先级的交错 RPM 查询。
- 挡位推算改用时间接近的原始速度/RPM、更严格的容差和连续两次确认；离合或换挡状态不可靠时显示 `--`。
- 可选 PID `01 A4` 必须连续两次返回相符的传动比，才能覆盖推算结果。

### 可断点续传的手机行程档案

- 完成的油耗行程现在保存 Unix 起止时间，并进入最多 64 条的 BLE 待确认队列，直到手机确认落库。
- Device Info 服务 `0x1FFA` 新增行程元数据、游标/控制和带 CRC 的记录特征；固件拒绝超前 ACK，避免破坏持久化游标。
- 新增 Android 客户端源码：自动给仪表授时、先写 SQLite 再 ACK、断线后续传，并显示日期、起止时间、时长、里程和油耗。

### 设备端 OTA：BLE 服务 + WiFi 传输

- 新增 **OTA Mode** 页面（从设备信息页进入），把仪表切换为升级目标：发布 OTA BLE 服务（`0x1FFB`）并启动 WiFi SoftAP（`OBD-Gauge-OTA-XXXX`），其 HTTP 端点接收带 SHA256 校验的固件与开机动画上传。
- 固件写入未运行的 OTA 槽位，且开机 15 秒自检通过后才标记有效；早期启动崩溃会自动回滚到上一个版本。
- 开机动画改为事务式更新：先把 `boot_block` 暂存、再提交，更新中断时自动恢复上一次的动画。传输期间暂停 RS485 与 ESP-NOW 以释放 CPU。
- 设备信息页现在显示固件 build tag，取代原来的 ESP-IDF/LVGL 版本号。

### RaceChrono 开关与开机动画模式

- 设置页新增 **RACECHRONO** 开关：关闭时设备进入最小 BLE 模式（仅 Info + OTA 服务，不广播），打开时恢复完整的 RaceChrono + 配对 + OTA 服务集。
- 开机动画模式简化为 **OFF / RACE / VIDEO**；VIDEO 播放手机 App 刷入的 `boot_block`（取代原来的 REI/SHINJI/ASUKA 槽位）。

### 界面一致性修复

- 白色圆环边框和圆角滚轮/滑杆在各配置页统一应用。
- 移除 INFO CUSTOM / TEMP CUSTOM 页多余的标题，并修复设置页标题与顶部缺口图重叠的问题。

### OBD 数据修复

- ELM327 客户端改为单线程，移除 ZC6 CAN 调试日志，并对刹车/机油报警进行节流。
- ZD8 挡位现在优先使用新鲜的 CAN `0x241`，其次尝试标准可选 PID `01 A4`；直接数据不支持、异常或超时后会自动回退到转速/车速推算。
- 修正推算公式中主减速比被重复乘入的问题；旧公式在真车数据下可能无法命中任何挡位。

### OTA 就绪分区与设备清单

- 分区布局已改为 `ota_0` + `ota_1` + `bootmedia`，坏包后可由 bootloader 自动回滚。
- 当前布局下 bootmedia 分区地址回到了 `0x620000`。
- 新增只读 BLE 设备清单服务（`0x1FFA`），供配套 App 在刷写前做硬件兼容性校验。
- 构建信息现在包含 git 分支、提交数和短 hash，release 清单可以按 build number 做版本比较。

### 主题变成配置，不再是代码

加一套 UI 主题现在不用碰任何 C 文件。一个主题就是 `themes/` 下的一个文件夹，里面放一份 `theme.toml` 清单（8 个装饰色 + 可选的美术素材），再往 `themes/registry.txt` 末尾追加一行。`tools/gen_themes.py` 在 CMake configure 阶段自动运行：校验清单、把 PNG 素材转成 LVGL 图片数组、生成 `ui_theme_generated.c`。

- **美术素材**：`ring`（360x360，带透明通道）替换代码画的表框，`needle` 替换指针（LVGL 绕清单里声明的 pivot 旋转，素材必须画成朝右），`dial`（360x360）作为所有页面的背景。没声明的项自动回退到代码绘制 + 对应颜色角色，所以纯配色主题依然只要一个文件。
- **槽位稳定性**：`themes/registry.txt` 固定 槽位 -> id 的映射，且只能追加。NVS 存的是槽位号，一旦调序，所有已有设备在下次 OTA 后会被静默换成另一个主题 —— 而且本地测试根本发现不了。生成器对调序、跳号、槽位 0 不是 `default`、以及登记了却找不到文件夹的情况一律构建失败。
- **构建期校验**覆盖素材尺寸、指针 pivot 越界、文件缺失、滚轮重名、未知字段，以及素材总预算（1536 KB —— 因为所有已注册主题的素材都会被无条件链接进固件）。每条报错都精确到文件和行号。
- 素材转换只在 PNG 的 SHA-256 变化时才需要 Pillow；转换出来的 C 文件是入库的，所以常规编译不依赖任何第三方 Python 包。`--check` 模式供 CI 校验是否过期。
- **修复**：设置页的主题滚轮原来用一个固定 96 字节缓冲区拼选项，主题一多就会静默截断。现在按实际长度精确分配，结构上不可能再截断。
- **修复**：转速报警结束时会把背景恢复成硬编码的黑色，有表盘背景图的话会被永久抹掉。三条恢复路径现在都改为重新应用主题背景。

编写指南：[themes/README.md](themes/README.md)（中英双语）。框架内部实现：[docs/THEMING.md](docs/THEMING.md)。

### 三连表联动转速报警

三块表现在可以随转速上升**依次**亮起，而不是同时闪。报警阈值往下 1000 转被分成三段，每块表按自己配置的位置在自己那一段里由黑渐变到红，到阈值时三块一起闪。每块表都用同一份 ESP-NOW 同步过来的转速自行计算自己的区间，所以不需要额外的表间通信，天然同步。

- 转速报警页新增 LINKED 开关，与原有的单表闪烁互斥。
- 在任一块表上改阈值会广播同步给其它表（新增 ESP-NOW 控制包）。
- 测试按钮会在所有表上跑一段模拟转速曲线（5 秒上升、0.8 秒保持、2.5 秒回落）。
- NVS 配置版本 1 -> 2：新增 `rpm_warn_linked_en` 字段；默认主题索引从 1 改为 0，并带迁移逻辑，保证老设备升级后外观不变。
- 转速闪烁的重绘间隔原来是 1 毫秒，现在改为 25 毫秒。

### 分区调整与开机动画

- app 分区从 6 MB 缩到 4 MB，bootmedia SPIFFS 分区从 9.8 MB 扩到 11.9 MB。
- **烧录方式变更：bootmedia 的地址从 `0x620000` 变为 `0x420000`。** 请同步更新你的烧录命令和脚本。
- 开机动画按 300x300 网格重新编码（原来是 240x240），并改用新的 `delta_varint_rgb565_black_v2` 流格式。


### 三连表：用真蓝牙配对取代绑定按钮

原来的 "BIND MASTER" 按钮是抓从表当下收到的任意一台主表 ESP-NOW 广播来绑定——附近有多台主表时（比如赛道日好几台车都在用）没法指定要跟哪一台。现在主表会真正通过蓝牙广播身份（`SkyGauge-XXYY`），从表在现成的蓝牙扫描页（从表角色下会变成 "FIND MASTER" 配对页）里发现并绑定，之后每次开机都会自动重连。

- 新增 `gauge_pair_ble_client.c/h`（从表侧一次性蓝牙配对客户端）和 `ble_adv_util.c/h`（从 OBD 蓝牙客户端里提出来的共享广播名解析工具）。
- `racechrono_ble_diy.c` 在现有 RaceChrono 服务旁边加了一个独立的配对 GATT 服务，共用同一份蓝牙广播。
- `ui_ScreenPageMultiGauge.c` 去掉了 BIND MASTER / UNBIND 按钮。
- 开机流程：从表没配对过会停在配对页；配对过则直接跳过、进入仪表显示。

### 修复：主表在 OBD 协议探测时看门狗重启

定位到了测试中出现的一次真实重启：等待 ELM327 响应的阻塞循环最多能空等 3 秒且不喂狗，协议自动探测连续超时几次累加起来就会超过 5 秒的 TWDT 窗口，导致轮询过程中重启。已经在等待循环里补上喂狗。

### 其它修复

- 从表蓝牙扫描 15 秒窗口自然到期后状态不会复位，导致重试/删除后重扫静默失效。
- 从表在配对页划走时停的是错误的蓝牙扫描 API，导致配对扫描在后台空跑。
- I2C 设备缓存计数逻辑在超过 8 个地址后会越界读（潜在崩溃，实际还没触发过）。
- LCD 初始化时如果 QSPI 探测失败，会用到未初始化的寄存器数据。

### 优化

- 蓝牙/ESP-NOW 数据断连超时后，仪表页显示 "NO SIGNAL" 提示。
- 档位显示优先使用车型支持的 CAN 精确解码档位，没有时才回退到转速/车速估算。
- 里程/行程统计不再每 30 秒写一次 flash，只在运行时内存里累计（没有界面显示过，纯粹是 flash 损耗）。
- 删除了从未被真正接入使用的 `fsm.h` 状态机脚手架和 OBD 数据的 dirty-flag 跟踪层。
- 把蓝牙广播名解析、屏幕白色圆环边框、深色滚轮样式这三处在约 18 个页面里重复的代码提取成共享实现；节流了一处全量重绘的图表和几个仪表页，只在真正可见/真正变化时才刷新。
