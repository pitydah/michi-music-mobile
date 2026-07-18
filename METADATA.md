# Michi Music Mobile — F-Droid Build Metadata

## Build Instructions

```bash
git clone https://github.com/pitydah/michi-music-mobile.git
cd michi-music-mobile
./gradlew assembleFdroidRelease
```

APK output: `app/build/outputs/apk/fdroid/release/app-fdroid-release.apk`

## Requirements

- JDK 17+
- Android SDK 35
- Gradle 9.6 (wrapper included)

## Flavors

| Flavor | Description |
|--------|-------------|
| `normal` | Full version with network sync |
| `fdroid` | F-Droid variant (identical features, minified, reproducible build) |
| `playstore` | Google Play variant |

## Permissions Justification

- `INTERNET` — LAN-only communication with desktop Michi Music Player via HTTP. No external internet required.
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` — UDP multicast discovery of desktop server on local network.
- `BLUETOOTH` / `BLUETOOTH_CONNECT` — A2DP device detection for audio output routing.
- `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` — Access local music files on device.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — Required for background audio playback (Android 14+).
- `POST_NOTIFICATIONS` — Media playback notification (Android 13+).
- `WAKE_LOCK` — Keep CPU awake during music sync/download.

## Network Security

Cleartext HTTP is allowed only for private IP ranges (192.168.x.x, 10.x.x.x, 172.16-31.x.x, localhost) via `network_security_config.xml`.

## Build Reproducibility

The `fdroid` flavor has `reproducibleBuild = true` enabled. APK should be bit-identical across builds with the same source commit.

## License

GPL-3.0-or-later
