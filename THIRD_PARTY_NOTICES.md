# Third-party notices

Open home TV source is MIT licensed. Dependencies retain their own licenses:

- Kotlin and kotlinx.coroutines — JetBrains and contributors, Apache License 2.0.
- AndroidX / Jetpack Compose / Compose for TV / Lifecycle / Activity / Media3 — Android Open Source Project and contributors, Apache License 2.0.
- Material icons — Google and contributors, Apache License 2.0.
- OkHttp / Okio — Square and contributors, Apache License 2.0.
- ZXing Core — ZXing authors and contributors, Apache License 2.0; QR generation only, no Google Play Services.
- noble-ciphers — Paul Miller, Thomas Pornin and contributors, MIT License. Full license is bundled at `app/src/main/assets/phone/noble-ciphers.LICENSE.txt`.
- esbuild and Prettier — development-only browser asset tools, MIT License.
- Gradle wrapper — Gradle and contributors, Apache License 2.0.
- JUnit and MockWebServer are development/test dependencies; JUnit 4 uses EPL 1.0, MockWebServer uses Apache License 2.0.

See each dependency's distribution for its complete copyright and license notices. [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) and [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html).

Android System WebView is supplied by the device and is not bundled here. The project does not bundle third-party app logos, live camera imagery or the supplied concept image. Demo app names identify example services and imply no endorsement or affiliation.

The native playback tests include AndroidX Media3’s small HLS fixture under Apache License 2.0. Its source, modification notice and full license are in `app/src/androidTest/assets/camera/`. These test assets are not packaged into the launcher APK.
