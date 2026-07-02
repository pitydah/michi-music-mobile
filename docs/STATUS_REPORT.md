# STATUS REPORT — Michi Music Mobile

**Date:** 2026-07-01
**Version:** 0.1.0-alpha
**Branch:** main
**Commit HEAD:** `bc48544`

## Build Status

- `./gradlew clean assembleNormalDebug`: **SUCCESS**
- APK: `app/build/outputs/apk/normal/debug/app-normal-debug.apk`
- Platform: ARM64 (aarch64) with QEMU x86_64 binfmt for AAPT2

## Modules

| Module | Status | Responsibility |
|--------|--------|---------------|
| `:core` | OK | Shared models (Track, Album, Sync DTOs, pairing models) |
| `:data` | OK (KSP Room) | Room database (9 entities), MediaStore reader, repositories |
| `:player` | OK | Media3 ExoPlayer, AudioController lazy, ReplayGain processor |
| `:sync-client` | OK | Ktor HTTP client, UDP discovery, transfer manager |
| `:remote` | OK | OkHttp remote control client |
| `:app` | OK | UI, navigation, DI (normalDebug variant) |

## AudioController — lazy init con pending queue

- `init` block que cree MediaController: **NO EXISTE**
- `MediaController.Builder` solo en `ensureConnected()`: **SÍ**
- `pendingTracks`/`pendingStartIndex`/`pendingPlay`: **SÍ** — `playQueue()` guarda la acción si MediaController aún no está listo, y `flushPending()` la ejecuta cuando la conexión asíncrona se completa
- `connectStarted` es `@Volatile`: **SÍ**
- NavGraph ya no inyecta `AudioController` al arrancar: **SÍ**
- Logs con tag `MichiAudio` para verificar: **SÍ**

## Design System verificable

- `MichiTokens.kt`: **EXISTE**
- Componentes en `ui/components/`: **14 archivos**
- `MichiBottomNavigation` usado en NavGraph: **SÍ** — 0 referencias a `NavigationBar` de Material3

## MichiBackground/MichiScreen adoptados

| Pantalla | MichiBackground | MichiScreen |
|----------|:---:|:---:|
| HomeScreen | - | ✅ |
| AlbumsScreen | ✅ | - |
| SearchScreen | ✅ | ✅ |
| QueueScreen | ✅ | ✅ |
| PlaylistsScreen | ✅ | - |
| SettingsScreen | ✅ | - |
| RemoteScreen | ✅ | - |
| SyncScreen | ✅ | - |

## Screens (13/13)

| Screen | Estado | Notas |
|--------|--------|-------|
| HomeScreen | ✅ | Header, search bar, quick actions, 8 canciones |
| AlbumsScreen | ✅ | CoverFlow, play button por album, estados premium |
| NowPlayingScreen | ✅ | AudioController real, seek, play/pause/next/prev, source selector |
| PlaylistScreen | ✅ | All tracks view (playlist general) |
| PlaylistsScreen | ✅ | Playlists reales desde MediaStore, expand/colapsar |
| QueueScreen | ✅ | Cola real con índice activo, limpiar |
| SearchScreen | ✅ | Local + synced + remote, MichiEmptyState |
| SyncScreen | ✅ | Discovery, pairing, registerLegacy, descarga |
| SyncedTracksScreen | ✅ | Descargadas con play |
| RemoteScreen | ✅ | KDE remote control, auto-config desde Sync |
| SettingsScreen | ✅ | ReplayGain, auto-sync, remote URL/token |
| AudioRouteScreen | ✅ | USB/Bluetooth/Local detection |
| DiagnosticsScreen | ✅ | Info de estado |

## Tests

- 5 tests de serialización de modelos Sync en `core/src/test/`
- `./gradlew :core:test`: **5/5 pass**
- Dependencias: `kotlin-test` + `kotlin-test-junit`

## Known Issues / Pendings

1. **NowPlayingScreen volumen no conectado** — Slider de volumen usa estado local. Media3/ExoPlayer no expone control de volumen por sesión.
2. **Pruebas en dispositivo real** — No se ha instalado ni probado en un teléfono real.
3. **AlbumArtworkCard colores hardcodeados** — 4 colores synthwave en `NowPlayingScreen.kt` (líneas 305-307, 331, 336). Deberían estar en `Color.kt`.
4. **CoverGrid fallback sin Coil3 placeholder** — `AlbumCoverViewHolder` carga sin fallback/error. Si la imagen no carga, no hay placeholder.
5. **MichiBackground / MichiScreen sin uso** — Creados pero ninguna pantalla los importa.
6. **NowPlayingScreen usa `__synthwave_gradient__` visual sin extraer** — `AlbumArtworkCard` es un componente grande dentro de NowPlayingScreen que podría extraerse.

## Próximo paso recomendado

```
Instalar APK en dispositivo real.
Verificar reproducción local.
Verificar logcat que ExoPlayer no se inicializa al abrir.
Ejecutar checklist en docs/TESTING.md.
```
