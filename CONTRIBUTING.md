# Contributing

Open the project in Android Studio or use JDK 17 and `./gradlew assembleDebug testDebugUnitTest lintDebug`.

Keep this app free of required Google Play Services, analytics, ads, cloud relays, foreground services and periodic background workers. Use explicit local endpoints, bounded caches and lifecycle-owned resources. Keep all essential controls reachable using only D-pad, Select and Back. Do not request root or alter another launcher.

For a change to Home Assistant behavior, add a protocol-level test with MockWebServer. For UI changes, capture Android TV screenshots and exercise remote focus. Do not check in credentials, APK signing keys, personal entity exports or `local.properties`.

See [validation](docs/VALIDATION.md) for hardware checks. Live integrations require a separately configured Home Assistant test instance. Report Android/TV versions, relevant entity domains, steps and sanitized errors when opening issues.
