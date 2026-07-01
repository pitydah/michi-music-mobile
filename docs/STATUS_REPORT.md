# STATUS REPORT — Michi Music Mobile

**Date:** 2026-07-01
**Version:** 0.1.0-alpha
**Branch:** main

## Build Status

- `./gradlew assembleNormalDebug`: **SUCCESS**
- APK: `app/build/outputs/apk/normal/debug/app-normal-debug.apk`
- Platform: ARM64 (aarch64) with QEMU x86_64 binfmt

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

### Screens (10/10)

- [x] HomeScreen — search bar, quick play/shuffle cards, track list
- [x] AlbumsScreen — CoverFlow carousel + album tracks
- [x] NowPlayingScreen — glassmorphism design, mock data, source selector
- [x] PlaylistScreen — all tracks with active-track highlight
- [x] SearchScreen — search local + synced tracks
- [x] SyncScreen — discovery, pairing, registration, download
- [x] SyncedTracksScreen — downloaded tracks list
- [x] RemoteScreen — KDE remote control
- [x] SettingsScreen — ReplayGain, auto-sync, remote URL/token
- [x] AudioRouteScreen — USB/Bluetooth/Local detection

### Navigation

- [x] MichiNavHost with 6-tab NavigationBar
- [x] Bottom nav: Inicio, Biblioteca, Reproduciendo, Control, Sync, Ajustes
- [x] MiniPlayer shown conditionally when track is active
- [x] All routes registered: home, library, playlist, nowplaying, sync, synced, search, remote, audio-route, settings

### Local Playback

- [x] MediaStore reader with MediaQueryDispatcher
- [x] LocalMediaRepository with 30s TTL caching
- [x] ExoPlayer via Media3 MediaLibraryService
- [x] AudioController (IPC via MediaController)
- [x] PlayerController (direct ExoPlayer access)
- [x] ReplayGainAudioProcessor (PCM-level gain)
- [x] PlaybackStateStore (SharedPreferences)

### Sync (Michi Protocol)

- [x] UDP multicast discovery (224.0.0.167:53318)
- [x] Legacy /api/register flow
- [x] Future-proof: DiscoveryInfoResponse, pairStart/pairConfirm models
- [x] authRequired field in AnnounceMessage/DiscoveredPeer
- [x] SearchResponse model (snake_case)
- [x] X-Michi-Device-Id header support
- [x] SyncTransferManager with parallel downloads
- [x] CoverCache
- [x] SyncWorker (WorkManager foreground)

### Remote Control

- [x] RemoteApiClient (OkHttp) with /api/player/* endpoints
- [x] RemoteViewModel with 2-second polling
- [x] Remote URL/token configurable in Settings

### Theme

- [x] Dark-only Material 3 theme
- [x] Color tokens: SurfaceDark (#090B11), AccentPink, TextPrimary, etc.
- [x] GlassCard component (14dp radius, translucent)
- [x] GlowPlayButton component
- [x] TrackRow component with active highlight
- [x] MichiCoverTransformer (60° rotation, 1.05 center scale)

## Known Issues / Pendings

1. **NowPlayingScreen uses hardcoded mock data** — it does not yet observe the real AudioController state. The visual design is final but data integration remains.
2. **Pairing UI** — The pair/start and pair/confirm endpoints are defined in models and client but not yet wired in the UI. The KDE server also lacks these endpoints (future feature).
3. **NowPlayingScreen private colors** — Has its own color definitions which shadow the theme colors.
4. **SyncedTracksScreen uses getAudioController** — Works but could be cleaner with koinInject.
5. **No automated tests yet** — Only manual testing guide exists.
6. **AlbumCoverViewHolder Coil 3 fallback** — Uses no explicit fallback drawable (Coil 3 API changed, fallback with Int resource removed). Works but no placeholder.
7. **MiniPlayer progress bar** — Uses LinearProgressIndicator which works but the custom track color may be ignored in Material 3.

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

## Files Modified

| File | Change |
|------|--------|
| `core/.../models/SyncModels.kt` | Added DiscoveryInfoResponse, PairStart/Confirm models, SearchResponse, authRequired fields |
| `sync-client/.../MichiSyncClient.kt` | Added discoveryInfo, pairStart, pairConfirm, registerLegacy. Removed old register. Added deviceId field. Updated search return type. |
| `sync-client/.../DiscoveryClient.kt` | Parse authRequired from AnnounceMessage → DiscoveredPeer |
| `app/.../MainActivity.kt` | Moved startService to onResume, removed from onCreate |
| `app/.../navigation/NavGraph.kt` | MiniPlayer shown only when currentTrack != null, code cleanup |
| `app/.../ui/screens/HomeScreen.kt` | Added empty state message |
| `app/.../ui/screens/SyncScreen.kt` | Added PairingRequiredState, PAIRING_REQUIRED/PAIRING/SYNCING branches |
| `app/.../ui/screens/SettingsScreen.kt` | Added Remote URL/token fields |
| `app/.../sync/SyncViewModel.kt` | Use registerLegacy, client.deviceId, handle authRequired |
| `app/.../library/coverflow/AlbumCoverViewHolder.kt` | Fixed Coil 3 asImage deprecation, fixed album.id string-safe fallback color |
| `docs/TESTING.md` | Created |
| `README.md` | Updated version numbers, added port reference, modules, testing link |

## Next Steps / Recommended Prompt

```
Continuar con la integración de datos reales en NowPlayingScreen:
- Usar koinInject para AudioController
- Reemplazar mock data por observación de PlayerState
- Usar colores del theme en lugar de privados
- Conectar source selector con SyncSession si hay servidores
```
