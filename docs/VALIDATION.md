# Validation

## Version 0.5.2 — live camera grid

- Cameras now renders two feeds per row and starts visible streams immediately, independently of the Home preview setting. Video fills each rounded card, with a name overlay and a Live badge driven by decoded/ready playback.
- At most four intersecting cards own players, prioritizing the most exposed cards during scrolling. Off-screen and prefetched cards own no decoder/WebView. Fullscreen removes all grid players; Back or the on-screen Back action restores the selected camera and scroll position.
- The new native regression test uses six configured HLS fixtures, with Home previews disabled and no Home cameras. It decodes four simultaneous feeds, verifies two-column geometry and D-pad movement, checks fullscreen exclusivity and focus restoration, scrolls to the final row and verifies the initial off-screen players are removed. Leaving Cameras removes the remaining players.
- The debug APK builds; lint passes, all 22 JVM tests pass and all 18 native Android TV tests pass. The preserved signing certificate supports in-place updates. Real-camera playback and decoder limits on the physical Sony remain hardware checks. Camera count/order/endpoints, authentication, permissions and dependencies are unchanged.

## Version 0.5.1 / 0.5.0 — Gallery Grey native implementation

- Version 0.5.1 additionally restores an explicit localized authentication-failed status, rather than describing a stopped invalid-token connection as retrying. The full native suite was rerun on the final patch.
- Final debug build, 22 JVM tests, 17 native Android TV tests, Android lint and 3 phone-protocol Node tests passed on the isolated API 34 / 1080p TV emulator. The phone bundle was rebuilt and included in the final APK. No runtime dependencies or permissions changed.
- The native Gallery layout removes the old header/branding and mountain wallpaper. Clock/weather, power and edge-to-edge camera panels occupy the top row; colorful scenes/lights sit above a seven-banner dock. Shared surfaces are neutral grey with white focus borders. Installed app artwork remains native; demo service tiles use text/color without bundled third-party logos.
- New native assertions check aligned top edges within the first 8% of the screen, increasing weather/power/camera widths, a visible All apps dock target, hourly/daily forecast access and Back restoring weather focus. Existing Home count/order, zero/four-feed geometry, Power/Today, scenes and navigation tests pass.
- Camera Settings entry now focuses the feed-count control; an actual remote test asserts focus there. Zero/four boundaries hand focus to the enabled control. Save/persistence, token storage and Select-to-edit keyboard tests still pass.
- Two real simultaneous HLS fixture players report live only after rendered/ready video and clear that state when disposed; they reopen correctly. The production WebRTC page still decodes synthetic video and detects a stalled feed. These tests do not establish playback with the user’s physical cameras on Sony.
- New configurations and legacy configurations without an explicit Home feed count default to one. Explicit saved counts, camera order/endpoints, app selections, entity mappings and authentication remain intact. Home’s Other row includes unlisted loads; missing visible readings keep the residual unknown. Full load details and Today recorder behavior remain available.
- Manual emulator D-pad navigation (Home → Right → Right → Select) reached the power card and toggled Today; its screenshot was checked. A demo-only memory sample after visiting the views reported 126,148 KiB PSS (~123 MiB), with no active video. This is not a comparative benchmark or a Sony/live-camera memory result.
- Demo-only screenshots were captured from the running app, including the shared styling on secondary pages. The demo camera is an explicitly labelled placeholder, not the generated concept’s garden photo or a claimed live HA feed. Physical Sony/4K behavior, real-feed visual comparison and long memory soaks remain hardware checks. The broader secondary-screen optimization proposal is not claimed fully implemented.

## Version 0.4.2 — save one Home camera from TV settings

- Build, Android lint, 22 JVM tests and 16 native Android TV tests passed on the isolated API 34 emulator. No installation or configuration change was made on the physical TV.
- The missing Save action on the TV camera subsection left count/order changes as a draft; the previous workflow required returning to main Settings and saving there. The camera subsection now provides the same validated Save action at both ends of the list, a Saved/Unsaved indicator and localized instructions.
- The new remote regression test changes two feeds to one, presses the subsection’s top Save with D-pad Select, verifies persisted preferences, clears the Activity’s ViewModel store and recreates the Activity. The fresh ViewModel loads one feed from saved settings and Home renders only the first camera while retaining all camera selections. Reopening settings still shows one. The footer Save also persists zero. This is a fresh-model/Activity reload test, not a physical TV process-kill test.
- Existing camera-count/order, phone setup (including one-feed apply), keyboard, D-pad, live HLS/WebRTC, lifecycle and secure-storage tests remain passing. No permissions or dependencies changed. The APK uses the same update-compatible signing certificate.

## Version 0.4.1 — video signaling and playback recovery

- Build, 22 JVM tests, 15 Android tests and Android lint passed on the isolated API 34 Android TV emulator. Physical TV app data and installed APK were not changed.
- New native playback tests decode an animated synthetic WebRTC camera using the production receiver page and real trickle ICE. Remote candidates intentionally omit both mid/index fields. After decoded frames stop, the watchdog reports VIDEO_STALLED. A transport connection alone does not pass the test.
- Two simultaneous local fMP4 HLS streams render first frames, then both render again after their players leave and re-enter composition. The test assets are Apache-licensed Media3 fixtures, bundled only in the test APK.
- One existing Settings test initially failed to find an uncomposed camera-count control; its isolated rerun passed. It now explicitly waits for focus and scrolls the lazy target into view before Select. The final full suite passed.
- The HA protocol test sends camera/webrtc/candidate with the session ID, checks entity/candidate payloads and rejects sends after session close. Existing disconnect/reconnection tests also pass.
- Read-only inspection confirmed the selected real cameras have no endpoint overrides and HA provides WebRTC configuration. The user reports both cameras play in HA but only snapshots appear in v0.4.0. The installed launcher was not running during the read-only ADB checks, so no current playback error or real-camera first frame was captured. These fixes address verified code defects; playback on the physical Sony with those cameras remains unverified.
- WebRTC now consumes HA’s optional ICE configuration, sends candidates arriving after the offer and supplies missing remote candidate mids. It matches HA’s receive-only audio/video negotiation while keeping playback muted. The decoded-frame watchdog, native no-frame timeout and renderer-loss fallback cover empty/stalled sessions. HLS skips unused audio decoders and displays safe error codes even with a snapshot.
- No HA settings, camera configuration, device state, permissions or production dependencies changed. No private camera images, URLs, credentials or SDP were added to the repository.

## Version 0.4.0 — cameras, loads and action icons

- Build, 22 JVM tests, 13 Android tests and lint passed. Node protocol tests also passed (3).
- The emulator suite was restricted with ANDROID_SERIAL=emulator-5554; the connected physical TV was not installed, tested or reset by the agent.
- New tests verify legacy six-widget migration without Home heating, live-preview defaults, camera count limits, ordering and endpoint persistence, four-feed row layout, zero/one-feed rendering, power/Today toggling, full load details and scene activation.
- Phone setup tests verify camera sequence/count and energy overrides survive encrypted apply, and reject out-of-range counts or power sensors used as daily energy meters.
- Protocol/model tests verify HA energy preferences and five-minute recorder changes requested in kWh from local midnight; missing/negative statistics are not treated as lifetime readings or zero. Nested included meters are not double-counted.
- HA MCP read-only inspection confirmed energy/get_prefs contains home and individual appliance pairs. A recorder/statistics_during_period query with types=[change], period=5minute and units.energy=kWh returned valid data for Today. No HA settings or services were modified.
- Home, Today energy, scene grid and Climate preset visuals use demo-only screenshots. Real simultaneous camera decoding and Sony firmware behavior still need target-TV validation. Browser UI verification remains unavailable in this session; the bundled phone page builds and its host/protocol paths are tested.

## Version 0.3.1 — keyboard activation

- Debug APK, 17 JVM tests, Android lint and 7 targeted Android tests passed on the API 34 TV emulator.
- The new regression test observes actual IME accessibility windows: D-pad focus moves between URL and token rows without a keyboard; Select opens the editor and keyboard; IME Done applies the draft and restores focus; Back cancels a token draft without saving it.
- The existing remote navigation tests cover Settings actions, Devices grouping, lights, camera fullscreen, Climate/Calendar and phone setup QR navigation with the new field presentation.
- All manual Settings fields use the shared TV editor, including filters, app labels and camera endpoints. Token drafts remain outside saved-instance state and are cleared on editor dismissal.
- The APK retains the prior local signing certificate. No permissions or dependencies changed.

## Version 0.3.0 — dashboard, rooms, calendar and cameras

Validated on 7 November 2026 with the same Android TV API 34 / 1080p emulator and isolated JDK 17 toolchain.

- Debug APK build, JVM tests and Android lint passed.
- 17 JVM tests passed. Added room area inheritance/overrides, diagnostic exclusion, scene activation availability, all-day DST dates and backward-compatible configuration. Extended protocol coverage to WebRTC subscription events/unsubscribe/disconnect, recoverable command timeout, calendar REST authorization and snapshot REST authorization.
- 10 Android tests passed using real D-pad/Select/Back events, including Devices grouping/category filters and Climate/Calendar pause/resume, camera fullscreen, lights, native phone setup approval/persistence and direct lifecycle cancellation while UI frames are paused.
- 3 Node AES-GCM protocol tests passed; bundled phone JavaScript rebuilt successfully. Browser UI verification for this update was unavailable because local browser use was disabled; the earlier 0.2.0 browser pass remains below.
- Visually inspected Home, app banner grid, Climate, Calendar, Settings and German Home. Screenshots contain demo data only.
- Debug demo PSS: 125,405 KiB (~122 MiB) after navigating screens; 117,490 KiB (~115 MiB) after five background/foreground cycles. One Activity and zero WebViews in both samples. This is a short emulator observation with cameras disabled, not a leak proof or a real-camera benchmark.
- APK signing certificate matches the prior local build, allowing `adb install -r` updates from that build without losing app data.
- HA MCP read inspection found configured areas, calendar entities and scenes, and the custom Energy Wall card. Both checked cameras advertise HLS and WebRTC. The connector rejects `camera/stream` as unsupported for its one-shot command tool, so live video/codec validation on the physical BRAVIA remains required. No HA configuration or device state was changed.

Automatic HA-native WebRTC, HLS/decoder fallback, stream renewal, clipped TextureView rendering and labeled snapshot fallback are implemented. Protocol/lifecycle tests do not establish that either physical camera plays on the TV. Check both configured cameras after upgrading, preferably using low-resolution H.264 substreams where available.

## Version 0.2.0 — phone setup

- 13 JVM tests passed, including AES-GCM integrity/replay/response binding and HTTP Host/Origin/body limits.
- 7 Android tests passed, including real TV approval gating, pending token reuse, entity/app validation, atomic Keystore persistence, apply/close, cancellation/expiry, and D-pad navigation.
- The native UI test decodes the QR from its rendered pixels and compares the URL with the active session.
- 3 Node browser-protocol tests passed against independent standard AES-256-GCM, including corrupt-message rejection and large envelopes.
- A real browser exercised the bundled page at 390×844: connect with fixture credentials, choose weather and power sensors, select a light/camera, enter an HLS endpoint, add/reorder apps, apply and verify the resulting JSON and closed listener. This used a development fixture, not a real HA server or physical phone.
- No new Android permissions, boot receivers, background services or mandatory Google services were added. The server exists only while the explicit setup screen is open.

Physical phone/TV LAN routing, Sony firmware and real HA/camera interoperability still need device validation. See [phone setup security model](PHONE-SETUP.md#security-model), including the trusted-LAN HTTP bootstrap limitation.

## Original 0.1.0 baseline


Validated on 5 November 2026 with JDK 17, Gradle 8.11.1, Android SDK 35 and an ARM64 Android TV API 34 emulator on macOS. Emulator RAM: 2048 MB; display: 1920×1080; software graphics. No physical TV, HA credentials or live camera endpoints were supplied.

## Completed

| Check | Result |
| --- | --- |
| `./gradlew assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest` | Passed |
| JVM tests | 9 passed, 0 failures |
| Android tests | 4 passed, 0 failures |
| Debug APK signature | Verified, APK Signature Scheme v2 |
| Runtime dependency graph | No `com.google.android.gms` or `com.google.firebase` modules |
| English/German dashboard and Settings visual checks | Passed at 1080p |
| Five Settings/background → HOME/foreground cycles | Completed, one launcher Activity remained |
| Crash log after runtime checks | No AndroidRuntime errors |

The native tests inject real D-pad/Select/Back key events. They verify Settings and all connection actions remain visible, demo light state changes, camera fullscreen opens/closes and HOME navigation restores the dashboard. The fourth test uses the real Android Keystore, verifies that persisted preferences do not contain the plaintext token, reopens the settings store, checks configuration persistence and removes the test token. Tests use an isolated token namespace.

Protocol tests cover authentication, invalid credentials stopping retries, event buffering before snapshot completion, command replies, REST bearer headers/HTTP errors, reconnection after server disconnect and no reconnect after explicit disconnect. Model tests cover power units, missing/unavailable/non-finite readings, temperature conversion and server/camera URL validation.

## Memory and launch observations

Debug demo dashboard with camera previews disabled:

- PSS after launch/idle capture: **117,624 KiB (~115 MiB)**.
- PSS after five background/foreground cycles: **116,635 KiB (~114 MiB)**.
- One Activity, zero WebViews in both samples.
- A measured debug cold `am start -W` reported **978 ms** TotalTime. Earlier runs ranged approximately 1–1.6 seconds.

These are short emulator observations, not a leak proof, a release-build benchmark or a promise for Sony hardware. Shared system memory and software graphics affect the numbers. Camera decoders/WebView add memory that is not represented by the demo baseline. No camera streams run in demo mode.

## Remaining hardware/integration verification

- Physical Sony BRAVIA D-pad/Back/Home behavior, HDMI visibility, default HOME selection and boot behavior.
- Physical 4K output. This emulator stayed at 1080p after a requested size override, so no 4K runtime pass is claimed. The width-scaled layout supports 16:9 4K in code.
- A live HA server's permissions, forecast support, entity removal, power sign/unit conventions, TLS certificates and network interruption/resume.
- Actual go2rtc SDP/ICE/codec interoperability, HA HLS playback, expired stream renewal, two-feed decoding and fullscreen transitions with real streams.
- A longer memory soak on the target TV, including repeated camera open/close and standby/wake.

## Repeat checks

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
# Start an English Android TV emulator before this command:
./gradlew connectedDebugAndroidTest
./scripts/capture-tv-diagnostics.sh emulator-5554 captures/before
```

For hardware profiling, capture `dumpsys meminfo` before/after a 30-minute camera/navigation session and compare Activity/WebView counts and Java/native heap after idle. Test Wi-Fi disconnection and recovery, invalid/revoked tokens, unavailable/removed entities, every remote direction, app launch/return, Settings access and standby/wake. Limit previews to two low-resolution substreams. Review logs/screenshots for personal data before publishing.

Lint has no errors. Remaining warnings include newer dependency versions, the deliberately landscape-only TV Activity, a small static vector TV banner and reserved strings. The project pins a tested toolchain; updating it should be a separate reviewed change.
