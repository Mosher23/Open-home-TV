# Validation

## Version 0.3.0 — dashboard, rooms, calendar and cameras

Validated on 7 November 2026 with the same Android TV API 34 / 1080p emulator and isolated JDK 17 toolchain.

- Debug APK build, JVM tests and Android lint passed.
- 17 JVM tests passed. Added room area inheritance/overrides, diagnostic exclusion, scene activation availability, all-day DST dates and backward-compatible configuration. Extended protocol coverage to WebRTC subscription events/unsubscribe/disconnect, recoverable command timeout, calendar REST authorization and snapshot REST authorization.
- 8 Android tests passed using real D-pad/Select/Back events, including Climate/Calendar navigation, camera fullscreen, lights and native phone setup approval/persistence.
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
