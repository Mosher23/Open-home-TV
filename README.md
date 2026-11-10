# Open home TV

A lightweight, ad-free Android TV HOME launcher with local Home Assistant integration. Built with Kotlin and Jetpack Compose for TV. No mandatory Google Play Services, accounts, ads, analytics, subscriptions or cloud relay.

**Status: 0.5.0 — Gallery Grey: header-free Home, colorful icons and a full-width app dock.** Starts in an interactive demo mode without credentials. Real Home Assistant and camera paths are implemented; compatibility with your HA installation, camera codecs and Sony firmware needs device validation. This is an independent community project, not an official Home Assistant or Sony product.

![English demo dashboard](docs/images/dashboard-en.png)

[Apps](docs/images/apps-en.png) · [Climate](docs/images/climate-en.png) · [Devices](docs/images/devices-en.png) · [Calendar](docs/images/calendar-en.png) · [Today energy](docs/images/energy-today-en.png) · [Scenes](docs/images/scenes-en.png) · [Settings](docs/images/settings-en.png)

Manual Settings fields stay in browse mode until you press **Select/OK**. The editor opens the keyboard explicitly; **Done** applies the draft, and **Cancel/Back** discards it and restores field focus. The main Settings **Save** still saves your configuration.

## Set up with your phone

Open **Settings → Set up with your phone** on the TV, scan its QR code and approve the matching number with your remote. Paste the HA URL/token in your phone browser, select sensors, controls, cameras and apps, then **Apply to TV**. A verified connection automatically turns off Demo mode. The temporary local setup server closes afterwards; no phone app, Google service or cloud relay is needed.

[Phone setup and updating instructions](docs/PHONE-SETUP.md) · [TV pairing screenshot](docs/images/phone-setup-tv.png)

Use a trusted private home network. Setup API payloads are encrypted; the local HTTP bootstrap page is not authenticated against active network tampering. See the guide's security model.

## Dashboard

- Neutral graphite/grey panels, colorful line icons, no title/header band, 16:9 layout scaled for 1080p/4K, white D-pad focus and English/German system localization. Clock/weather, power and edge-to-edge cameras use the recovered top area; scenes/lights sit above the bottom app dock. Select the clock/weather panel for hourly/daily forecasts.
- Bottom app dock and Apple TV inspired banner grid, using installed app artwork. Pin/unpin apps from the grid; order, labels and icon initials remain configurable. HDMI shortcuts appear when the TV exposes passthrough inputs. Icon-only navigation retains accessible names. Android TV settings are inside Settings.
- HA weather entity, current temperature/humidity/wind, hourly/daily forecasts and Celsius/Fahrenheit display.
- Individual-load flow chart with a home source, colored appliance branches and Other consumption. Select the card to toggle live W/kW power and Today’s kWh consumption; Details opens the full load list. It reads the HA energy dashboard’s home and device meter pairs automatically, with optional overrides on TV or phone. Daily values use recorder changes since midnight in HA’s time zone, not lifetime counters.
- Devices are grouped by kind (lights, switches, covers, thermostats), alphabetized within each group, with persistent category filters and hidden empty groups.
- All HA scenes appear automatically, including scenes never activated before. Modern colored icons distinguish morning, night, cinema, home, eco and other scenes; thermostat presets use the same icon style. Lights and switches remain configurable. Heating controls are in Climate and Devices, with no heating panel on Home.
- Climate groups sensors and controls by HA area, inheriting device areas when needed: temperature, humidity, CO₂, PM2.5, thermostat setpoint/modes/presets, and supported cover open/stop/close actions. Hidden and diagnostic entities are excluded.
- Calendar automatically discovers visible HA calendars and loads a seven-day agenda on demand, with calendar filters, all-day dates in HA’s time zone, event details, partial-failure reporting and Refresh.
- Camera fullscreen playback and live Home previews enabled by default, with one Home feed for a new configuration. Choose 0–4 feeds and their sequence from TV Settings → Home cameras and streams or phone setup. Zero hides the Home camera panel; three/four feeds use a grid, in row order. Automatic HA-native WebRTC, optional explicit go2rtc endpoint, then Media3 HLS fallback. Cropped video fills cards. If video fails, a labeled snapshot refreshes every five seconds while HLS retries; it is never labeled live. Cameras stop when hidden.
- HA URL/token settings, connection testing, entity selection, camera endpoints, app selection, widget order, panel appearance and optional animation.
- Android TV HOME registration, normal TV app entry, Android settings access, and safe return to the stock launcher.

The attached concept is the layout reference. This release uses a lightweight vector background and installed app icons rather than bundled photographic wallpaper or third-party brand assets.

## Build

Requires **JDK 17**, **Android SDK platform 35** and an Internet connection to resolve build dependencies once. Android Studio can install the required SDK and open this directory directly.

```sh
git clone https://github.com/Mosher23/Open-home-TV.git
cd Open-home-TV
# Set ANDROID_HOME to your Android SDK, or use Android Studio's local.properties.
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows: use `gradlew.bat`. The checked-in Gradle wrapper pins Gradle 8.11.1 with distribution checksum verification. Android Gradle Plugin 8.7.3 and Kotlin 2.0.21 are pinned. Dependency versions are deliberately reproducible; AndroidX libraries come from Google's Maven repository but do not require Google Play Services on the TV.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Minimum TV Android version: **8.0 / API 26**. The GitHub Actions workflow builds a debug APK and uploads it under **Actions → Android → build → Artifacts → Open-home-TV-debug**. Debug APKs are for development; a production release should use your own signing key outside the repository. For in-place updates, use the same signing key as the installed APK; fresh CI debug builds may have different keys.

## Install on Sony BRAVIA

See [Sony installation and recovery](docs/SONY-BRAVIA.md) for pairing, firmware differences, default HOME selection and recovery. Quick start after connecting ADB:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.hatv.launcher/.MainActivity
```

Select this app as the default Home app only after checking navigation. This app never disables or uninstalls the Sony/Google launcher. Android launches the selected HOME activity automatically where firmware supports it; there is no background boot service.

## Connect Home Assistant

The recommended route is [phone setup](docs/PHONE-SETUP.md). Manual entry with the TV remote remains available:

1. Use the demo dashboard first. Apps in the demo strip are sample tiles; select real installed apps in Settings → Choose and order apps.
2. In HA, create a long-lived access token from a suitable dedicated user's profile.
3. Enter the local HA server URL and token in launcher Settings. Test the connection. Disable Demo mode and Save to connect.
4. Reopen Settings → Choose entities. Pick weather, power sensors, controls and cameras. Camera selection is independent of controls.
5. Back to the main Settings screen and **Save**. Selection changes remain a draft until saved. Widget selection moves that widget to the first slot within its row.
6. In **Home cameras and streams**, set the feed count (choose **1** for a single camera), put the wanted camera first and press **Save camera view** on that screen. The Saved/Unsaved indicator confirms whether the draft is stored. No return to the main Settings Save button is required.
7. Leave both camera endpoints blank for automatic HA-native WebRTC with HLS fallback. Optionally override with a go2rtc HTTP SDP endpoint such as `http://go2rtc.local:1984/api/webrtc?src=front_door`. Leave HLS blank to request a stream from HA. One preview feed is the default for new/legacy configurations without an explicit count; existing saved counts are preserved. Choose 0–4 and their sequence in Home cameras and streams; prefer low-resolution feeds for three or four.

The token is encrypted with AES/GCM using Android Keystore and excluded from backups. Standard TLS validation is enforced. HTTP is allowed for local compatibility and sends the token without transport encryption; use HTTPS when available. Changing the server URL requires re-entering a saved token. Tokens are never sent to a different camera origin. Camera URLs must not contain embedded username/password credentials. Settings are private app data.

HA REST is used for connection tests/service actions; one authenticated WebSocket provides entity subscriptions, stream requests and forecasts. It reconnects with bounded exponential backoff and stops retrying invalid credentials. All subscriptions and forecast work stop while the Activity is hidden. App shortcuts work independently of HA.

## Automatic discovery and power setup

Assign devices to areas in HA for room grouping. Entity area overrides device area; unassigned controls remain available under Other devices. Configure the area’s temperature/humidity sensor in HA to prioritize it. Scenes and calendars need no manual entity selection. Registry access may require an HA administrator token; if denied, the launcher retains controls and uses available entity naming/area attributes for grouping.

The load card uses your HA Energy dashboard automatically. To override it, map **Whole home** to the actual whole-home power meter. Leave unused solar/grid/battery/EV/heat-pump roles empty. Select **Measured appliances** separately; avoid aggregate meters and duplicates that would count the same consumption twice. Appliance readings are independently measured and may not sum to the whole-home reading. For Today, choose optional cumulative Wh/kWh/MWh meter overrides under Energy meters for Today; recorder statistics are required and may lag by about five minutes. Missing statistics stay missing. No individual sensor IDs from a private installation are built into the app.

## Camera and hardware limits

- WebRTC uses Android System WebView and HA camera capabilities/client configuration, offer subscription, answer and ICE candidate events. Older HA versions without these commands fall back to HLS. An explicit go2rtc HTTP SDP endpoint remains supported.
- WebRTC uses HA’s ICE configuration and sends local candidates throughout negotiation. Any STUN/TURN servers come from your HA configuration; none are hardcoded by the launcher. Local host candidates work without an external service. WebView support, codecs, LAN routing and TV decoder limits still determine compatibility. HLS is the fallback.
- The green Live badge appears only after video frames arrive and playback is ready. A connected WebRTC session must deliver decoded frames; a missing or frozen video falls back to HLS. HLS disables audio decoding for muted previews and shows safe error codes (for example HTTP_401 or DECODING_FAILED) beside snapshot fallback. Snapshot refreshes are still images, not live video.
- At most four configured preview players or one fullscreen player are active. Prefer low-resolution camera substreams. Additional selected cameras are accessible from the Cameras screen without starting hidden streams.
- Sony default-launcher restrictions and HDMI visibility vary by firmware. They cannot be bypassed by a normal application.
- Custom app icons currently support installed package icons or short text initials; arbitrary image import and drag-and-drop widget sizing are future extensions. Widget order is configurable within each row, with three large and two compact slots.

## Development and validation

[Architecture](docs/ARCHITECTURE.md) · [Validation and hardware checks](docs/VALIDATION.md) · [Contributing](CONTRIBUTING.md)

Protocol tests exercise authentication, invalid-token handling, state snapshot/event ordering, reconnection, command responses, REST authorization and URL/unit validation. Native playback tests decode synthetic WebRTC video and two local HLS streams, check stalled-video recovery and reopen players after disposal. Real-camera and physical-BRAVIA validation still requires your own hardware and HA endpoints. See the validation report for measured emulator results and remaining checks.

Primary API references: [Compose for TV](https://developer.android.com/training/tv/playback/compose), [HA WebSocket API](https://developers.home-assistant.io/docs/api/websocket/), [HA REST API](https://developers.home-assistant.io/docs/api/rest/), [go2rtc](https://github.com/AlexxIT/go2rtc).

## License

[MIT](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for dependency licenses. Demo service names are descriptive references; no third-party logos or camera images are bundled.
