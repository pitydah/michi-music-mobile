# TESTING — Michi Music Mobile

## Validación manual

### 1. Build e install

```bash
./gradlew assembleNormalDebug
adb uninstall org.michimusic.mobile.debug || true
adb install -r app/build/outputs/apk/normal/debug/app-normal-debug.apk
```

### 2. Verificar paquete instalado

```bash
adb shell dumpsys package org.michimusic.mobile.debug | grep -E "versionName|versionCode|targetSdk"
adb shell pm path org.michimusic.mobile.debug
```

### 3. Logcat

```bash
adb logcat -c
adb shell am force-stop org.michimusic.mobile.debug
adb shell monkey -p org.michimusic.mobile.debug 1
adb logcat | grep -E "MichiAudio|MediaController|ExoPlayerImpl|MediaSessionImpl|PlaybackResumption|UnsupportedOperationException"
```

#### Al abrir sin interactuar:
- NO debe aparecer `MichiAudio: ensureConnected called`
- NO debe aparecer `ExoPlayerImpl Init`
- NO debe aparecer `UnsupportedOperationException onPlaybackResumption`

#### Al tocar reproducir:
- `MichiAudio: playQueue requested`
- `MediaController ready`
- `ExoPlayerImpl Init`

### 4. Pruebas unitarias

```bash
./gradlew :core:test
```
