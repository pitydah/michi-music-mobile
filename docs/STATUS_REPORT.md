# STATUS REPORT — Michi Music Mobile

**Date:** 2026-07-01
**Version:** 0.1.0-alpha
**Branch:** main
**Commit HEAD:** `539a2d3`

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

## AudioController — lazy init

- `init` block: **eliminado** (no existe)
- `ensureConnected()`: **12 llamadas** desde playQueue, play, pause, seek, skipNext/Previous, etc.
- Inyectar AudioController **no inicia Media3/ExoPlayer/MichipebackService**
- `StateFlow<PlayerState>` disponible desde la construcción sin conexión
- MiniPlayer puede observar `state` sin forzar conexión

## Navegación — MichiNavHost

13 rutas, todas con screens existentes:

| Ruta | Screen | Bottom nav |
|------|--------|------------|
| home | HomeScreen | ✅ Inicio |
| library | AlbumsScreen | ✅ Biblioteca |
| playlist | PlaylistScreen | ❌ interna |
| playlists | PlaylistsScreen | ❌ interna |
| queue | QueueScreen | ❌ interna |
| nowplaying | NowPlayingScreen | ✅ Reproduciendo |
| sync | SyncScreen | ✅ Sync |
| synced | SyncedTracksScreen | ❌ interna |
| search | SearchScreen | ❌ interna |
| remote | RemoteScreen | ✅ Control |
| audio-route | AudioRouteScreen | ❌ interna |
| diagnostics | DiagnosticsScreen | ❌ interna |
| settings | SettingsScreen | ✅ Ajustes |

Bottom nav: 6 items con `MichiBottomNavigation` (smoked glass), no `NavigationBar` de Material3.
MiniPlayer: oculto en NowPlaying, visible solo con `currentTrack != null`.

## Design System

**Tokens**: `ui/theme/MichiTokens.kt` (49 líneas) — MichiRadius, MichiSpacing, MichiAlpha, MichiSize, MichiAnimation

**Componentes** (9 en `ui/components/`):
- `MichiBackground` — fondo gradiente (no usado actualmente)
- `MichiScreen` — wrapper con padding (no usado actualmente)
- `MichiBottomNavigation` — smoked glass nav bar
- `MichiEmptyState` — icono + título + descripción + acción
- `MichiLoadingState` — spinner + texto
- `MichiSectionHeader` — título + subtítulo + acción
- `MichiActionButton` — PRIMARY_GLOW / SECONDARY_GLASS
- `MichiSlider` — progreso/volumen con colores Michi
- `MichiIconButton` — botón icono compacto

Extras: `GlassCard` (con variantes COMPACT/NORMAL/STRONG), `TrackRow`, `MiniPlayer`, `GlowPlayButton`

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
