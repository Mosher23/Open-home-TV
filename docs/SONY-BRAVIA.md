# Sony BRAVIA installation and recovery

Requires Android TV/Google TV, Android 8.0 (API 26) or later. The app does not require Google Play Services. ADB availability, wireless debugging and default HOME selection vary by Sony firmware.

1. Build the debug APK with `./gradlew assembleDebug`, or obtain the debug artifact from this repository's Actions build.
2. On the TV, enable Developer options by selecting the Android TV OS build number repeatedly under Settings → System/Device Preferences → About. Enable USB debugging or Wireless debugging as exposed by your firmware.
3. Connect from a trusted computer on the same LAN. Authorize its debugging prompt on the TV. Newer Android versions may require pairing first:

```sh
adb pair TV_IP:PAIRING_PORT
adb connect TV_IP:DEBUG_PORT
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.hatv.launcher/.MainActivity
```

Use the ports shown by the TV. Some older firmware uses `adb connect TV_IP:5555`; do not assume port 5555 on every TV.

4. Open the app and verify the demo dashboard before connecting HA. For easy configuration, use **Settings → Set up with your phone** and follow the [phone setup guide](PHONE-SETUP.md). Manual setup is also available. Select apps and entities in Settings. Save a server URL and a token, test the connection and then disable Demo mode and save. Create a dedicated HA user with appropriate permissions; its long-lived token has that user's access. HTTPS is preferred; cleartext HTTP sends the token unencrypted on your LAN.
5. Choose Open home TV in the TV's default Home app setting if present. Press Home and select it in the chooser if one appears. An optional ADB command supported on many Android versions is:

```sh
adb shell cmd package set-home-activity dev.hatv.launcher/.MainActivity
adb shell input keyevent KEYCODE_HOME
```

Some Sony firmware prevents replacement; opening the launcher as a regular TV app still works. No Sony launcher package needs to be disabled or removed. The app has no boot receiver: Android starts the chosen HOME activity at boot where supported.

## Restore the original launcher

Use Open home TV's sidebar **Android TV settings** button to change the default Home app. If necessary, list HOME handlers and choose the exact original component from your TV:

```sh
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package set-home-activity ORIGINAL_PACKAGE/ORIGINAL_ACTIVITY
```

As a last reversible recovery step, uninstall only this app:

```sh
adb uninstall dev.hatv.launcher
adb shell input keyevent KEYCODE_HOME
```

Uninstalling removes its token and settings. Keep the stock launcher installed. Disable debugging again when finished if you do not need it.

## Camera setup

Choose camera entities, then configure each camera's optional endpoints:

- go2rtc WebRTC: `http://GO2RTC_LAN_IP:1984/api/webrtc?src=front_door` (HTTP SDP API).
- HLS: a local HLS playlist URL, or blank to use Home Assistant's `camera/stream` command.

Use lower resolution substreams (e.g. 640×360 or 720p) for dashboard previews. Preview streaming is disabled by default. Credentials in camera endpoint URLs are not supported; use a trusted LAN endpoint or HA HLS authentication. Do not expose an unauthenticated go2rtc server to the Internet.

For updates, use `adb install -r` with an APK signed with the same key. See [update signing details](PHONE-SETUP.md#updating-an-existing-installation) before switching between local and CI builds.
