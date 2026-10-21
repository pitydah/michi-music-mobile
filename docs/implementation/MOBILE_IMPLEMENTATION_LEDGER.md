# MOBILE_IMPLEMENTATION_LEDGER.md — Michi Music Mobile

## Registro de Fases de Implementación

| Fase | Estado código | Tests JVM | Tests Android | Hardware | SHA | Deuda / dependencia |
|---|---|---|---|---|---|---|
| M0 | VERIFIED | VERIFIED | PENDING_ENV | N/A | 45ed692a | Rescate 13 commits playlists, DI validado, DAO y mutex atómico, UI Error state y Main.immediate |
| M1 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | deae6e64 | Autoridad única Media3, LocalPlaybackGateway, PlaybackStatus/ErrorMapper, recuperación de pistas faltantes |
| M2 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | bb2c3fc3 | Medios locales tipados MediaResult, SAF SafMediaRepository, ReplayGain SeekableAudioSource/ContentResolver |
| M3 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | ef003543 | USB DAC Route Truth, OutputDescriptor, AudioRouteObserver, hotplug auto-pausa y UI veraz |
| M4 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | Hi-Res, DSP/Transparente, Signal Truth |
| M5 | NOT_STARTED | NOT_STARTED | NOT_STARTED | N/A | - | Multi-Library Room v3->v4 y dominio relacional |
| M6 | NOT_STARTED | NOT_STARTED | NOT_STARTED | N/A | - | UI Multi-Library, favoritos, búsqueda |
| M7 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | Michi Link identidad, Keystore recovery, Home trust |
| M8 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | Michi Sync resumable downloads y profiles |
| M9 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | AirCat Mobile consumer contract y Devices UI |
| M10 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | Catálogos remotos y ReceiverLite Stream |
| M11 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | UI Premium, Settings jerárquico, Android Auto |
| M12 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | CI, seguridad, certificación y release |
| M13 | NOT_STARTED | NOT_STARTED | NOT_STARTED | N/A | - | IA musical optativa |

---

## Checkpoint de Ejecución — Bloque D (M3)

```text
BLOCK_ID: M3
MASTER_BASE_SHA: 5ef3d21ad373ec56aee0e5015b3cba14ca6cf716
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/ui/components/DialogComponents.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/AudioRouteScreen.kt
  - player/src/main/java/org/michimusic/player/AudioRouteModels.kt
  - player/src/main/java/org/michimusic/player/AudioRouteObserver.kt
  - player/src/main/java/org/michimusic/player/PlayerController.kt
  - player/src/main/java/org/michimusic/player/UsbDacManager.kt
  - player/src/test/java/org/michimusic/player/UsbDacManagerTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  UsbDacManager y la UI asumían optimismo ciego: un dongle USB conectado se catalogaba como "DAC USB DIRECTO"
  y salida actual activa sin confirmación del hardware/AudioTrack ni de Media3; no existía desacoplamiento
  de IDs efímeros de Android ni máquina de estados de ruta; la desconexión abrupta del USB o auriculares
  no pausaba la reproducción deterministamente, pudiendo filtrar audio no deseado por el altavoz integrado.
PRODUCTION_BEHAVIOR_AFTER:
  Se implementó AudioRouteStatus (NoDevice, Detected, Requested, Confirmed, Failed, Unknown) y OutputDescriptor
  desacoplado de IDs volátiles; UsbDacManager opera mediante AudioDeviceSource inyectable y notifica eventos
  de desconexión hotplug; PlayerController intercepta desconexión de USB mientras isPlaying y pausa de inmediato;
  AudioRouteObserver se conecta a AudioTrack (vía AudioTrackProvider) para reportar enrutamiento efectivo;
  DialogComponents y AudioRouteScreen exhiben estado veraz y frecuencias declaradas sin falsear bit-perfect.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 322 actionable tasks ejecutadas en :player, :app, :data, :core, :michi-link-client)
  UsbDacManagerTest: initialState_hasFallbackSpeakerWhenNoManager, selectDevice_updatesSelectedDeviceId,
  clearDeviceSelection_resetsToNull, release_doesNotThrow, usbDetectedNotRoutedTest, usbDisconnectPausesTest,
  usbReplugNewIdTest, twoDacsChooseRequestedTest, unknownRouteDoesNotClaimConfirmedTest, routeCallbackReleasedTest.
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (requiere dispositivo Android físico y al menos 2 dongles DAC USB reales)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  No se exponen datos de usuario ni recursos de hardware no autorizados; desuscripción y liberación de callbacks
  en UsbDacManager y PlayerController verificada para prevenir fugas de memoria (memory leaks) de AudioDeviceCallback.
ROLLBACK_PLAN:
  git reset --hard 5ef3d21ad373ec56aee0e5015b3cba14ca6cf716
BLOCKERS: Ninguno.
NEXT_BLOCK: M4 (Hi-Res, DSP/Transparente, ReplayGain y Signal Truth)
```

---

## Checkpoint de Ejecución — Bloque A (M0)

```text
BLOCK_ID: M0
MASTER_BASE_SHA: bab314cc37ce07dd22af49baecf56f2503b3799c
MASTER_FINAL_SHA: 45ed692a05ed1af7d64fe420ad0c022df8517790
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/screens/PlaylistsViewModel.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/PlaylistDetailScreen.kt
  - app/src/test/java/org/michimusic/mobile/screens/PlaylistsViewModelTest.kt
  - data/src/main/java/org/michimusic/data/cache/PlaylistDao.kt
  - data/src/main/java/org/michimusic/data/repository/PlaylistRepository.kt
  - data/src/test/java/org/michimusic/data/repository/PlaylistRepositoryTest.kt
  - docs/implementation/MOBILE_BASELINE_AUDIT.md
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  Playlists usaban INSERT OR REPLACE compitiendo en ediciones concurrentes; createPlaylist no normalizaba
  trackIds vacíos ni duplicados distantes; refresco de detalle convertía errores recuperables en NotFound;
  callbacks de navegación/diálogo se ejecutaban en Dispatchers.IO.
PRODUCTION_BEHAVIOR_AFTER:
  createPlaylist normaliza IDs (trim, isNotBlank, distinct, conserva orden) y sincroniza trackCount;
  ediciones usan Mutex y UPDATE targeted (updateTrackIdsAndCount); PlaylistDetailUiState modela estado Error
  con reintento; callbacks UI despachan en Dispatchers.Main.immediate.
UNIT_TESTS_EXECUTED:
  :core:testDebugUnitTest (PASSED)
  :data:testDebugUnitTest (PASSED)
  :player:testDebugUnitTest (PASSED)
  :michi-link-client:testDebugUnitTest (PASSED)
  :app:testNormalDebugUnitTest (PASSED)
  :app:testFdroidDebugUnitTest (PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (no hay emulador/dispositivo conectado en sesión JVM)
HARDWARE_TESTS_EXECUTED: N/A
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Commit sensible b8d288bb aislado de M0 para implementación rigurosa de recuperación en M7.
  Sin filtración de credenciales en logs o persistencia.
ROLLBACK_PLAN:
  git reset --hard mobile-baseline-20261020-bab314cc (tag de respaldo local confirmado)
BLOCKERS: Ninguno para M0.
NEXT_BLOCK: M1 (Consolidar autoridad única Media3, colas persistentes y recuperación en :player)
```

---

## Checkpoint de Ejecución — Bloque B (M1)

```text
BLOCK_ID: M1
MASTER_BASE_SHA: 45ed692a05ed1af7d64fe420ad0c022df8517790
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/MichiApp.kt
  - player/src/main/java/org/michimusic/player/AudioController.kt
  - player/src/main/java/org/michimusic/player/LibraryProvider.kt
  - player/src/main/java/org/michimusic/player/LocalPlaybackGateway.kt
  - player/src/main/java/org/michimusic/player/MichiMediaLibrarySessionCallback.kt
  - player/src/main/java/org/michimusic/player/MichiPlaybackService.kt
  - player/src/main/java/org/michimusic/player/PlaybackErrorMapper.kt
  - player/src/main/java/org/michimusic/player/PlaybackStatus.kt
  - player/src/main/java/org/michimusic/player/PlayerController.kt
  - player/src/main/java/org/michimusic/player/PlayerDependencies.kt
  - player/src/main/java/org/michimusic/player/PlayerState.kt
  - player/src/test/java/org/michimusic/player/MissingTrackRecoveryTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  AudioController y PlayerController usaban estado optimista isPlaying = true antes de la confirmación
  de ExoPlayer/Media3; onPlaybackResumption devolvía SettableFuture síncrono que podía fallar en cold-start;
  LibraryProvider solo buscaba en MediaStore ignorando pistas descargadas/sincronizadas; errores de reproducción
  no tipados; ExoPlayer se detenía/quedaba bloqueado al topar con archivos faltantes o corruptos.
PRODUCTION_BEHAVIOR_AFTER:
  LocalPlaybackGateway define contrato estricto de reproducción local desacoplado; PlaybackStatus y
  PlaybackErrorMapper proporcionan estados tipados (Preparing, Buffering, Playing, Paused, Ended, Error);
  AudioController opera de manera no-optimista esperando confirmación de ExoPlayer; LibraryProvider resuelve
  pistas locales y remotas cacheadas mediante TrackDao; MichiPlaybackService avanza automáticamente al siguiente
  elemento ante errores de IO/corrupción para evitar estancamiento; onPlaybackResumption resuelve en CoroutineScope.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 322 actionable tasks ejecutadas en :player, :data, :app, :michi-link-client, :core)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (entorno headless sin emulador físico/virtual conectado)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (requiere dispositivo Android físico para pruebas de audio en background)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  No se exponen credenciales ni URIs no autorizadas; fallback seguro en resolución de pistas.
ROLLBACK_PLAN:
  git revert al commit de M1 o reset al SHA 45ed692a05ed1af7d64fe420ad0c022df8517790
BLOCKERS: Ninguno.
NEXT_BLOCK: M2 (Capa de medios locales, SAF multi-directorio y ReplayGain offline/online)
```

---

## Checkpoint de Ejecución — Bloque C (M2)

```text
BLOCK_ID: M2
MASTER_BASE_SHA: d08ab3b19c23565f42dfb987b243b9df7072db43
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/ui/screens/SettingsScreen.kt
  - data/src/main/java/org/michimusic/data/DataModule.kt
  - data/src/main/java/org/michimusic/data/local/MediaQueryDispatcher.kt
  - data/src/main/java/org/michimusic/data/local/ReplayGainReader.kt
  - data/src/main/java/org/michimusic/data/local/SeekableAudioSource.kt
  - data/src/main/java/org/michimusic/data/repository/LocalMediaRepository.kt
  - data/src/main/java/org/michimusic/data/repository/MediaResult.kt
  - data/src/main/java/org/michimusic/data/repository/SafMediaRepository.kt
  - data/src/test/java/org/michimusic/data/local/ReplayGainContentResolverTest.kt
  - data/src/test/java/org/michimusic/data/repository/LocalMediaRepositoryCacheTest.kt
  - data/src/test/java/org/michimusic/data/repository/SafMediaRepositoryTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  ReplayGainReader dependía exclusivamente de RandomAccessFile con File(path), fallando para URIs content://
  de MediaStore en Android 10+ y SAF; no existía abstracción seekable ni protección contra etiquetas
  desmesuradas/malformadas; queryTracks usaba convenciones nullable no tipadas; no existía integración SAF
  verdadera con persistencia de permisos para importar música externa.
PRODUCTION_BEHAVIOR_AFTER:
  ReplayGainReader lee de forma segura mediante SeekableAudioSource (File, ContentUri o ByteArray) con límites
  de seguridad contra OOM por tags gigantescos; LocalMediaRepository y MediaQueryDispatcher devuelven
  MediaResult tipado (Success, PermissionDenied, QueryFailed) impidiendo cacheo de errores; SafMediaRepository
  gestiona persistencia de permisos SAF y metadatos; SettingsScreen integra selector OpenMultipleDocuments.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 322 actionable tasks ejecutadas en :data, :app, :player, :core, :michi-link-client)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (requiere dispositivo para pruebas de almacenamiento extraíble y SAF real)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  takePersistableUriPermission manejado con captura segura de SecurityException; límites de memoria de 16MB/256KB
  en parseo de etiquetas para prevenir ataques de denegación de servicio por archivos de audio malformados.
ROLLBACK_PLAN:
  git reset --hard d08ab3b19c23565f42dfb987b243b9df7072db43
BLOCKERS: Ninguno.
NEXT_BLOCK: M3 (Dongles DAC USB: Route Truth, preferencia efectiva y recuperación)
```
| M4 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | Hi-Res, DSP/Transparente, Signal Truth |
| M5 | NOT_STARTED | NOT_STARTED | NOT_STARTED | N/A | - | Multi-Library Room v3->v4 y dominio relacional |
| M6 | NOT_STARTED | NOT_STARTED | NOT_STARTED | N/A | - | UI Multi-Library, favoritos, búsqueda |
| M7 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | Michi Link identidad, Keystore recovery, Home trust |
| M8 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | Michi Sync resumable downloads y profiles |
| M9 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | AirCat Mobile consumer contract y Devices UI |
| M10 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | Catálogos remotos y ReceiverLite Stream |
| M11 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | UI Premium, Settings jerárquico, Android Auto |
| M12 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | CI, seguridad, certificación y release |
| M13 | NOT_STARTED | NOT_STARTED | NOT_STARTED | N/A | - | IA musical optativa |

---

## Checkpoint de Ejecución — Bloque A (M0)

```text
BLOCK_ID: M0
MASTER_BASE_SHA: bab314cc37ce07dd22af49baecf56f2503b3799c
MASTER_FINAL_SHA: 45ed692a05ed1af7d64fe420ad0c022df8517790
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/screens/PlaylistsViewModel.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/PlaylistDetailScreen.kt
  - app/src/test/java/org/michimusic/mobile/screens/PlaylistsViewModelTest.kt
  - data/src/main/java/org/michimusic/data/cache/PlaylistDao.kt
  - data/src/main/java/org/michimusic/data/repository/PlaylistRepository.kt
  - data/src/test/java/org/michimusic/data/repository/PlaylistRepositoryTest.kt
  - docs/implementation/MOBILE_BASELINE_AUDIT.md
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  Playlists usaban INSERT OR REPLACE compitiendo en ediciones concurrentes; createPlaylist no normalizaba
  trackIds vacíos ni duplicados distantes; refresco de detalle convertía errores recuperables en NotFound;
  callbacks de navegación/diálogo se ejecutaban en Dispatchers.IO.
PRODUCTION_BEHAVIOR_AFTER:
  createPlaylist normaliza IDs (trim, isNotBlank, distinct, conserva orden) y sincroniza trackCount;
  ediciones usan Mutex y UPDATE targeted (updateTrackIdsAndCount); PlaylistDetailUiState modela estado Error
  con reintento; callbacks UI despachan en Dispatchers.Main.immediate.
UNIT_TESTS_EXECUTED:
  :core:testDebugUnitTest (PASSED)
  :data:testDebugUnitTest (PASSED)
  :player:testDebugUnitTest (PASSED)
  :michi-link-client:testDebugUnitTest (PASSED)
  :app:testNormalDebugUnitTest (PASSED)
  :app:testFdroidDebugUnitTest (PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (no hay emulador/dispositivo conectado en sesión JVM)
HARDWARE_TESTS_EXECUTED: N/A
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Commit sensible b8d288bb aislado de M0 para implementación rigurosa de recuperación en M7.
  Sin filtración de credenciales en logs o persistencia.
ROLLBACK_PLAN:
  git reset --hard mobile-baseline-20261020-bab314cc (tag de respaldo local confirmado)
BLOCKERS: Ninguno para M0.
NEXT_BLOCK: M1 (Consolidar autoridad única Media3, colas persistentes y recuperación en :player)
```

---

## Checkpoint de Ejecución — Bloque B (M1)

```text
BLOCK_ID: M1
MASTER_BASE_SHA: 45ed692a05ed1af7d64fe420ad0c022df8517790
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/MichiApp.kt
  - player/src/main/java/org/michimusic/player/AudioController.kt
  - player/src/main/java/org/michimusic/player/LibraryProvider.kt
  - player/src/main/java/org/michimusic/player/LocalPlaybackGateway.kt
  - player/src/main/java/org/michimusic/player/MichiMediaLibrarySessionCallback.kt
  - player/src/main/java/org/michimusic/player/MichiPlaybackService.kt
  - player/src/main/java/org/michimusic/player/PlaybackErrorMapper.kt
  - player/src/main/java/org/michimusic/player/PlaybackStatus.kt
  - player/src/main/java/org/michimusic/player/PlayerController.kt
  - player/src/main/java/org/michimusic/player/PlayerDependencies.kt
  - player/src/main/java/org/michimusic/player/PlayerState.kt
  - player/src/test/java/org/michimusic/player/MissingTrackRecoveryTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  AudioController y PlayerController usaban estado optimista isPlaying = true antes de la confirmación
  de ExoPlayer/Media3; onPlaybackResumption devolvía SettableFuture síncrono que podía fallar en cold-start;
  LibraryProvider solo buscaba en MediaStore ignorando pistas descargadas/sincronizadas; errores de reproducción
  no tipados; ExoPlayer se detenía/quedaba bloqueado al topar con archivos faltantes o corruptos.
PRODUCTION_BEHAVIOR_AFTER:
  LocalPlaybackGateway define contrato estricto de reproducción local desacoplado; PlaybackStatus y
  PlaybackErrorMapper proporcionan estados tipados (Preparing, Buffering, Playing, Paused, Ended, Error);
  AudioController opera de manera no-optimista esperando confirmación de ExoPlayer; LibraryProvider resuelve
  pistas locales y remotas cacheadas mediante TrackDao; MichiPlaybackService avanza automáticamente al siguiente
  elemento ante errores de IO/corrupción para evitar estancamiento; onPlaybackResumption resuelve en CoroutineScope.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 322 actionable tasks ejecutadas en :player, :data, :app, :michi-link-client, :core)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (entorno headless sin emulador físico/virtual conectado)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (requiere dispositivo Android físico para pruebas de audio en background)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  No se exponen credenciales ni URIs no autorizadas; fallback seguro en resolución de pistas.
ROLLBACK_PLAN:
  git revert al commit de M1 o reset al SHA 45ed692a05ed1af7d64fe420ad0c022df8517790
BLOCKERS: Ninguno.
NEXT_BLOCK: M2 (Capa de medios locales, SAF multi-directorio y ReplayGain offline/online)
```
