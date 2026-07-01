# STATUS REPORT — Michi Music Mobile

**Date:** 2026-07-01
**Version:** 0.1.0-alpha
**Branch:** main
**Commits HEAD:** `30bfa31` (+ working tree changes)

## Build Status

- `./gradlew assembleNormalDebug`: **SUCCESS**
- APK: `app/build/outputs/apk/normal/debug/app-normal-debug.apk` (79 MB debug)
- Platform: ARM64 (aarch64) with QEMU x86_64 binfmt for AAPT2

## Compilation

| Module | Status |
|--------|--------|
| `:core` | OK |
| `:data` | OK (KSP Room) |
| `:sync-client` | OK |
| `:player` | OK |
| `:remote` | OK |
| `:app` | OK (normalDebug variant) |

## What Exists

### Screens (12/12)

- [x] HomeScreen — search bar, quick play/shuffle cards, track list, empty state
- [x] AlbumsScreen — CoverFlow carousel + album tracks, selectedIndex safe
- [x] NowPlayingScreen — datos reales del AudioController, seek conectado, carátula real, theme colors
- [x] PlaylistScreen — all tracks with active-track highlight (playlist general)
- [x] QueueScreen — cola de reproducción real con índice activo
- [x] SearchScreen — search local + synced + remote tracks (via SyncSession)
- [x] SyncScreen — discovery, pairing (username/password), registration, download
- [x] SyncedTracksScreen — downloaded tracks list
- [x] RemoteScreen — KDE remote control
- [x] SettingsScreen — ReplayGain, auto-sync, remote URL/token
- [x] AudioRouteScreen — USB/Bluetooth/Local detection
- [x] DiagnosticsScreen — información de estado de la app

### Navigation

- [x] MichiNavHost con NavigationBar de 6 tabs
- [x] Bottom nav oculta en NowPlaying
- [x] MiniPlayer oculto en NowPlaying
- [x] MiniPlayer no tapa NavigationBar (padding inferior)
- [x] 12 rutas: home, library, playlist, queue, nowplaying, sync, synced, search, remote, audio-route, diagnostics, settings

### AudioController — refactorizado

- [x] `getAudioController()` reemplazado por `rememberAudioController()` en todos los archivos
- [x] `AudioController` inyectado vía `koinInject()`
- [x] Ya no es nullable: `controller.state.collectAsState()` directo
- [x] Todos los componentes usan `controller.playQueue()`, `controller.play()`, etc. sin `?.`
- [x] MainActivity ya no inicia servicio directamente (AudioController lo hace on-demand)

### Local Playback

- [x] MediaStore reader with MediaQueryDispatcher (cursor null safe)
- [x] LocalMediaRepository with 30s TTL caching
- [x] ExoPlayer via Media3 MediaLibraryService
- [x] AudioController (IPC via MediaController)
- [x] PlayerController (direct ExoPlayer access)
- [x] ReplayGainAudioProcessor (PCM-level gain)
- [x] PlaybackStateStore (SharedPreferences)
- [x] MiniPlayer con progreso y controles reales
- [x] NowPlayingScreen con seek, carátula, play/pause/next/prev reales

### CoverFlow

- [x] DiscreteScrollView via AndroidView
- [x] AlbumCoverAdapter con `submitList()` para actualización reactiva
- [x] MichiCoverTransformer (60° rotation, 1.05 center scale, 0.45 min scale)
- [x] selectedIndex corregido si albums.size cambia
- [x] Placeholder drawable + fallback colors por hash de album.id
- [x] Coil 3 sin API `toImage()` ni `fallback(Int)` — usa `setBackgroundColor` previo

### Sync (Michi Protocol)

- [x] UDP multicast discovery (224.0.0.167:53318)
- [x] Legacy `/api/register` flow via `registerLegacy()`
- [x] Nuevo pairing: `discoveryInfo()`, `pairStart()`, `pairConfirm()`
- [x] `authRequired` en AnnounceMessage/DiscoveredPeer
- [x] `SearchResponse` model (KDE: `{"results": [...], "query": "..."}`)
- [x] `X-Michi-Device-Id` header en requests
- [x] `fetchDeltaManifest(deviceId, since)` endpoint
- [x] SyncCredentialsStore persistente (UUID, token, server base URL)
- [x] Reconexión automática al abrir app si hay sesión guardada
- [x] SyncTransferManager with parallel downloads
- [x] CoverCache
- [x] SyncWorker (WorkManager foreground con notificación)
- [x] SyncScreen: PAIRING_REQUIRED, PAIRING, PAIRING_REQUIRED con formulario

### Remote Control

- [x] RemoteApiClient (OkHttp) with `/api/player/*` endpoints
- [x] RemoteViewModel with 2-second polling
- [x] Auto-config desde SyncSession (peer.ip + puerto 8124)
- [x] Remote URL/token manual en Settings como override

### Theme

- [x] Dark-only Material 3 theme
- [x] Color tokens: AccentCoral, GlassBg, GlassBorder, SurfaceDark, AccentPink, TextPrimary, etc.
- [x] GlassCard component (14dp radius, translucent)
- [x] GlowPlayButton component
- [x] TrackRow component with active highlight
- [x] MichiCoverTransformer (60° rotation, 1.05 center scale)

### Testing

- [x] `core/src/test/` — 5 tests de serialización de modelos Sync
  - RegisterRequest snake_case
  - AnnounceMessage auth_required
  - PairStartRequest snake_case
  - SearchResponse parsing
  - Track defaults deserialization
- [x] `docs/TESTING.md` — checklist de pruebas manuales

## Known Issues / Pendings

1. **NowPlayingScreen: volumen no conectado** — El slider de volumen usa estado local. Media3/ExoPlayer no expone control de volumen por sesión.
2. **NowPlayingScreen: SmokedGlassBottomBar redundante** — La barra inferior flotante dentro de NowPlayingScreen duplica la navegación que ya está en el Scaffold.
3. **Sync: auto-conexión al primer peer** — `startDiscovery()` se conecta automáticamente al encontrar el primer peer sin dar opción a elegir.
4. **Tests sin runner** — Los tests en `core/src/test/` no tienen dependencia `kotlin.test` o `junit` en `core/build.gradle.kts`. No se ejecutan automáticamente.
5. **STATUS_REPORT desactualizado** — (ya no, se actualizó ahora).
6. **Pruebas en dispositivo real** — No se ha instalado ni probado en un teléfono.

## Commands Used

```bash
./gradlew :core:compileDebugKotlin
./gradlew :data:compileDebugKotlin
./gradlew :sync-client:compileDebugKotlin
./gradlew :player:compileDebugKotlin
./gradlew :remote:compileDebugKotlin
./gradlew :app:compileNormalDebugKotlin
./gradlew assembleNormalDebug
```

## Files Modified (this session)

| File | Change |
|------|--------|
| `app/.../ui/AudioControllerHelper.kt` | Reescrita: `rememberAudioController()` vía `koinInject()` |
| `app/.../navigation/NavGraph.kt` | `rememberAudioController()` no nullable, bottomBar oculta en NowPlaying, MiniPlayer oculto en NowPlaying, ruta diagnostics + queue |
| `app/.../ui/components/MiniPlayer.kt` | `rememberAudioController()` no nullable, `controller.` directo |
| `app/.../ui/screens/HomeScreen.kt` | `rememberAudioController()`, `controller.` directo |
| `app/.../ui/screens/AlbumsScreen.kt` | `rememberAudioController()`, selectedIndex safe |
| `app/.../ui/screens/SearchScreen.kt` | `rememberAudioController()`, `controller.` directo |
| `app/.../ui/screens/PlaylistScreen.kt` | `rememberAudioController()`, `controller.` directo |
| `app/.../ui/screens/SyncedTracksScreen.kt` | `rememberAudioController()`, `controller.` directo |
| `app/.../ui/screens/DiagnosticsScreen.kt` | Nuevo — pantalla de diagnóstico |
| `app/.../ui/screens/QueueScreen.kt` | Nuevo — cola de reproducción real |
| `app/.../ui/coverflow/MichiCoverFlowHost.kt` | adapter con `submitList()`, `update` block, selectedIndex safe |
| `app/.../library/coverflow/AlbumCoverAdapter.kt` | `submitList()`, adapter reutilizable |
| `sync-client/.../MichiSyncClient.kt` | `fetchDeltaManifest(deviceId, since)` |
| `app/.../MainActivity.kt` | Sin `startService`, sin `onResume` con service start |
| `docs/STATUS_REPORT.md` | Actualizado |

## Next Phase Recommended

```
Probar la app en dispositivo real siguiendo docs/TESTING.md.
Corregir bugs que aparezcan en la prueba manual.
```
