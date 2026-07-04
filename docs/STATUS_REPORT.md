# STATUS REPORT — Michi Music Mobile

| Campo | Valor |
|-------|-------|
| Commit | `9eff637` |
| Variante instalada | `normalDebug` |
| APK | `app/build/outputs/apk/normal/debug/app-normal-debug.apk` (79MB) |
| Fecha build | 2026-07-03 |
| `adb` disponible | NO (entorno sin dispositivo) |
| Logcat real | NO (no se pudo ejecutar sin adb) |

## Resultados de compilación

| Tarea | Estado |
|-------|--------|
| AudioController: MediaController.Builder solo en ensureConnected | ✅ |
| NavGraph: sin getAudioController al arrancar | ✅ |
| MiniPlayer: sin getAudioController en composición (solo en onClick) | ✅ |
| pendingQueue con ensureConnected | ✅ |
| enableOnBackInvokedCallback en manifest | ✅ |
| onPlaybackResumption sin failedFuture | ✅ |
| BUILD SUCCESSFUL | ✅ |

## Verificaciones pendientes (requieren adb + dispositivo)

| Condición | Esperado |
|-----------|----------|
| ExoPlayer arranca al abrir | NO |
| onPlaybackResumption warning | NO (emptyList) |
| MiniPlayer conecta Media3 al arrancar | NO (state=null, no inyecta) |
| pendingQueue ejecuta al estar listo | SÍ |

## Comandos para validación manual

```bash
# Build e install
./gradlew clean assembleNormalDebug
adb uninstall org.michimusic.mobile.debug || true
adb install -r app/build/outputs/apk/normal/debug/app-normal-debug.apk

# Verificar paquete
adb shell dumpsys package org.michimusic.mobile.debug | grep -E "versionName|versionCode|targetSdk"
adb shell pm path org.michimusic.mobile.debug

# Logcat
adb logcat -c
adb shell am force-stop org.michimusic.mobile.debug
adb shell monkey -p org.michimusic.mobile.debug 1
adb logcat | grep -E "MichiAudio|MediaController|ExoPlayerImpl|MediaSessionImpl|PlaybackResumption|UnsupportedOperationException|OnBackInvokedCallback"
```

Resultado esperado al abrir sin tocar:
```
(ningún MichiAudio log, ningún ExoPlayerImpl Init, ningún UnsupportedOperationException)
```

Resultado esperado al tocar reproducir:
```
MichiAudio: playQueue requested
MichiAudio: playQueue deferred (si no estaba listo)
MichiAudio: MediaController ready
MichiAudio: Executing deferred playQueue
ExoPlayerImpl Init
```
