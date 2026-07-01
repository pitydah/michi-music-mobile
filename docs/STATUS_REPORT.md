# Michi Music Mobile — Status Report

## Build

./gradlew assembleDebug: BUILD SUCCESSFUL

## Errores corregidos

1. **getAudioController() → koinInject()**: Reemplazado en 7 archivos. El helper `AudioControllerHelper.kt` se mantiene como respaldo pero ya no se usa.
2. **koinInject() en lambdas no-composable**: Corregido en AlbumsScreen, SearchScreen, SyncedTracksScreen. Ahora se inyecta al inicio del @Composable.
3. **HomeScreen sin loadMedia()**: Agregado `LaunchedEffect(Unit) { viewModel.loadMedia() }`.
4. **controller?. redundante**: Reemplazado por llamadas directas (controller nunca es null).
5. **PlaylistsScreen duplicada**: Renombrada a QueueScreen. PlaylistScreen real creada con PlaylistsViewModel y PlaylistRepository.
6. **PlaylistRepository no registrado en Koin**: Agregado en DataModule.kt.
7. **PlaylistsViewModel no registrado**: Agregado en AppModule.kt.

## Pantallas existentes (navegables desde MichiNavHost)

- home — HomeScreen (reproducción rápida, lista de canciones)
- library — AlbumsScreen (cover flow + tracks)
- nowplaying — NowPlayingScreen
- playlist — PlaylistScreen (playlists reales desde repositorio)
- queue — QueueScreen (cola actual de reproducción)
- remote — RemoteScreen (control remoto)
- sync — SyncScreen (pairing + descarga)
- synced — SyncedTracksScreen (canciones sincronizadas con paginación)
- search — SearchScreen (búsqueda local)
- settings — SettingsScreen
- diagnostics — DiagnosticsScreen
- audio-route — AudioRouteScreen

## Reproducción local

- AudioController usa Media3 ExoPlayer via MediaController + MichiPlaybackService
- playQueue(List<Track>, startIndex) está implementado
- MiniPlayer muestra estado real con polling vía StateFlow
- HomeScreen carga canciones desde AlbumsViewModel -> LocalMediaRepository.loadAlbums()
- AlbumsScreen reproduce álbum desde track seleccionado
- SearchScreen reproduce resultado seleccionado

## Sync con Michi KDE

- LinkClient usa sessionToken + deviceToken + clientDeviceId
- Envía Authorization Bearer y X-Michi-Device-Id
- SyncWorker persiste credenciales
- SyncScreen tiene UI de pairing completa

## Pendientes para probar en teléfono real

- Reproducción local con archivos reales
- CoverFlow en AlbumsScreen
- NowPlayingScreen seek/progress
- Sync pairing con servidor KDE
- Descarga de tracks sincronizados
- Playlists desde servidor
