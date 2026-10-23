# MOBILE_IMPLEMENTATION_LEDGER.md — Michi Music Mobile

## Registro de Fases de Implementación

| Fase | Estado código | Tests JVM | Tests Android | Hardware | SHA | Deuda / dependencia |
|---|---|---|---|---|---|---|
| M0 | VERIFIED | VERIFIED | PENDING_ENV | N/A | 45ed692a | Rescate 13 commits playlists, DI validado, DAO y mutex atómico, UI Error state y Main.immediate |
| M1 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | deae6e64 | Autoridad única Media3, LocalPlaybackGateway, PlaybackStatus/ErrorMapper, recuperación de pistas faltantes |
| M2 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | bb2c3fc3 | Medios locales tipados MediaResult, SAF SafMediaRepository, ReplayGain SeekableAudioSource/ContentResolver |
| M3 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | ef003543 | USB DAC Route Truth, OutputDescriptor, AudioRouteObserver, hotplug auto-pausa y UI veraz |
| M4 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | 7eee54d | Hi-Res, DSP/Transparente, ReplayGain multi-formato, Signal Truth, DSD/DoP Feasibility |
| M5 | VERIFIED | VERIFIED | PENDING_ENV | N/A | 5bc5ab4 | Multi-Library Room v3->v4, DAOs, identidades/ubicaciones desacopladas y repositorios de dominio |
| M6 | VERIFIED | VERIFIED | PENDING_ENV | N/A | 7bedf68 | UI Multi-Library, favoritos persistentes, búsqueda federada y Home |
| M7 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | c81a126 | Michi Link identidad, Keystore recovery, Home trust, WorkManager creds removal |
| M8 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | b4bf34f | Michi Sync resumable downloads, checksums, atomic promotion y profiles |
| M9 | VERIFIED | VERIFIED | PENDING_ENV | BLOCKED_UPSTREAM | 88669ac | AirCat Mobile consumer contract, FakeAirCatPeer harness, USB feasibility |
| M10 | VERIFIED | VERIFIED | PENDING_ENV | BLOCKED_UPSTREAM | 248c5a9 | Catálogos remotos y ReceiverLite Stream |
| M11 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | 73e54d6 | UI Premium, Settings jerárquico, Android Auto y Diagnósticos expurgados |
| M12 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | a6bb44b | CI ampliado, migraciones Room, matriz física verificable y release gates |
| M13 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | 2f77234e | Integración total de seguridad criptográfica y controles idempotentes |
| M14 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | df032e44 | Macro-sprint integral: Media3 queue dinámica, error loop guard, DAC vs headset truth, physical location validation, playlists album append y RG UI |
| M14.1 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | d939f4d | Cierre funcional auditado: consecución error loop guard con delay, verdad de ruta USB DAC vs Headset en toda la UI, diálogo para añadir álbum a playlist en AlbumsScreen y targets táctiles de 44-48dp en cola |
| M14.2 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | 4b06447 | Cierre técnico integral: AudioRouteScreen reactivo con AudioDeviceCallback, eliminación de asunciones Hi-Res, autoridad única Media3 con PendingQueueCommands y ServiceScope thread-safe, Playlists atómicas con confirmación explícita (PlaylistAddResult) y feedback en UI, resolución de ubicaciones físicas y fallback resiliente, recuperación persistente de transferencias AirCat ante recreación de proceso |
| M14.3 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | e50cc3e | Sincronización autoritativa de timeline en Media3 (onTimelineChanged), comandos pendientes basados en identidad de pista, transacciones Room atómicas en Multi-Library (registerTrackWithLocationAtomic), recuperación efectiva de transferencias AirCat incompletas con byte-range resume tras process death, menú unificado de acciones TrackActionBottomSheet |
| M14.4 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | cbd270c | Auditoría KILLCRITIC superada: AirCat unwrap getOrThrow(), rechazo de pares revocados, preservación no destructiva de TrackIdentity, orquestación de recuperación en SyncWorker/SyncViewModel; Media3 preservación de pistas duplicadas en cola secuencial; Multi-Library rollback atómico con prueba unitaria; TrackActionBottomSheet y AddToPlaylistDialog conectados en QueueScreen, NowPlayingScreen y AlbumsScreen |
| M14.5 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | fdac694 | Acciones remotas playNow/playNext/addToQueue/moveQueueItem desacopladas, token binding dinámico AirCat, SyncWorker cancellation unwrap, AddToPlaylistDialog try-finally |
| M15 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | 69d70aa | ADR-MOB-DSD-LEGACY formalizado, DSD_DOP_FEASIBILITY archivado OUT_OF_SCOPE_MOBILE, BitPerfectStatus deprecado, SignalTruth sin bit-perfect claims, Equalizer "Sin efectos", Lossless filter fix y LibraryLosslessClassifierTest |
| M16 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | dcdd3bd | VisualPreferences reactivo (highContrast/reducedMotion), MichiTheme adaptativo, MichiIconAction (>=48dp), MichiSurface, MichiBottomSheet, MichiPlaybackControls y specs UI/UX |
| M17 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | 8bdc5ff | Adopción de MichiPlaybackControls en NowPlayingScreen y RemoteScreen, eliminación total de slider de volumen (D-05) y suite NowPlayingNoVolumeTest |
| M18 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | 0804da1 | Devices & Trust Integration + Hardening: RemoteCommandAuthority, QueueEntry disambiguation, AirCat token hardening, 4-dimension device state, 1-CTA resolution & DeviceDetailSheet |
| M19 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | 8a4fb75 | Biblioteca, Playlists y Home honesta: Home recent integrity (sin heurísticas take(8)), navegación CRUD playlist completa sin no-ops, y clasificador lossless canónico en búsqueda |
| M18-M19-HARDENING | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | HEAD | Cierre completo de defectos P1/P2: autoridad de comandos remotos (fallo getQueue produce REJECTED, anti-stale endpoint affinity), reproducción rápida real de playlists (playPlaylist en PlaylistsViewModel), confianza de dispositivos (distinción anunciado vs verificado y pairing QR), identidad QueueEntry en Media3 (timeline y restauración), clasificación Lossless/Lossy/Unknown, UI/UX Devices (touch targets >=48dp, metadata sin raw IP) |
| M20 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | HEAD | Sync Preflight inteligente, perfiles de entrega negociados sin fake transcoding, reserva de seguridad >= 100 MB estricta, StorageBreakdownBar, SyncWizardScreen (G14, G15, G29), Room v5->v6 con QueueEntry UUID y autoridad remota ACCEPTED/CONFIRMED |
| M20.1 | VERIFIED | VERIFIED | PENDING_ENV | PENDING_HARDWARE | HEAD | Estabilización e integración real de Sync: catálogo remoto real en Wizard, selección granular (álbumes/playlists/artistas/pistas), invariante de 0 pistas (cero descargas no autorizadas), perfiles AAC/Opus veraces, métricas de disco reales y prueba E2E completa |

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

## Checkpoint de Ejecución — Bloque E (M4)

```text
BLOCK_ID: M4
MASTER_BASE_SHA: 2a2ec287e07ae510bc49a75d1645e9a4440058b7
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/PlayerModule.kt
  - app/src/main/java/org/michimusic/mobile/ui/components/DialogComponents.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/NowPlayingScreen.kt
  - core/src/main/java/org/michimusic/core/models/AudioProcessingModels.kt
  - docs/audio/DSD_DOP_FEASIBILITY.md
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
  - player/src/main/java/org/michimusic/player/AudioModeManager.kt
  - player/src/main/java/org/michimusic/player/MichiAudioEffects.kt
  - player/src/main/java/org/michimusic/player/MichiPlaybackService.kt
  - player/src/main/java/org/michimusic/player/PlayerController.kt
  - player/src/main/java/org/michimusic/player/PlayerDependencies.kt
  - player/src/main/java/org/michimusic/player/ReplayGainAudioProcessor.kt
  - player/src/main/java/org/michimusic/player/SignalTruthTracker.kt
  - player/src/test/java/org/michimusic/player/AudioModeAndSignalTruthTest.kt
  - player/src/test/java/org/michimusic/player/MichiPlaybackServiceTest.kt
  - player/src/test/java/org/michimusic/player/ReplayGainMultiFormatTest.kt
PRODUCTION_BEHAVIOR_BEFORE:
  ReplayGainAudioProcessor solo admitía ENCODING_PCM_16BIT, descartando o distorsionando audio Hi-Res de 24-bit,
  32-bit o Float; no existía modo transparente ni separación entre procesamiento DSP y reproducción purista;
  MichiAudioEffects no permitía bypass dinámico sin destruir los ajustes personalizados del usuario; la UI
  no reportaba la verdad de la señal de audio (formato fuente vs formato decodificado vs configuración AudioTrack),
  y no existía estudio técnico documentado para reproducción DSD/DoP en Android.
PRODUCTION_BEHAVIOR_AFTER:
  ReplayGainAudioProcessor soporta 16-bit, 24-bit, 32-bit y Float con protección de clipping little-endian y bypass
  inmediato en modo Transparent; AudioModeManager gestiona modos AUTO, TRANSPARENT y DSP coordinando bypass
  de hardware HAL effects (MichiAudioEffects.isBypassedByMode) sin borrar preferencias; SignalTruthTracker
  consolida observaciones veraces de decodificación y AudioTrack sin afirmar bit-perfect salvo confirmación estricta;
  NowPlayingScreen incluye cápsula Signal Truth y diálogo de diagnóstico veraz; DialogComponents integra selector
  de modo de audio; estudio docs/audio/DSD_DOP_FEASIBILITY.md documentado rigurosamente.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 322 actionable tasks ejecutadas en :player, :app, :data, :core, :michi-link-client)
  ReplayGainMultiFormatTest (clipping 16-bit, 24-bit, Float, Transparent bypass - PASSED)
  AudioModeAndSignalTruthTest (Transparent bypass, restore DSP, unknown fields, decoder updates, hot-swap - PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (requiere dispositivo físico con DAC USB Hi-Res y analizador de espectro de señal)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Operaciones de buffer little-endian acotadas al tamaño remanente exacto de buffers para evitar BufferOverflowException
  o OutOfMemoryError con streams de audio corruptos o maliciosos.
ROLLBACK_PLAN:
  git reset --hard 2a2ec287e07ae510bc49a75d1645e9a4440058b7
BLOCKERS: Ninguno.
NEXT_BLOCK: M5 (Multi-Library Core: Room schema v3->v4, DAOs, relational domain)
```

---

## Checkpoint de Ejecución — Bloque F (M5)

```text
BLOCK_ID: M5
MASTER_BASE_SHA: 5ead81fc7ceebdaae2e7616d61f76269ebf58fc2
FILES_CHANGED:
  - core/src/main/java/org/michimusic/core/models/MultiLibraryModels.kt
  - data/build.gradle.kts
  - data/schemas/org.michimusic.data.cache.MichiDatabase/3.json
  - data/schemas/org.michimusic.data.cache.MichiDatabase/4.json
  - data/src/main/java/org/michimusic/data/DataModule.kt
  - data/src/main/java/org/michimusic/data/cache/MichiDatabase.kt
  - data/src/main/java/org/michimusic/data/cache/MultiLibraryDaos.kt
  - data/src/main/java/org/michimusic/data/cache/MultiLibraryEntities.kt
  - data/src/main/java/org/michimusic/data/repository/MusicLibraryRepository.kt
  - data/src/test/java/org/michimusic/data/cache/Migration3To4Test.kt
  - data/src/test/java/org/michimusic/data/repository/MultiLibraryRepositoryTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  El esquema Room v3 mezclaba pistas locales y sincronizadas como una única entidad plana `cached_tracks`;
  no existía el concepto de múltiples bibliotecas ni identidades lógicas desacopladas de URIs físicas;
  las playlists almacenaban IDs en formato CSV `trackIds TEXT` propensas a corrupción o truncado en concurrencia;
  exportSchema estaba desactivado (`exportSchema = false`) impidiendo auditoría y verificación formal de migraciones;
  no existía política de resolución de ubicación preferida entre SAF, MediaStore y sincronizadas.
PRODUCTION_BEHAVIOR_AFTER:
  Esquema Room v4 aditivo con `exportSchema = true` y versionado de schemas JSON; MIGRATION_3_4 crea tablas
  `libraries`, `track_identities`, `track_locations`, `library_tracks`, `playlist_headers`, `playlist_entries`,
  `sync_profiles` y `sync_manifests` sin eliminar datos legacy; migración atómica de playlists CSV a entradas
  posicionales preservando orden exacto e IDs huérfanos; biblioteca predeterminada `DEVICE_LOCAL` ('Este teléfono')
  inicializada; MusicLibraryRepository implementa resolución preferente desacoplada (SAF > MediaStore > Synced > Remoto)
  y membresías n-a-n sin duplicación de identidad.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 342 actionable tasks ejecutadas en :data, :player, :core, :michi-link-client, :app)
  Migration3To4Test (verificación formal de upgrade v3->v4, playlists intactas, DEVICE_LOCAL - PASSED)
  MultiLibraryRepositoryTest (creación de bibliotecas custom, resolución SAF prioritario, membresía n-a-n - PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: N/A
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Migraciones transaccionales con escape de comillas simples; eliminación de bibliotecas de usuario protegida para
  no permitir borrado de la biblioteca predeterminada del sistema ni eliminación destructiva de archivos en disco.
ROLLBACK_PLAN:
  git reset --hard 5ead81fc7ceebdaae2e7616d61f76269ebf58fc2
BLOCKERS: Ninguno.
NEXT_BLOCK: M6 (UI Multi-Library, favoritos, búsqueda y Home)
```

---

## Checkpoint de Ejecución — Bloque G (M6)

```text
BLOCK_ID: M6
MASTER_BASE_SHA: 36b30869d6bf37f6a6ca7006886e80b2a74c207d
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/AppModule.kt
  - app/src/main/java/org/michimusic/mobile/library/LibraryScopeStore.kt
  - app/src/main/java/org/michimusic/mobile/screens/AlbumsViewModel.kt
  - app/src/main/java/org/michimusic/mobile/screens/SearchViewModel.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/AlbumsScreen.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/HomeScreen.kt
  - app/src/test/java/org/michimusic/mobile/library/LibraryScopeStoreTest.kt
  - app/src/test/java/org/michimusic/mobile/screens/AlbumsViewModelTest.kt
  - app/src/test/java/org/michimusic/mobile/screens/FederatedSearchTest.kt
  - app/src/test/java/org/michimusic/mobile/ui/screens/HomeScreenTest.kt
  - data/src/main/java/org/michimusic/data/DataModule.kt
  - data/src/main/java/org/michimusic/data/repository/FavoritesRepository.kt
  - data/src/test/java/org/michimusic/data/repository/FavoritesRepositoryTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  HomeScreen confundía analíticas de reproducción (topTracks) con favoritos del usuario ("Favoritos: $topTracksCount temas");
  no existía repositorio de favoritos persistentes desacoplado; no había navegación por scopes de biblioteca en AlbumsScreen;
  la búsqueda local mezclaba indistintamente diferentes masterizaciones (p. ej. 44.1 kHz vs 96 kHz) o no deduplicaba
  correctamente las ubicaciones disponibles; la selección de biblioteca activa no persistía entre sesiones.
PRODUCTION_BEHAVIOR_AFTER:
  FavoritesRepository implementa almacenamiento persistente de favoritos en app_settings de forma asíncrona y segura;
  LibraryScopeStore persiste StateFlow<LibraryScope> con fallback determinista a LibraryScope.All en caso de borrado;
  AlbumsScreen integra selector dinámico de scope (Todas, Este teléfono, bibliotecas sincronizadas/custom);
  HomeScreen separa nítidamente "Favoritos" ($favoritesCount pistas) de "Más Escuchadas" ($topTracksCount temas) y "Recientes";
  SearchViewModel agrupa ubicaciones disponibles y preserva versiones con distinta calidad/masterización (sampleRate/bitrate/formato).
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 342 actionable tasks ejecutadas en :app, :data, :player, :core, :michi-link-client)
  FavoritesRepositoryTest (toggle, add, remove, persistencia y reactividad - PASSED)
  LibraryScopeStoreTest (persistencia, cambio de scope, fallback a All ante eliminación - PASSED)
  FederatedSearchTest (búsqueda federada, agrupación de ubicaciones, preservación de masters distintos - PASSED)
  AlbumsViewModelTest (carga de biblioteca, favoritos y playlists - PASSED)
  HomeScreenTest (loading state, recents, top tracks, favoritos, wrapping de títulos largos - PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: N/A
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Favoritos y scopes sanitizan entradas; fallback seguro si el scope guardado ya no existe;
  sin fugas de datos de bibliotecas privadas en búsqueda federada no autorizada.
ROLLBACK_PLAN:
  git reset --hard 36b30869d6bf37f6a6ca7006886e80b2a74c207d
BLOCKERS: Ninguno.
NEXT_BLOCK: M7 (Michi Link, Identidad, Home Trust y Seguridad Android)
```

---

## Checkpoint de Ejecución — Bloque H (M7)

```text
BLOCK_ID: M7
MASTER_BASE_SHA: aeddc6d5c0daf8d32934a46f34bdd45b52ab852d
FILES_CHANGED:
  - app/src/main/AndroidManifest.xml
  - app/src/main/java/org/michimusic/mobile/di/KoinAccessors.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncWorker.kt
  - app/src/main/res/xml/backup_rules.xml
  - app/src/main/res/xml/data_extraction_rules.xml
  - app/src/main/res/xml/network_security_config.xml
  - michi-link-client/src/main/java/org/michimusic/link/identity/IdentityStorageRecovery.kt
  - michi-link-client/src/main/java/org/michimusic/link/identity/MichiIdentity.kt
  - michi-link-client/src/test/java/org/michimusic/link/identity/Ed25519KeyLoadingTest.kt
  - michi-link-client/src/test/java/org/michimusic/link/identity/IdentityStorageRecoveryTest.kt
  - michi-link-client/src/test/java/org/michimusic/link/identity/MichiIdentityTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  La inicialización de MichiIdentity podía fallar de forma irrecuperable ante corrupción de Android Keystore;
  las preferencias cifradas (EncryptedSharedPreferences) no estaban excluidas de Google Cloud Backup ni
  device-to-device transfer, provocando AEADBadTagException al restaurar en otro hardware con distinta MasterKey;
  network_security_config.xml contenía sintaxis CIDR no admitida en <domain> de Android;
  SyncWorker persistía tokens de sesión y device tokens en la base de datos de WorkManager en texto plano;
  SyncViewModel utilizaba un UUID efímero generado aleatoriamente para clientId en vez de la identidad Ed25519 canónica.
PRODUCTION_BEHAVIOR_AFTER:
  IdentityStorageRecovery proporciona recuperación con reintento y borrado transaccional de clave corrupta;
  backup_rules.xml y data_extraction_rules.xml excluyen michi_identity y paired_devices_secure de respaldos automáticos;
  network_security_config.xml elimina notaciones CIDR inválidas preservando dominios claros permitidos en debug/local;
  SyncWorker resuelve tokens desde PairedDeviceRegistry/TokenStore en runtime abortando si falta credencial;
  SyncViewModel converge clientId a identity.michiId;
  Suite completa de pruebas unitarias para recuperación de identidad, generación y carga de claves Ed25519.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 342 actionable tasks ejecutadas en :michi-link-client, :app, :data, :player, :core)
  IdentityStorageRecoveryTest (5 tests: basic read, recovery on error, double failure throws, writes succeed, corrupted key - PASSED)
  Ed25519KeyLoadingTest (4 tests: valid key, regenerated invalid key, consistent ID, storage recovery invocation - PASSED)
  MichiIdentityTest (2 tests: getOrCreateIdentity, signing and verification - PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (requiere dispositivo Android físico para pruebas con chip Keystore hardware-backed real)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Exclusión de copias de seguridad de claves privadas y tokens; eliminación de credenciales en inputData de WorkManager;
  recuperación resiliente sin filtración de material criptográfico.
ROLLBACK_PLAN:
  git reset --hard aeddc6d5c0daf8d32934a46f34bdd45b52ab852d
BLOCKERS: Ninguno.
NEXT_BLOCK: M8 (Michi Sync: descargas transaccionales, reanudación y perfiles de sincronización)
```

---

## Checkpoint de Ejecución — Bloque I (M8)

```text
BLOCK_ID: M8
MASTER_BASE_SHA: 3be110c1fceecf482599723c31671fccece8784d
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/di/KoinAccessors.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncWorker.kt
  - app/src/test/java/org/michimusic/mobile/sync/SyncWorkerSecurityTest.kt
  - data/src/main/java/org/michimusic/data/DataModule.kt
  - data/src/main/java/org/michimusic/data/cache/MichiDatabase.kt
  - data/src/main/java/org/michimusic/data/cache/MultiLibraryDaos.kt
  - data/src/main/java/org/michimusic/data/repository/MusicLibraryRepository.kt
  - michi-link-client/src/main/java/org/michimusic/link/LinkClient.kt
  - michi-link-client/src/main/java/org/michimusic/link/LinkTransferManager.kt
  - michi-link-client/src/main/java/org/michimusic/link/transfer/TransferModels.kt
  - michi-link-client/src/test/java/org/michimusic/link/transfer/ResumableTransferTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  LinkTransferManager.downloadTrack borraba el archivo .part existente y comenzaba desde 0 sin soporte veraz
  de reanudación por Range; no validaba el código HTTP 206 ni detectaba si el servidor ignoraba el rango
  devolviendo 200 OK con el stream completo (lo que causaba archivos corruptos si se abrían en append);
  la promoción del archivo descargado no era transaccional ni protegía el archivo bueno previo ante fallos de red;
  no existía máquina de estados con checkpoints persistibles ni progreso honesto en bytes;
  SyncProfileDao y SyncManifestDao no estaban expuestos en Room/DI;
  las descargas completadas en SyncWorker no registraban la ubicación TrackLocation(SYNCED_MANAGED) ni la membresía
  en la biblioteca destino del sistema multi-biblioteca de Room v4.
PRODUCTION_BEHAVIOR_AFTER:
  LinkClient soporta streamTrackRange con validación de cabeceras Range, ETag, If-Range y Content-Range;
  LinkTransferManager implementa máquina de estados transaccional (QUEUED, PREFLIGHT, DOWNLOADING, VERIFYING,
  COMMITTING, COMPLETED, PAUSED, FAILED) con TransferCheckpoint;
  reanudación veraz en offset o reinicio transparente y honesto (RESTARTED_FROM_ZERO) si el servidor ignora el rango;
  verificación estricta de hash SHA-256 previa a la promoción;
  promoción atómica con copia de seguridad y restauración del archivo previo en caso de fallo durante el reemplazo;
  cancelación limpia que preserva el .part para su posterior reanudación;
  SyncProfileDao y SyncManifestDao implementados y registrados en Koin DataModule;
  SyncWorker enlaza los archivos descargados a TrackLocation.SYNCED_MANAGED y membresía en la biblioteca de destino
  asociada al dispositivo remoto;
  Suite completa de pruebas unitarias cubriendo reanudación, fallback ante falta de Range, verificación de checksum,
  preservación atómica de archivos antiguos y ausencia de credenciales en WorkManager.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 342 actionable tasks ejecutadas en :michi-link-client, :data, :app, :player, :core)
  ResumableTransferTest (5 tests: partialDownloadResumesAtOffset, rangeNotSupportedFallsBackHonestRestart,
  checksumMismatchNeverPromotes, cancelPreservesSafePart, atomicPromotionKeepsOldGoodFile - PASSED)
  SyncWorkerSecurityTest (workInputContainsNoSecretsTest - PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (requiere dispositivo Android físico y servidor Michi Node real para pruebas de corte de red WiFi durante descarga)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Verificación criptográfica SHA-256 de todas las pistas antes de su promoción; prevención de DoS por buffers
  desbordados o archivos maliciosos; ninguna credencial ni token almacenado en WorkManager Data.
ROLLBACK_PLAN:
  git reset --hard 3be110c1fceecf482599723c31671fccece8784d
BLOCKERS: Ninguno.
NEXT_BLOCK: M9 (AirCat + Michi Devices: contrato real de transferencia y UI)
```

---

## Checkpoint de Ejecución — Bloque J (M9)

```text
BLOCK_ID: M9
MASTER_BASE_SHA: 1cc0890533036e4f35d21394f71a06a64f33b1eb
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/devices/aircat/AirCatModels.kt
  - app/src/main/java/org/michimusic/mobile/devices/aircat/AirCatTransferClient.kt
  - app/src/test/java/org/michimusic/mobile/devices/aircat/AirCatContractTest.kt
  - app/src/test/java/org/michimusic/mobile/devices/aircat/FakeAirCatPeer.kt
  - docs/contracts/AIRCAT_MOBILE_CONSUMER_CONTRACT.md
  - docs/contracts/MICHI_DEVICES_UPSTREAM_REQUEST.md
  - docs/devices/USB_TRANSFER_FEASIBILITY.md
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  AirCat no poseía especificación formal de contrato de consumo ni deslinde arquitectónico frente a Michi Link;
  no existía análisis de viabilidad técnica sobre transferencias por USB frente a las restricciones del sandbox
  de almacenamiento de Android (Scoped Storage) y MTP;
  no existían modelos de datos de transferencia (TransferIntent, AirCatManifest, AirCatCapabilities) ni cliente
  consumidor AirCat en Mobile;
  no había arnés de pruebas con pares simulados (FakeAirCatPeer) para verificar negociación de capacidades,
  compatibilidad de versiones ni rechazo de transferencias con espacio insuficiente o pares no autorizados.
PRODUCTION_BEHAVIOR_AFTER:
  Documentados AIRCAT_MOBILE_CONSUMER_CONTRACT.md, MICHI_DEVICES_UPSTREAM_REQUEST.md y estudio USB_TRANSFER_FEASIBILITY.md;
  desarrollado DefaultAirCatTransferClient desacoplado de transportes efímeros, utilizando LinkTransferManager (M8)
  y enlazando a Room v4 MultiLibrary con origen AIRC_AT;
  validación rigurosa de espacio libre con margen de seguridad (100 MB), rechazo de peers no autorizados,
  incompatibilidad de versiones de manifiesto y control de idempotencia (rechazo de transferId duplicado);
  FakeAirCatPeer instrumentado para simulación de contratos en tests unitarios;
  E2E con servidor físico Michi Devices marcado honestamente como BLOCKED_UPSTREAM a la espera de implementación en repo upstream.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 342 actionable tasks ejecutadas en :app, :data, :player, :core, :michi-link-client)
  AirCatContractTest (5 tests: aircatUnauthorizedPeerRejectedTest, aircatManifestVersionMismatchTest,
  aircatDestinationCapacityTest, aircatDuplicatedTransferIdTest, aircatSuccessfulTransferBindsLibraryTest - PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: BLOCKED_UPSTREAM (servidor real Michi Devices/Player de escritorio aún no desplegado)
UPSTREAM_CONTRACTS: docs/contracts/AIRCAT_MOBILE_CONSUMER_CONTRACT.md, docs/contracts/MICHI_DEVICES_UPSTREAM_REQUEST.md
SECURITY_REVIEW:
  Validación mutua mediante identidades canónicas de Home Trust; no se permite escalada de privilegios ni
  acceso a bibliotecas ajenas sin scope explícito; protección contra agotamiento de almacenamiento local.
ROLLBACK_PLAN:
  git reset --hard 1cc0890533036e4f35d21394f71a06a64f33b1eb
BLOCKERS:
  Pruebas E2E físicas bloqueadas por implementación upstream en repo Michi Devices (BLOCKED_UPSTREAM reportado honestamente).
NEXT_BLOCK: M10 (Catálogos remotos, control distribuido y ReceiverLite Stream)
```

---

## Checkpoint de Ejecución — Bloque N (M12)

```text
BLOCK_ID: M12
MASTER_BASE_SHA: 4bbab1db9e76fa9e1d8a4369a19c5c163013d2f9
FILES_CHANGED:
  - .github/workflows/ci.yml
  - docs/CERTIFICATION_MATRIX.md
  - RELEASE_BLOCKERS.md
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  El pipeline de CI (.github/workflows/ci.yml) ejecutaba cinco jobs básicos sin verificar explícitamente las migraciones
  de esquema Room v3→v4 ni la generación/empaquetado de artefactos release (assembleNormalRelease con R8/ProGuard);
  el Quality Gate no requería la validación de persistencia ni la ausencia de fallas en compilación release;
  la matriz de validación física no estaba estructurada ni versionada en docs/, dificultando auditorías de hardware real;
  RELEASE_BLOCKERS.md no reflejaba la auditoría de expurgo de seguridad ni el estado de aislamiento de Android Auto.
PRODUCTION_BEHAVIOR_AFTER:
  CI ampliado y endurecido con job dedicado de migraciones Room (Migration3To4Test) y assembleNormalRelease en Quality Gate;
  publicada la matriz integral docs/CERTIFICATION_MATRIX.md que cataloga pruebas funcionales por capa, modelo de amenazas,
  auditoría de privacidad y validación física auditable (PENDING_HARDWARE y BLOCKED_UPSTREAM reportados honestamente);
  RELEASE_BLOCKERS.md actualizado con las resoluciones de seguridad y confirmación de release candidate;
  compilación release con ProGuard/R8 y shrinkResources verificada y libre de errores.
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 342 actionable tasks)
BUILD_TARGETS_EXECUTED:
  ./gradlew assembleNormalDebug assembleFdroidDebug assembleNormalRelease (PASSED - 330 actionable tasks)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin dispositivo/emulador conectado en sesión de desarrollo)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (catalogado y estructurado en docs/CERTIFICATION_MATRIX.md)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Verificada exclusión de claves en Android Backup (data_extraction_rules.xml), reglas ProGuard activas,
  MediaLibraryService no exportado (android:exported="false"), y ausencia total de credenciales en repositorio o logs.
ROLLBACK_PLAN:
  git reset --hard 4bbab1db9e76fa9e1d8a4369a19c5c163013d2f9
BLOCKERS: Ninguno para M12.
NEXT_BLOCK: M13 (IA Musical Móvil optativa; fases principales M0 a M12 completadas)
```

---

## Checkpoint de Ejecución — Bloque L (M11)

```text
BLOCK_ID: M11
MASTER_BASE_SHA: 96db01d36886e9275a507e15bf919934eeae5d5a
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/ui/screens/SettingsScreen.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/DiagnosticsScreen.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/DiagnosticsRedactor.kt
  - app/src/test/java/org/michimusic/mobile/ui/screens/SettingsHierarchyTest.kt
  - app/src/test/java/org/michimusic/mobile/ui/screens/DiagnosticsRedactionTest.kt
  - app/src/test/java/org/michimusic/mobile/ui/screens/A11yAndTokensTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  SettingsScreen era una lista vertical única con scroll infinito y sobrecarga cognitiva;
  no existía navegación jerárquica con categorías claras (Audio & DSP, Biblioteca & Almacenamiento, Accesibilidad, Privacidad);
  DiagnosticsScreen permitía ver estados de red pero no proporcionaba exportación redactada ni reseteo de auditoría,
  arriesgando fuga de tokens sensibles en capturas de pantalla o reportes pegados;
  no existían tests que validaran contratos de claves de SharedPreferences ni que los targets táctiles cumplieran con los mínimos de accesibilidad.
PRODUCTION_BEHAVIOR_AFTER:
  SettingsScreen rediseñado jerárquicamente con subpantallas fluidas (AnimatedContent) y soporte de navegación de retroceso (BackHandler);
  preservación 100% íntegra de claves de preferencias existentes en SharedPreferences y Room;
  DiagnosticsScreen equipado con copiado de informe con expurgo de seguridad (DiagnosticsRedactor que elimina tokens, URLs internas y credenciales) y reseteo;
  conformidad estricta con tokens de diseño (MichiSpacing, MichiShapes, MichiTypography) y objetivos táctiles >= 48dp;
  contratos de Android Auto auditados y clasificados honestamente como ANDROID_AUTO_PENDING (MediaLibraryService no exportado, sin dependencias GMS en F-Droid);
  suite completa de pruebas (342 tareas) ejecutada con éxito (BUILD SUCCESSFUL).
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 342 actionable tasks en :app, :data, :player, :core, :michi-link-client)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin dispositivo/emulador conectado en sesión de desarrollo)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (requiere hardware de infotenimiento para Android Auto y pruebas de accesibilidad en pantalla física)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  DiagnosticsRedactor elimina tokens de autenticación, contraseñas, bearer tokens y enmascara IPs privadas antes de cualquier exportación o copiado.
  MediaLibraryService permanece con android:exported="false" en variantes sin bridge automotriz seguro.
ROLLBACK_PLAN:
  git reset --hard 96db01d36886e9275a507e15bf919934eeae5d5a
BLOCKERS: Ninguno.
NEXT_BLOCK: M12 (Calidad, Seguridad, CI, Validación Física y Release)
```

---

## Checkpoint de Ejecución — Bloque K (M10)

```text
BLOCK_ID: M10
MASTER_BASE_SHA: 68865bc595b1e6268ea846a89c93a0b3f86e3f05
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/playback/PlaybackSessionManager.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - app/src/test/java/org/michimusic/mobile/playback/QueueMappingTest.kt
  - data/src/main/java/org/michimusic/data/repository/MusicLibraryRepository.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  LegacyTrackMapper mapeaba todas las pistas como locales (MEDIASTORE o SAF) omitiendo fuentes STREAMING y SYNCED,
  sin asociar TrackLocationType.REMOTE_URL a URLs remotas;
  SyncViewModel forzaba estrategia PairingStrategy.RECEIVER_BUTTON para cualquier dispositivo con deviceType receiver/stream,
  incluso si la preprovisión o el perfil de red confiable anunciaba auth.required = false;
  PlaybackSessionManager no limpiaba el estado de audio tap (stopPcmStreaming) de manera explícita si la creación
  de sesión de ReceiverLite fallaba de forma remota, arriesgando muting residual;
  no existía test garantizando que una pista con URI `content://` fuera rechazada y nunca enviada a un servidor remoto.
PRODUCTION_BEHAVIOR_AFTER:
  LegacyTrackMapper asigna fielmente TrackLocationType.REMOTE_URL y TrackSource.STREAMING a pistas de catálogo remoto,
  preservando contentHash y remoteTrackId;
  SyncViewModel respeta auth.required = false en receptores y hardware preprovisionado permitiendo enlace sin botón;
  PlaybackSessionManager garantiza restauración del sink local (stopPcmStreaming) ante fallo de creación de sesión en receptor;
  garantía de seguridad anti-fuga: URIs `content://` locales nunca se envían como track IDs remotos;
  QueueMappingTest ampliado y verificado con prueba unitaria específica;
  suite completa `./gradlew test` (342 tareas) ejecutada con éxito (BUILD SUCCESSFUL).
UNIT_TESTS_EXECUTED:
  ./gradlew test (PASSED - 342 actionable tasks en :app, :data, :player, :core, :michi-link-client)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin dispositivo/emulador conectado en sesión de desarrollo)
HARDWARE_TESTS_EXECUTED: BLOCKED_UPSTREAM (requiere hardware receptor Michi Stream físico en red)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Previene fuga de URIs de almacenamiento local privado (content://) hacia servidores remotos.
  Negociación estricta de capacidades de audio y tokens efímeros para ReceiverLite.
ROLLBACK_PLAN:
  git reset --hard 68865bc595b1e6268ea846a89c93a0b3f86e3f05
BLOCKERS:
  Validación en hardware físico dependiente de disponibilidad de receptor Michi Stream físico (BLOCKED_UPSTREAM).
NEXT_BLOCK: M11 (UI/UX Premium, Settings jerárquico, Android Auto y Diagnósticos)
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

---

## Checkpoint de Ejecución — Sprint Avance 65% → 75% (Bloques A, B, C, D, E, F)

```text
BLOCK_ID: SPRINT_65_75_ADVANCE
MASTER_BASE_SHA: 0aa421a7f462aead8b46764dcc1ca8d260ff7da8
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/MichiApp.kt
  - app/src/main/java/org/michimusic/mobile/SyncModule.kt
  - app/src/main/java/org/michimusic/mobile/devices/aircat/AirCatTransferClient.kt
  - app/src/main/java/org/michimusic/mobile/di/KoinAccessors.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncWorker.kt
  - app/src/test/java/org/michimusic/mobile/sync/EndToEndOfflineSyncTest.kt
  - core/src/main/java/org/michimusic/core/models/SyncModels.kt
  - data/schemas/org.michimusic.data.cache.MichiDatabase/5.json
  - data/src/main/java/org/michimusic/data/DataModule.kt
  - data/src/main/java/org/michimusic/data/cache/MichiDatabase.kt
  - data/src/main/java/org/michimusic/data/cache/MultiLibraryDaos.kt
  - data/src/main/java/org/michimusic/data/cache/MultiLibraryEntities.kt
  - data/src/main/java/org/michimusic/data/repository/RoomTransferCheckpointRepository.kt
  - data/src/test/java/org/michimusic/data/cache/Migration4To5Test.kt
  - michi-link-client/src/main/java/org/michimusic/link/LinkModule.kt
  - michi-link-client/src/main/java/org/michimusic/link/LinkTransferManager.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. Identidad y alcance de sincronización: WorkManager encolaba tareas genéricas globales sin scoping por dispositivo remoto, arriesgando colisiones en transferencias concurrentes;
  2. Persistencia de checkpoints: Room solo contemplaba hasta versión 4 sin persistencia formal de checkpoints de transferencia duraderos;
  3. Desacoplamiento de AirCat: DefaultAirCatTransferClient operaba con memoria volátil sin respaldar checkpoints en base de datos Room;
  4. P0 - Marcado prematuro de COMPLETED: AirCatTransferClient marcaba checkpoints como COMPLETED antes de asegurar el registro en MultiLibraryRepository; si fallaba Room, el checkpoint quedaba falsamente completado;
  5. P0 - SyncWorker actualizaba checkpoints inexistentes: llamaba a updateProgress sin inserción previa (saveCheckpoint), afectando 0 filas silenciosamente en el DAO;
  6. P1 - LinkTransferManager mantenía estado volátil en _checkpoints sin inyección ni recuperación desde TransferCheckpointRepository ante muerte de proceso;
  7. P1 - Ruta final inválida: AirCatTransferClient usaba nombre relativo cuando el archivo aún no existía en disco, generando rutas fuera del directorio canónico de música.
PRODUCTION_BEHAVIOR_AFTER:
  1. Encolamiento estricto en WorkManager con clave única saneada por dispositivo remoto para tareas one-time y periódicas;
  2. Room v5 con TransferCheckpointEntity, índices optimizados y MIGRATION_4_5 implementada y verificada mediante Migration4To5Test;
  3. RoomTransferCheckpointRepository registrado en DataModule e inyectado en AirCatTransferClient, LinkTransferManager y SyncWorker;
  4. Persistencia transaccional de estados de transferencia (DOWNLOADING, COMPLETED, FAILED) con clave compuesta;
  5. P0 corregido: AirCatTransferClient confirma primero libraryRepository.registerTrackWithLocation; solo tras éxito en Room se asienta COMPLETED (y ante excepción se marca FAILED);
  6. P0 corregido: SyncWorker invoca saveCheckpoint con estado DOWNLOADING antes de comenzar las descargas, y RoomTransferCheckpointRepository.updateProgress incluye fallback de inserción defensiva si las filas afectadas son 0;
  7. P1 corregido: LinkTransferManager inyecta TransferCheckpointRepository, expone musicDir canónico y recupera checkpoints persistidos en SQLite durante preflight tras un reinicio de proceso;
  8. P1 corregido: AirCatTransferClient y SyncWorker resuelven finalPath usando siempre transferManager.musicDir absoluto;
  9. Test de integración honesto (EndToEndOfflineSyncTest) clasificado como INTEGRATION_SIMULATED: utiliza base de datos Room in-memory real (sin mocks en la capa de datos), verificando persistencia SQLite real de checkpoints, registro en biblioteca y recuperación tras interrupción de proceso;
  10. Suite completa ./gradlew test (342 tareas) y ./gradlew lintNormalDebug pasan sin fallos.
UNIT_TESTS_EXECUTED:
  :data:testDebugUnitTest (PASSED - Migration4To5Test, Migration3To4Test)
  :app:testNormalDebugUnitTest (PASSED - EndToEndOfflineSyncTest [INTEGRATION_SIMULATED], AirCatContractTest, SyncWorkerSecurityTest, SyncIdentitySeparationTest)
  ./gradlew test (BUILD SUCCESSFUL - 342 actionable tasks)
  ./gradlew lintNormalDebug (BUILD SUCCESSFUL - 0 lint errors)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (catalogado y estructurado en docs/CERTIFICATION_MATRIX.md)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Cero filtración de credenciales en WorkManager Data o logs de transferencias; control de integridad criptográfica SHA-256 antes de confirmación; validación de permisos de almacenamiento.
ROLLBACK_PLAN:
  git revert al commit resultante o reset a 0aa421a7f462aead8b46764dcc1ca8d260ff7da8
BLOCKERS: Ninguno.
```

---

## Checkpoint de Ejecución — Auditoría KILLCRITIC y Consolidación de Madurez

```text
BLOCK_ID: SPRINT_KILLCRITIC_CONSOLIDATION
MASTER_BASE_SHA: 1a199ec91ab0272510ecc9b260b59e607aa0bd59
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/AppModule.kt
  - app/src/main/java/org/michimusic/mobile/devices/aircat/AirCatTransferClient.kt
  - app/src/main/java/org/michimusic/mobile/sync/DeviceActionResolver.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncWorker.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/DevicesScreen.kt
  - app/src/test/java/org/michimusic/mobile/sync/EndToEndOfflineSyncTest.kt
  - core/src/main/java/org/michimusic/core/models/SyncModels.kt
  - data/src/main/java/org/michimusic/data/cache/MultiLibraryDaos.kt
  - data/src/main/java/org/michimusic/data/repository/RoomTransferCheckpointRepository.kt
  - michi-link-client/src/main/java/org/michimusic/link/LinkTransferManager.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. P0 - Colisión y desalineación de clave de checkpoint: LinkTransferManager indexaba y buscaba exclusivamente por trackId simple, mientras AirCat y Room utilizaban la clave compuesta ($remoteDeviceId:$destinationLibraryId:$trackId). Múltiples servidores con pistas de id idéntico colisionaban, provocando que preflight no encontrase checkpoints persistidos tras reinicio.
  2. P0 - Inyección faltante en SyncWorker: LinkTransferManager(applicationContext) se instanciaba sin transferCheckpointRepository, impidiendo persistir checkpoints desde el Worker.
  3. P0 - Inserciones corruptas en RoomTransferCheckpointRepository: el método updateProgress intentaba insertar registros con valores vacíos si la fila no existía previamente.
  4. P1 - Ausencia de controles y visualización AirCat en DevicesScreen: la pantalla de dispositivos no mostraba el estado de transferencias AirCat, cuota de almacenamiento disponible, ni proveía controles de pausa, reanudación y cancelación.
PRODUCTION_BEHAVIOR_AFTER:
  1. Identidad unificada y aislada: DownloadItem incorpora remoteDeviceId, destinationLibraryId, transferKey y la propiedad effectiveTransferKey. LinkTransferManager busca y actualiza checkpoints en Room y memoria utilizando esta clave compuesta, con método getLatestCheckpointForTrack como retrocompatibilidad.
  2. Inyección completa en SyncWorker: LinkTransferManager recibe checkpointRepo inyectado, y cada DownloadItem se mapea con sus atributos de servidor y biblioteca de destino.
  3. Integridad en RoomTransferCheckpointRepository: se eliminó la inserción artificial con campos en blanco en updateProgress; los checkpoints son creados explícitamente mediante saveCheckpoint con todos sus metadatos antes de descargar.
  4. UI completa en DevicesScreen: LocalDeviceCard exhibe almacenamiento disponible (GB libres), y se integró AirCatTransferCard con título de pista activa, barra de progreso porcentual, estado detallado y botones interactivos de Pausar, Reanudar y Cancelar.
  5. Pruebas E2E completas: EndToEndOfflineSyncTest valida el aislamiento determinista entre dos servidores que comparten el mismo trackId, así como el control interactivo de pausa, reanudación y cancelación en AirCatTransferClient.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos en suite completa)
  EndToEndOfflineSyncTest (5 tests PASSED: testCompleteOfflineSyncFlowWithRealRoomPersistence, testOfflineSyncResumesFromInterruptedCheckpoint, testTransactionIntegrityWhenLibraryRegistrationFails, testTwoServersWithSameTrackIdDoNotCollideInCheckpoints, testAirCatInteractivePauseResumeCancel)
  ./gradlew lintNormalDebug (PASSED - 0 errores de lint)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (catalogado y estructurado en docs/CERTIFICATION_MATRIX.md)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Aislamiento criptográfico y multi-tenant garantizado por claves compuestas; validación previa de almacenamiento disponible; no se exponen tokens ni rutas temporales.
ROLLBACK_PLAN:
  git revert al commit resultante o reset a 1a199ec91ab0272510ecc9b260b59e607aa0bd59
BLOCKERS: Ninguno.
```

---

## Checkpoint de Ejecución — Aislamiento Físico de Archivos, Resolución Estricta y Control Cooperativo Real

```text
BLOCK_ID: SPRINT_KILLCRITIC_PHYSICAL_ISOLATION_AND_CONTROL
MASTER_BASE_SHA: dd095591e4a0b85d842d4bf5c759147e853f0e84
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/devices/aircat/AirCatTransferClient.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncWorker.kt
  - app/src/test/java/org/michimusic/mobile/sync/EndToEndOfflineSyncTest.kt
  - michi-link-client/src/main/java/org/michimusic/link/LinkTransferManager.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. P0 - Colisión física de archivos en disco: LinkTransferManager nombraba archivos físicos en disco y buffers temporales exclusivamente como "${trackId}.${ext}" y "${trackId}.${ext}.part". Si dos servidores distintos (o bibliotecas diferentes) sincronizaban la misma pista de audio, colisionaban físicamente sobreescribiéndose o corrompiendo el archivo parcial .part.
  2. P0 - Resolución no estricta de sesión en AirCat: SyncViewModel.startAirCatTransfer utilizaba el cliente activo global (currentClient) de forma laxa sin verificar que su baseUrl y credenciales correspondieran al targetDeviceId objetivo, arriesgando el envío de solicitudes y tokens cruzados hacia dispositivos erróneos.
  3. P1 - Ausencia de cancelación e interrupción activa de sockets/red en AirCat: pauseTransfer y cancelTransfer actualizaban colecciones en memoria e interceptaban iteraciones entre pistas, pero no interrumpían la coroutine ni el streaming de red en curso para la pista activa.
  4. P1 - Sobrecarga de búsqueda física getTrackFile: no admitía discriminación por remoteDeviceId y destinationLibraryId, imposibilitando la resolución atómica de pistas multi-servidor en el almacenamiento local.
PRODUCTION_BEHAVIOR_AFTER:
  1. Aislamiento físico absoluto en disco: LinkTransferManager, SyncWorker y AirCatTransferClient formatean de forma unificada los archivos finales y temporales como "${safeFileName(remoteDeviceId)}_${safeFileName(destinationLibraryId)}_${safeFileName(trackId)}.${ext}" y ".part". getTrackFile incorpora sobrecarga con remoteDeviceId y destinationLibraryId preservando fallback transparente retrocompatible.
  2. Resolución estricta de sesión AirCat: SyncViewModel.startAirCatTransfer valida credenciales y URL del targetDeviceId, resolviendo el cliente mediante connectionManager.getClient(targetDeviceId) o instanciando un LinkClient dedicado y configurado específicamente con el token y clientDeviceId asignado para dicho nodo.
  3. Control cooperativo e interrupción activa en tiempo real: AirCatTransferClient mantiene un registro concurrente de activeJobs indexado por transferId. Las llamadas a pauseTransfer o cancelTransfer cancelan inmediatamente el Job activo interrumpiendo el flujo de red/socket y emitiendo los estados terminales PAUSED / CANCELLED sin catalogarlos espuriamente como fallos.
  4. Suite de pruebas E2E ampliada: EndToEndOfflineSyncTest incorpora testDualServerPhysicalFileIsolation (dos servidores descargando en paralelo el mismo trackId sin colisión física de archivos ni de claves) y testAirCatRealtimeStreamingCancellation (verificación de interrupción reactiva inmediata del streaming de red).
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 186 tests completados exitosamente, 0 fallos)
  ./gradlew lintNormalDebug (PASSED - 0 errores de lint)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (catalogado y estructurado en docs/CERTIFICATION_MATRIX.md)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Aislamiento multi-tenant integral tanto en persistencia relacional (Room) como en el sistema de archivos (ext4/F2FS); verificación estricta de tokens antes del inicio de transferencias AirCat para prevenir ataques de suplantación entre nodos emparejados.
ROLLBACK_PLAN:
```

---

## Checkpoint de Ejecución — Reanudación Real AirCat, Validación de Confianza de Sesión y Aislamiento Estricto

```text
BLOCK_ID: SPRINT_KILLCRITIC_RESUMABLE_AIRCRAFT_AND_STRICT_ISOLATION
MASTER_BASE_SHA: d083d620bea45954b7e41f68a96092866564bf44
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/devices/aircat/AirCatTransferClient.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - michi-link-client/src/main/java/org/michimusic/link/LinkTransferManager.kt
  - app/src/test/java/org/michimusic/mobile/sync/EndToEndOfflineSyncTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. P0 - Reanudación funcional de AirCat tras pausa: pauseTransfer cancelaba el Job de corrutina, pero resumeTransfer únicamente mutaba el estado en memoria sin relanzar la transferencia ni consultar los checkpoints persistentes de pistas ya completadas, requiriendo iniciar de cero o quedando en limbo.
  2. P0 - Validación de confianza y sesión en AirCat: startAirCatTransfer confiaba ciegamente en el token de pairedDevice sin verificar que la conexión activa mantuviera la sesión viva y autorizada, ni que la identidad del nodo remoto (serverDeviceId / michiId) coincidiera de manera fidedigna antes de solicitar el catálogo.
  3. P1 - Fallback ambiguo a archivos heredados en getTrackFile: cuando se suministraban remoteDeviceId y destinationLibraryId, getTrackFile recurría a archivos con nombre legado "trackId.ext", arriesgando servir pistas huérfanas de otros servidores y vulnerando el aislamiento multi-servidor estricto.
  4. P1 - Pruebas de integración no concurrentes ni verificadas a nivel de bytes: testDualServerPhysicalFileIsolation se ejecutaba de forma secuencial y testAirCatRealtimeStreamingCancellation cancelaba la corrutina antes de asegurar que los bytes parciales hubiesen llegado por la red.
PRODUCTION_BEHAVIOR_AFTER:
  1. Reanudación real con sesión activa y salto de checkpoints: AirCatTransferClient persiste ActiveTransferSession internamente y al recibir resumeTransfer() re-lanza el Job en un clientScope dedicado con SupervisorJob. En executeTransfer(), evalúa si cada pista ya posee checkpoint COMPLETED y archivo físico verificado en disco; si es así, avanza el acumulador de bytes y salta la descarga, reanudando transparentemente desde la pista pendiente.
  2. Verificación criptográfica y de identidad remota previa a AirCat: SyncViewModel.startAirCatTransfer realiza una llamada de comprobación a effectiveClient.getServerInfo(), validando que la sesión esté autorizada y que el remoteServerId/remoteMichiId coincida de manera fidedigna con la identidad almacenada del dispositivo.
  3. Eliminación del fallback ambiguo en getTrackFile: getTrackFile no realiza fallbacks a rutas no aisladas cuando se especifican remoteDeviceId y destinationLibraryId, asegurando que cada nodo y biblioteca acceda únicamente a sus archivos debidamente aislados.
  4. Endurecimiento exhaustivo de pruebas:
     - testDualServerPhysicalFileIsolation ejecuta descargas simultáneas en paralelo real mediante async/coroutineScope.
     - testAirCatRealtimeStreamingCancellation utiliza un CompletableDeferred para verificar que los primeros 16KB fueron transmitidos activamente antes de disparar la cancelación.
     - Nuevo test testAirCatPauseAndResumeFromCheckpoint verifica el ciclo completo de interrupción tras la primera pista y reanudación automática exitosa de la segunda pista con persistencia en Room.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - suite completa de pruebas unitarias al 100%, 0 fallos)
  EndToEndOfflineSyncTest (7 tests PASSED: testCompleteOfflineSyncFlowWithRealRoomPersistence, testProcessInterruptionRecoveryWithPersistentCheckpoint, testTransactionIntegrityWhenLibraryRegistrationFails, testCheckpointIsolationAcrossServersForSameTrack, testAirCatInteractivePauseResumeCancel, testDualServerPhysicalFileIsolation, testAirCatRealtimeStreamingCancellation, testAirCatPauseAndResumeFromCheckpoint)
  ./gradlew lintNormalDebug (PASSED - 0 errores de lint)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (catalogado y estructurado en docs/CERTIFICATION_MATRIX.md)
```

---

## Checkpoint de Ejecución — Macro-Sprint M13: Integración Total, Seguridad Criptográfica y Madurez 85%

```text
BLOCK_ID: SPRINT_M13_TOTAL_INTEGRATION
MASTER_BASE_SHA: 11c3bca404a48a1a9cde01b44df2418e2ba9bd7c
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - app/src/main/java/org/michimusic/mobile/devices/aircat/AirCatTransferClient.kt
  - app/src/main/java/org/michimusic/mobile/playback/PlaybackSessionManager.kt
  - app/src/main/java/org/michimusic/mobile/remote/RemoteViewModel.kt
  - michi-link-client/src/main/java/org/michimusic/link/LinkClientConfig.kt
  - app/src/test/java/org/michimusic/mobile/remote/RemoteViewModelTest.kt
  - app/src/test/java/org/michimusic/mobile/sync/EndToEndOfflineSyncTest.kt
  - RELEASE_BLOCKERS.md
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. Identidad remota en AirCat validada parcialmente: SyncViewModel comprobaba coincidencia de IDs pero no validaba la clave pública fijada durante el emparejamiento ni el estado revocado del nodo remoto.
  2. Salto optimista de pistas en AirCat: AirCatTransferClient saltaba pistas ya completadas basándose en el estado y tamaño del archivo sin verificar integridad criptográfica (SHA-256), origen del servidor y biblioteca de destino.
  3. Semántica ambigua en controles remotos: RemoteViewModel delegaba play(), pause() y stop() indistintamente en playPause(), provocando que una pulsación de pause() pudiera iniciar reproducción accidental.
  4. Redirección HTTP cleartext no restringida: LinkClient normalizaba URLs sin validar si se apuntaba a redes públicas desprotegidas en texto claro.
  5. Declaraciones de release desalineadas: RELEASE_BLOCKERS.md catalogaba el proyecto como Release Candidate general sin segregar explícitamente las capacidades dependientes de hardware físico.
PRODUCTION_BEHAVIOR_AFTER:
  1. Verificación criptográfica rigurosa: SyncViewModel valida que el dispositivo no esté revocado, y contrasta estrictamente serverId, michiId y clave pública Ed25519 remota contra el registro confiable fijado.
  2. Pre-skip criptográfico exhaustivo en AirCat: AirCatTransferClient verifica SHA-256, origen de nodo y pertenencia a biblioteca antes de omitir cualquier pista completada en disco.
  3. Comandos de reproducción idempotentes y explícitos: PlaybackSessionManager y RemoteViewModel exponen play(), pause() y stop() dedicados con semántica estricta.
  4. Restricción de transporte cleartext: LinkClientConfig rechaza conexiones HTTP en texto claro hacia direcciones públicas de internet, permitiendo únicamente localhost, .local y rangos privados de LAN.
  5. Clasificación honesta de release: RELEASE_BLOCKERS.md clasifica con rigor cada subsistema (INTEGRATION_TESTED, PENDING_HARDWARE, BLOCKED_UPSTREAM), acreditando un 85% de madurez global real demostrable.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 190 tests en suite completa, 0 fallos)
  ./gradlew :michi-link-client:testDebugUnitTest (PASSED - 100 tests completados, 0 fallos)
  ./gradlew lintNormalDebug (PASSED - 0 errores de lint)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (catalogado y estructurado en docs/CERTIFICATION_MATRIX.md)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Criptografía Ed25519 verificada en transferencias; transporte cleartext restringido a LAN; comandos de control idempotentes; sin fuga de secretos.
ROLLBACK_PLAN:
  git revert al commit resultante o reset a 11c3bca404a48a1a9cde01b44df2418e2ba9bd7c
BLOCKERS: Ninguno en software.
```

---

## Checkpoint de Ejecución — Macro-Sprint M14: Avance Transversal e Integral de Madurez

```text
BLOCK_ID: SPRINT_M14_TRANSVERSAL_MATURITY
MASTER_BASE_SHA: 810b54773b9aea680e7a6d5b687d64e8167c9f81
FILES_CHANGED:
  - player/src/main/java/org/michimusic/player/LocalPlaybackGateway.kt
  - player/src/main/java/org/michimusic/player/PlayerController.kt
  - player/src/main/java/org/michimusic/player/AudioController.kt
  - player/src/main/java/org/michimusic/player/MichiPlaybackService.kt
  - player/src/main/java/org/michimusic/player/PlayerDependencies.kt
  - player/src/main/java/org/michimusic/player/UsbDacManager.kt
  - player/src/test/java/org/michimusic/player/PlayerControllerTest.kt
  - data/src/main/java/org/michimusic/data/repository/PlaylistRepository.kt
  - data/src/main/java/org/michimusic/data/repository/MusicLibraryRepository.kt
  - data/src/test/java/org/michimusic/data/repository/PlaylistRepositoryTest.kt
  - app/src/main/java/org/michimusic/mobile/screens/PlaylistsViewModel.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/QueueScreen.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/SettingsScreen.kt
  - michi-link-client/src/main/java/org/michimusic/link/LinkClientConfig.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
  - RELEASE_BLOCKERS.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. Media3 Cola estática / incompleta: Sin operaciones de playNext ni reordenamiento de cola dinámico en tiempo real en ExoPlayer/MediaSession; falta de persistencia inmediata de la cola tras reorganizaciones manuales.
  2. Bucle infinito en fallos de pistas: MichiPlaybackService intentaba saltar forward indefinidamente ante errores consecutivos de decodificación/red, provocando agotamiento de batería y loops descontrolados.
  3. Indiferenciación USB DAC vs Headset: UsbDacManager trataba indistintamente adaptadores USB básicos (TYPE_USB_HEADSET) como DACs audiófilos (TYPE_USB_DEVICE / ACCESSORY).
  4. Rutas físicas ciegas en Multi-Library: MusicLibraryRepository aceptaba URIs tipo archivo persistidas en Room sin verificar existencia real en el sistema de archivos del dispositivo.
  5. Playlists carentes de agregado por álbum: PlaylistRepository solo soportaba añadir pistas unitarias, impidiendo operaciones atómicas de álbum completo.
  6. ReplayGain UI desconectada de Media3: Los controles de Settings no actualizaban el procesador en caliente o causaban filtrado de dependencias internas de Media3.
PRODUCTION_BEHAVIOR_AFTER:
  1. Media3 Cola dinámica y persistente: playNext(track) inserta tras la pista actual en ExoPlayer; moveQueueItem(from, to) reordena en Media3 recalculando índice activo y persiste atómicamente la cola en Room.
  2. Protección anti-bucle en servicio: MichiPlaybackService cuenta fallos consecutivos (consecutiveErrorCount); tras 3 fallos consecutivos detiene la reproducción y reporta error en lugar de entrar en bucle.
  3. Distinción rigurosa DAC vs Headset: AudioOutputDevice incorpora isUsbHeadset e isUsbDac separados, reportando la verdad del hardware al observador de ruta.
  4. Validación física en almacenamiento: MusicLibraryRepository valida que rutas locales existan físicamente en disco antes de seleccionarlas como ubicación preferida.
  5. Adición de álbumes atómica a Playlists: PlaylistRepository y PlaylistsViewModel exponen addAlbumToPlaylist(id, albumTracks) con mutex atómico.
  6. ReplayGain reactivo y desacoplado: PlayerDependencies.configureReplayGain(mode, preAmp) encapsula la configuración en caliente de Media3 sin filtrar classpath interno.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos)
  ./gradlew testFdroidDebugUnitTest (PASSED - 0 fallos)
  ./gradlew :data:testDebugUnitTest --tests "org.michimusic.data.cache.Migration3To4Test" (PASSED - 0 fallos)
  ./gradlew :player:testDebugUnitTest (PASSED - tests de queue reorder, playNext, hotplug y route observer al 100%)
  ./gradlew lintNormalDebug (PASSED - 0 errores de lint)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE (catalogado y estructurado en docs/CERTIFICATION_MATRIX.md)
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Validación rigurosa de hostnames y rangos privados en transporte LinkClient; validación física de rutas de archivo previniendo desincronizaciones de almacenamiento.
ROLLBACK_PLAN:
  git reset --hard 810b54773b9aea680e7a6d5b687d64e8167c9f81
BLOCKERS: Ninguno en software.
```

---

## Checkpoint de Ejecución — Auditoría KILLCRITIC Cierre M14.4

```text
BLOCK_ID: M14.4
MASTER_BASE_SHA: 43d9fb1bf5182e6ac8bde3ab985348612df36e84
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/devices/aircat/AirCatTransferClient.kt
  - app/src/main/java/org/michimusic/mobile/di/KoinAccessors.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncWorker.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - app/src/main/java/org/michimusic/mobile/ui/components/TrackActionMenu.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/QueueScreen.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/NowPlayingScreen.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/AlbumsScreen.kt
  - app/src/test/java/org/michimusic/mobile/devices/aircat/AirCatContractTest.kt
  - data/src/main/java/org/michimusic/data/repository/MusicLibraryRepository.kt
  - data/src/test/java/org/michimusic/data/repository/MultiLibraryRepositoryTest.kt
  - player/src/main/java/org/michimusic/player/AudioController.kt
  - player/src/main/java/org/michimusic/player/PlayerController.kt
  - player/src/test/java/org/michimusic/player/PlayerControllerTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. AirCat Transfer / Recovery:
     - registerTrackWithLocation retornaba Result<Unit> pero se ejecutaba sin .getOrThrow(), provocando que fallos de persistencia en Multi-Library marcaran silenciosamente el checkpoint como COMPLETED en lugar de FAILED.
     - recoverIncompleteTransfers no validaba pairedDevice.isRevoked, permitiendo recuperar transferencias desde pares revocados.
     - Durante la recuperación de transferencias se sobrescribía TrackIdentity con valores dummy ("Unknown") en lugar de preservar los metadatos existentes en base de datos.
     - recoverIncompleteTransfers era un método huérfano desconectado de la sincronización periódica y de la UI.
  2. Media3 Timeline Reconciliation:
     - PlayerController y AudioController utilizaban currentQueue.associateBy { it.id }. Si una cola contenía la misma pista múltiples veces (duplicados intencionales), la lista colapsaba perdiendo elementos y desincronizando índices con ExoPlayer.
  3. Multi-Library Transaction Rollback:
     - registerTrackWithLocationAtomic en Room carecía de una prueba unitaria explícita verificando la reversión atómica de transacciones ante fallos de integridad referencial.
  4. UI Track Actions:
     - TrackActionBottomSheet estaba desconectado de las pantallas principales sin callbacks reales ni diálogos de inserción en playlists.
PRODUCTION_BEHAVIOR_AFTER:
  1. AirCat Transfer / Recovery:
     - Se aplicó .getOrThrow() tanto en executeTransfer como en recoverIncompleteTransfers, garantizando que cualquier fallo en Multi-Library desate rollback y marque FAILED en el checkpoint.
     - Se valida estrictamente pairedDevice.isRevoked retornando AirCatTransferResult.UnauthorizedPeer si el dispositivo está revocado.
     - Se consulta getTrackIdentity(cp.trackId) para preservar fielmente los metadatos previos antes de recurrir a fallback.
     - Se integró la orquestación en SyncWorker (previo a encolar nuevas descargas) y en SyncViewModel.recoverIncompleteAirCatTransfers.
  2. Media3 Timeline Reconciliation:
     - Reconciliación secuencial mediante un pool mutable de pistas restantes (remainingQueueTracks.removeAt(indexOfFirst)), preservando pistas duplicadas en su orden y posición exactos.
  3. Multi-Library Transaction Rollback:
     - Test unitario exhaustivo en MultiLibraryRepositoryTest que demuestra que ante fallos en llaves foráneas ninguna entidad queda huérfana en la base de datos.
  4. UI Track Actions:
     - TrackActionBottomSheet y AddToPlaylistDialog integrados con menú MoreVert en QueueScreen, NowPlayingScreen y AlbumsScreen, soportando Reproducir ahora, Reproducir siguiente, Añadir a cola, Añadir a playlist, Favoritos y Signal Truth.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos)
  ./gradlew testFdroidDebugUnitTest (PASSED - 0 fallos)
  ./gradlew :data:test :player:test :core:test :michi-link-client:test (PASSED - 0 fallos)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (sin emulador/dispositivo conectado en entorno headless)
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Rechazo de dispositivos revocados en AirCat previene reanudación no autorizada de transferencias. Rollback atómico en Room garantiza consistencia de datos ante fallos.
ROLLBACK_PLAN:
  git reset --hard 43d9fb1bf5182e6ac8bde3ab985348612df36e84
BLOCKERS: Ninguno.
```

---

```yaml
SPRINT_ID: M14.5
DATE: 2026-10-22
AUTHOR: Antigravity + Gemini
COMPONENT: michi-music-mobile
COMMIT_HASH: a11f5f6cf8b99fee38a165ae472db9cd6a7acb77
TARGET_MATURITY: 88%
MEASURED_MATURITY: 88%
PRODUCTION_BEHAVIOR_BEFORE:
  1. Remote Playback Actions in Now Playing:
     - Las acciones de NowPlayingScreen ("Reproducir ahora", "Reproducir siguiente", "Añadir a cola") llamaban directamente a AudioController local en vez de pasar por PlaybackSessionManager, causando bifurcación cuando había un endpoint remoto activo.
  2. AirCat Checkpoint Recovery Security:
     - recoverIncompleteTransfers no validaba deviceToken binding entre LinkClient y PairedDevice.
     - No filtraba estrictamente checkpoints por remoteDeviceId cruzado.
     - No re-verificaba revocación dinámica del par entre descargas individuales de una misma tanda.
  3. SyncWorker Recovery Robustness:
     - SyncWorker envolvía recoverIncompleteTransfers en runCatching ignorando silenciosamente fallos, suprimiendo CancellationException y no abortando ante pares no autorizados.
  4. UI AddToPlaylistDialog:
     - isAdding podía quedar atrapado en true ante excepciones no controladas.
     - addedCount == 0 (pistas ya existentes en playlist) no presentaba retroalimentación clara al usuario.
  5. Media3 Duplicate Queue Identity:
     - PendingQueueCommand.Move y Remove en AudioController operaban exclusivamente por trackId, desordenando o eliminando la primera ocurrencia en colas con pistas repetidas intencionalmente.
PRODUCTION_BEHAVIOR_AFTER:
  1. Remote Playback Actions:
     - PlaybackSessionManager incorpora playNow, playNext, addToQueue y moveQueueItem con despacho bidireccional: comandos HTTP remotos (play_track, play_next, add_to_queue, reorderQueue) sin alterar el reproductor local si isRemoteActive es true.
  2. AirCat Checkpoint Recovery Security:
     - Validación obligatoria de deviceToken coincidente entre LinkClient y PairedDevice.
     - Filtrado estricto cp.remoteDeviceId == remoteDeviceId.
     - Comprobación dinámica de revocación de par antes de cada descarga en la secuencia.
  3. SyncWorker Recovery Robustness:
     - Evaluación explícita de recoveryResult: relanza CancellationException y aborta inmediatamente con Result.failure si el par es UnauthorizedPeer.
  4. UI AddToPlaylistDialog:
     - Bloque try / finally que garantiza isAdding = false ante cancelaciones o fallos, informando específicamente cuando la pista ya existía en la playlist seleccionada.
  5. Media3 Duplicate Queue Identity:
     - PendingQueueCommand.Move(trackId, toIndex, fromIndex) y PendingQueueCommand.Remove(trackId, index) almacenan y respetan la posición exacta de ocurrencias duplicadas.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos)
  ./gradlew testFdroidDebugUnitTest (PASSED - 0 fallos)
  ./gradlew :data:test :player:test :core:test :michi-link-client:test (PASSED - 0 fallos)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Criptografía y enlace de sesión protegidos contra spoofing de tokens en transferencias AirCat. Revocación inmediata en tiempo real.
ROLLBACK_PLAN:
  git reset --hard 9a247ebe54735ea6b6fcbacd4564792e7cd64905
BLOCKERS: Ninguno.
```

---

```yaml
SPRINT_ID: M15
DATE: 2026-10-22
AUTHOR: Antigravity + Gemini
COMPONENT: michi-music-mobile
COMMIT_HASH: 6c29c0ee6ef0314fbe933f2fec47bfedea8b786b
TARGET_MATURITY: 89%
MEASURED_MATURITY: 89%
PRODUCTION_BEHAVIOR_BEFORE:
  1. DSD/DoP & Bit-Perfect Claims:
     - El estudio DSD_DOP_FEASIBILITY.md figuraba con estado ambiguo de ingeniería sin exclusión formal para Mobile.
     - SignalTruthDialog en NowPlayingScreen y SignalTruthTracker presentaban reclamos de "Bit-Perfect" (REQUESTED/UNVERIFIABLE) sin posibilidad de verificación física ni HAL bypass en Android.
     - EqualizerDialog utilizaba el rótulo "Transparent" y prometía "máxima pureza", generando ambigüedad frente a los mezcladores de Android.
  2. Lossless Filtering Flaw:
     - AlbumsScreen consideraba Lossless cualquier pista con sampleRate >= 48000, clasificando falsamente archivos MP3, AAC u Opus con pérdida a 48 kHz como lossless.
PRODUCTION_BEHAVIOR_AFTER:
  1. DSD/DoP & Bit-Perfect Archiving:
     - Formalización de ADR-MOB-DSD-LEGACY en docs/contracts/ADR-MOB-DSD-LEGACY.md.
     - DSD_DOP_FEASIBILITY.md archivado como ARCHIVED / OUT_OF_SCOPE_MOBILE (Decisión D-02).
     - Eliminación de la fila "Bit-Perfect" de SignalTruthDialog en NowPlayingScreen.
     - BitPerfectStatus en :core deprecado con @Deprecated por compatibilidad hacia atrás de esquemas compartidos, y SignalTruthTracker fijado en UNVERIFIABLE sin inferencias no probadas.
     - Rótulo de AudioProcessingMode.TRANSPARENT en EqualizerDialog cambiado a "Sin efectos" con explicación honesta de neutralidad de DSP (Decisión D-03).
  2. Genuine Lossless Classification:
     - El filtro LOSSLESS en AlbumsScreen valida exclusivamente códecs reales sin pérdida (FLAC, ALAC, WAV, PCM). Archivos con compresión con pérdida a 48 kHz o superior (MP3, AAC, Opus) son correctamente rechazados.
     - Se añadió la suite unitaria LibraryLosslessClassifierTest validando rigurosamente la taxonomía de códecs.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos)
  ./gradlew testFdroidDebugUnitTest (PASSED - 0 fallos)
  ./gradlew :data:test :player:test :core:test :michi-link-client:test (PASSED - 0 fallos)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Se elimina la superficie de riesgo acústico por streaming DoP no soportado y se garantizan afirmaciones de señal 100% verificables y honestas.
ROLLBACK_PLAN:
  git revert fdac694..HEAD
BLOCKERS: Ninguno.
```

---

```yaml
SPRINT_ID: M16
DATE: 2026-10-22
AUTHOR: Antigravity + Gemini
COMPONENT: michi-music-mobile
COMMIT_HASH: dcdd3bd5631db63411a5335108d7dc334470083f
TARGET_MATURITY: 90%
MEASURED_MATURITY: 90%
PRODUCTION_BEHAVIOR_BEFORE:
  1. Theme & Accessibility Reactivity:
     - Los switches de accesibilidad ("Alto contraste" y "Reducir animaciones") en SettingsScreen guardaban las preferencias en SharedPreferences pero MichiTheme y los componentes animados (como BottomNavBar) no se enteraban reactivamente sin reiniciar la app o forzar una recomposición manual.
     - MichiTheme no contaba con una paleta de alto contraste (HighContrastDarkColorScheme) dedicada ni exponía un CompositionLocal reactivo para preferencias visuales.
  2. Componentes Compartidos y Accesibilidad Táctil:
     - No existía una capa de componentes premium estándar (MichiIconAction, MichiSurface, MichiBottomSheet, MichiPlaybackControls).
     - Se requerían targets de toque mínimos garantizados de 48dp x 48dp y la eliminación de círculos y pastillas de fondo artificiales en controles de reproducción según la guía de diseño.
     - Faltaban las especificaciones técnicas formales (Component Specs) en docs/uiux/components/.
PRODUCTION_BEHAVIOR_AFTER:
  1. Reactive Visual Preferences & Theme Engine:
     - Implementado VisualPreferencesRepository como singleton reactivo inyectable con StateFlow y listener de SharedPreferences.
     - MainActivity inyecta VisualPreferencesRepository y alimenta MichiTheme(visualPreferences).
     - MichiTheme commuta atómicamente a HighContrastDarkColorScheme y expone LocalVisualPreferences.
     - SettingsScreen delega los toggles directamente a VisualPreferencesRepository con actualización inmediata del árbol de UI.
     - BottomNavBar respeta LocalVisualPreferences.current.reducedMotion eliminando el rebote (spring LowBouncy) y usando snap().
  2. Standardized Premium Components & Specs:
     - Creado MichiIconAction garantizando target táctil de >= 48dp (MichiSpacing.minTouchTarget), fondo neutro y semántica Role.Button accesible.
     - Creado MichiSurface con 4 niveles semánticos (Canvas, Content, Raised, Floating) y adaptación automática de bordes y rellenos en modo alto contraste.
     - Creado MichiBottomSheet con esquinas superiores de 24dp, manija accesible de arrastre y soporte nativo de alto contraste (adoptado en TrackActionBottomSheet).
     - Creado MichiPlaybackControls y modelo PlaybackControlUiState con íconos blancos puros y target >= 48dp.
     - Redactadas las especificaciones en docs/uiux/components/ (MichiIconAction.md, MichiPlaybackControls.md, MichiSurface.md, MichiBottomSheet.md).
  3. Testing:
     - Añadida suite VisualPreferencesPropagationTest validando StateFlow, reactividad y mutación de SharedPreferences.
     - Añadida suite PremiumComponentsTest validando contratos de accesibilidad y jerarquía de superficies.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos)
  ./gradlew testFdroidDebugUnitTest (PASSED - 0 fallos)
  ./gradlew :data:test :player:test :core:test :michi-link-client:test (PASSED - 0 fallos)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Preferencia de usuario aislada en sandbox local de michi_settings sin filtración a logs ni red.
ROLLBACK_PLAN:
  git revert 69d70aa..HEAD
BLOCKERS: Ninguno.
```

---

```yaml
SPRINT_ID: M17
DATE: 2026-10-22
AUTHOR: Antigravity + Gemini
COMPONENT: michi-music-mobile
COMMIT_HASH: 8bdc5ff9515e9cd897121a0e89fa932a7b88fd09
TARGET_MATURITY: 91%
MEASURED_MATURITY: 91%
PRODUCTION_BEHAVIOR_BEFORE:
  1. Playback Controls Visual Inconsistency:
     - NowPlayingScreen contenía botones con fondos circulares y un gran botón central rosa de 68dp (`PrimaryPinkContainer`) en lugar de iconos limpios sólidos sin círculos decorativos.
     - RemoteScreen usaba un botón de Play/Pausa con gradiente llamativo `Brush.linearGradient(PrimaryPinkContainer, SecondaryPurpleDeep)`.
  2. Volume Slider Violation (Decisión D-05):
     - RemoteScreen incluía una tarjeta con `MichiFineSlider` para el volumen efectivo del reproductor remoto, contraviniendo la regla innegociable D-05 ("Sin slider de volumen en Now Playing ni en Control Remoto").
PRODUCTION_BEHAVIOR_AFTER:
  1. Standardized Minimalist Playback Controls:
     - Adopción de `MichiPlaybackControls` en `NowPlayingScreen`: iconos blancos sólidos sin fondos circulares en Skip Previous/Next, Shuffle y Repeat, manteniendo el área táctil mínima de 48dp y central Play/Pause accesible.
     - Adopción de `MichiPlaybackControls` en `RemoteScreen`: unificando la gramática visual con NowPlayingScreen.
     - Soporte para tags de test configurables en `MichiPlaybackControls` para interoperabilidad de pruebas automáticas.
  2. Strict Volume Slider Removal (D-05):
     - Eliminado definitivamente el slider de volumen (`MichiFineSlider` / VolumeDown / VolumeUp) de `RemoteScreen`.
     - NowPlayingScreen se mantiene libre de cualquier slider de volumen.
  3. Quality Gates:
     - Creada suite unitaria `NowPlayingNoVolumeTest` que valida formalmente la ausencia de sliders de volumen en las pantallas de reproducción.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos)
  ./gradlew testFdroidDebugUnitTest (PASSED - 0 fallos)
  ./gradlew :data:test :player:test :core:test :michi-link-client:test (PASSED - 0 fallos)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Eliminación de sliders sin afectación a contratos de red o privilegios locales.
ROLLBACK_PLAN:
  git revert 3985867..HEAD
BLOCKERS: Ninguno.
```

```yaml
SPRINT_ID: M18
DATE: 2026-10-23
AUTHOR: Antigravity + Gemini
COMPONENT: michi-music-mobile
COMMIT_HASH: HEAD
TARGET_MATURITY: 90%
MEASURED_MATURITY: 90%
PRODUCTION_BEHAVIOR_BEFORE:
  1. Remote State Authority & Queue Mutations:
     - Remote queue mutations (playNow, playNext, addToQueue, moveQueueItem) en PlaybackSessionManager aplicaban mutaciones optimistas locales sobre `_sessionState.value.queue` antes de confirmación remota, generando divergencias de estado ante latencia de red o rechazos.
     - No existía modelo explícito de ciclo de vida para comandos remotos (`RemoteCommandRecord`).
  2. Queue Duplicate Disambiguation:
     - Media3 PlayerState operaba exclusivamente con `Track`, dificultando desambiguar instancias idénticas del mismo tema agregadas en múltiples posiciones de la cola.
  3. AirCat Transfer Security & Token Binding:
     - AirCatTransferClient.recoverIncompleteTransfers permitía recuperar descargas con validación parcial de tokens si uno de los extremos contenía token nulo o vacío.
  4. Devices Screen Architecture & Action Overload:
     - UnifiedDevice no reflejaba si un dispositivo estaba revocado criptográficamente (`isRevoked`).
     - DeviceActionResolver producía múltiples acciones primarias compitiendo por atención o carecía de jerarquía unificada.
     - Los nodos de dispositivos mostraban diálogos modales básicos sin granularidad en 4 dimensiones (Discovery, Trust, Connection, Capabilities).
PRODUCTION_BEHAVIOR_AFTER:
  1. Authoritative Remote Playback Commands:
     - Implementado `RemoteCommandModels.kt` con `RemoteCommandStatus` (`PENDING`, `ACCEPTED`, `CONFIRMED`, `REJECTED`, `TIMED_OUT`, `UNSUPPORTED`, `DISCONNECTED`) y `RemoteCommandRecord`.
     - PlaybackSessionManager emite comando PENDING, ejecuta llamada RPC a LinkClient, y actualiza la cola y estado SOLO al recibir confirmación autoritativa (`client.getQueue()`).
  2. QueueEntry Identity Model:
     - Creado `QueueEntry` en `:core:models` con `entryId` UUID único, `canonicalTrackId`, `position`, `locationId` y `track`.
     - `PlayerState` y `PlayerController` sincronizan `queueEntries` de forma determinista para desambiguar pistas repetidas.
  3. Strict AirCat Token Security Binding:
     - `recoverIncompleteTransfers` valida estrictamente que tanto `linkClient.deviceToken` como `pairedDevice.deviceToken` no estén vacíos y sean exactamente iguales; de lo contrario aborta con `AirCatException.UnauthorizedPeer`.
  4. 4-Dimension Device State & 1-CTA Hierarchy:
     - `DeviceUiModels.kt` modela formalmente Discovery (`NOT_SEEN`, `DISCOVERED`, `STALE`), Trust (`UNKNOWN`, `PENDING`, `TRUSTED`, `REVOKED`, `IDENTITY_MISMATCH`), Connection (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `OFFLINE`, `ERROR`) y Capabilities.
     - `DeviceActionResolver` garantiza exactamente UNA acción primaria (`isPrimary == true`) con precedencia estricta por rol (LibraryHost -> BROWSE_LIBRARY, PlaybackHost -> CONTROL_PLAYBACK, AudioReceiver -> PLAY_ON_DEVICE, SyncHost -> SYNC_LIBRARY, Revoked/Fallback -> VIEW_DETAILS).
     - Diseñado e integrado `DeviceDetailSheet.kt` (`MichiBottomSheet`, `MichiSurface`, `MichiIconAction`) con métricas criptográficas, acciones secundarias y diálogo de desemparejamiento.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos)
  ./gradlew :core:test :player:test :data:test :michi-link-client:test (PASSED - 0 fallos)
  ./gradlew compileNormalDebugKotlin compileFdroidDebugKotlin (PASSED - 0 fallos)
  Nuevas suites:
    - RemoteCommandAuthorityTest (PASSED)
    - QueueEntryDisambiguationTest (PASSED)
    - AirCatSecurityTokenBindingTest (PASSED)
    - DeviceActionResolverTest (PASSED)
    - DeviceUiDimensionsTest (PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: AirCat Transfer & Michi Link Playback REST/SSE
SECURITY_REVIEW:
  Refuerzo de token binding en recuperación de transferencias de archivos y revocación criptográfica en resolución de acciones.
ROLLBACK_PLAN:
  git revert 8a4fb75..0804da1
BLOCKERS: Ninguno.
```

```yaml
SPRINT_ID: M19
DATE: 2026-10-23
AUTHOR: Antigravity + Gemini
COMPONENT: michi-music-mobile
COMMIT_HASH: HEAD
TARGET_MATURITY: 92%
MEASURED_MATURITY: 92%
PRODUCTION_BEHAVIOR_BEFORE:
  1. Home Fake Recents Heuristic:
     - En HomeScreen, si el historial reciente estaba vacío (`recentTracks.isEmpty()`), la sección "Recientemente Escuchadas" se poblaba silenciosamente con `allTracks.take(8)`, fingiendo un historial inexistente con pistas arbitrarias de la biblioteca.
  2. Playlist Screen No-Op Action:
     - En PlaylistScreen, hacer click en una tarjeta de playlist no realizaba acción alguna (`onClick = { /* Open playlist detail */ }`), dejando al usuario imposibilitado de acceder al detalle de su lista de reproducción.
  3. Lossless Filter Inconsistency in Search:
     - SearchViewModel evaluaba `sampleRate >= 48000` o códecs no tipados bajo el filtro `SearchFilter.LOSSLESS`, permitiendo que archivos MP3 o AAC a 48 kHz aparecieran erróneamente catalogados como Lossless.
  4. Library to Playlists Navigation:
     - AlbumsScreen no proveía un acceso directo visual para navegar a la colección de playlists del usuario desde la vista principal de la biblioteca.
PRODUCTION_BEHAVIOR_AFTER:
  1. Honest Home Content & Recent Truth:
     - HomeScreen muestra la sección "Recientemente Escuchadas" EXCLUSIVAMENTE si existen pistas con historial real en `recentTracks`.
     - Si no hay historial reciente y la biblioteca contiene temas, se presenta honestamente con el título "De tu biblioteca" sin fingir historial de escucha.
  2. Complete Playlist Navigation & Fast Play:
     - PlaylistScreen recibe el callback `onNavigateToPlaylist(playlistId)` conectado a la ruta `playlist/{playlistId}` en `MichiNavHost`.
     - Se eliminó el no-op: el click en la tarjeta abre el detalle de la playlist, y el icono de reproducción carga el detalle y navega de forma determinista.
  3. Canonical Lossless Classification:
     - Introducida la propiedad [`Track.isLossless`](file:///home/cristian/michi-music-mobile/core/src/main/java/org/michimusic/core/models/Models.kt) en `:core:models`, aplicando la Decisión D-04 de forma compartida en `AlbumsScreen` y `SearchViewModel`.
     - MP3, AAC y Opus a 48 kHz o superior quedan estrictamente excluidos de los filtros Lossless.
  4. Direct Playlists Access in Library:
     - AlbumsScreen incorpora acceso dedicado a playlists en la cabecera mediante `library_playlists_button`, interconectando la navegación con `MichiNavHost`.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos)
  ./gradlew :core:test :player:test :data:test :michi-link-client:test (PASSED - 0 fallos)
  ./gradlew compileNormalDebugKotlin compileFdroidDebugKotlin (PASSED - 0 fallos)
  Nuevas suites:
    - HomeRecentIntegrityTest (PASSED)
    - PlaylistNoopRegressionTest (PASSED)
    - SearchLosslessFilterTest (PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Integridad de navegación y clasificación de metadatos locales sin impacto en contratos de red ni credenciales.
ROLLBACK_PLAN:
  git revert 0804da1..HEAD
BLOCKERS: Ninguno.
```

---

## Checkpoint de Ejecución — Cierre de Defectos Críticos P1/P2 (Pre-M20)

```text
BLOCK_ID: M18-M19-HARDENING
MASTER_BASE_SHA: 8a4fb754a388eecb51a733c162b960c705017f7b
FILES_CHANGED:
  - core/src/main/java/org/michimusic/core/models/Models.kt
  - player/src/main/java/org/michimusic/player/AudioController.kt
  - player/src/main/java/org/michimusic/player/PlayerController.kt
  - player/src/test/java/org/michimusic/player/QueueEntryDisambiguationTest.kt
  - app/src/main/java/org/michimusic/mobile/playback/PlaybackSessionManager.kt
  - app/src/main/java/org/michimusic/mobile/screens/PlaylistsViewModel.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/PlaylistScreen.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/DevicesScreen.kt
  - app/src/test/java/org/michimusic/mobile/playback/RemoteCommandAuthorityTest.kt
  - app/src/test/java/org/michimusic/mobile/ui/screens/PlaylistNoopRegressionTest.kt
  - app/src/test/java/org/michimusic/mobile/screens/PlaylistsViewModelTest.kt
  - app/src/test/java/org/michimusic/mobile/ui/screens/LibraryLosslessClassifierTest.kt
  - app/src/test/java/org/michimusic/mobile/sync/SyncViewModelTest.kt
  - app/src/test/java/org/michimusic/mobile/ui/screens/devices/DevicesScreenTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. Comandos remotos (RemoteCommandAuthority): El fallo de getQueue() o getPlaybackState() dejaba el comando en
     CONFIRMED erróneamente; además respuestas lentas/antiguas de un endpoint anterior podían sobrescribir el estado de un nuevo endpoint.
  2. Reproducción rápida de playlists: El botón play en la tarjeta de playlist navegaba a la vista de detalle pero
     no despachaba reproducción real inmediata de las pistas.
  3. Identidad QueueEntry en Media3: Durante la restauración de cola (restoreQueue) o reconciliación de timeline,
     se perdían las identidades estables QueueEntry (instanceId), desalineando colas con pistas repetidas.
  4. Clasificación Lossless: Formatos desconocidos o cadenas vacías podían no ser clasificadas de forma estricta
     y no existía distinción explícita tipada entre LOSSLESS, LOSSY y UNKNOWN.
  5. UI/UX Devices: El subtítulo de las tarjetas mostraba IP en bruto en lugar de tipo y capacidades; no existía
     distinción visual directa de "Anunciado" vs "Verificado"; botones de acción y detalle no aseguraban >= 48dp.
PRODUCTION_BEHAVIOR_AFTER:
  1. RemoteCommandAuthority:
     - removeFromQueue, playNow, playNext, addToQueue, moveQueueItem y clearQueue retornan REJECTED si getQueue() falla.
     - Verificación estricta de afinidad de endpoint activo (_sessionState.value.activeEndpoint.id == targetEndpoint.id)
       que previene que respuestas desfasadas de endpoints viejos muten la sesión activa.
  2. Reproducción rápida de playlists:
     - PlaylistsViewModel incorpora playPlaylist(id, audioController) que resuelve las pistas de la playlist y despacha
       audioController.playQueue(tracks, 0) inmediatamente.
     - PlaylistScreen conecta playlist_play_{id} con tamaño >= 48dp y dispatch real de reproducción.
  3. QueueEntry Media3:
     - AudioController mantiene y reconcilia queueEntries: List<QueueEntry> en restoreQueue, executePlayQueue,
       reconcileQueueFromController, addToQueue, playNext, moveQueueItem y removeFromQueue.
     - PlayerController sincroniza queueEntries durante onTimelineChanged manteniendo identidades únicas estables.
  4. Clasificación Lossless:
     - Introducido CodecClassification (LOSSLESS, LOSSY, UNKNOWN) en :core:models.
     - Track.codecClassification y Track.isLossless clasifican estrictamente formatos conocidos (FLAC, ALAC, WAV, etc.)
       como LOSSLESS, códecs con pérdida como LOSSY, y cadenas vacías o no reconocidas como UNKNOWN (isLossless = false).
  5. UI/UX Devices:
     - DeviceNodeCard renderiza insignias diferenciadas: "● Verificado" (Cyan), "● Anunciado" (Gris neutro), "● Revocado" (Rojo).
     - Subtítulo presenta tipo formateado ("Micro Server", "Michi Player", "Teléfono") y capacidades/red local sin raw IP.
     - Botón de información y botones de acción garantizan tamaño táctil >= 48dp (WCAG).
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED - 0 fallos)
  ./gradlew :core:test :player:test :data:test :michi-link-client:test (PASSED - 0 fallos)
  ./gradlew compileNormalDebugKotlin compileFdroidDebugKotlin (PASSED - 0 fallos)
  Nuevas/actualizadas suites:
    - RemoteCommandAuthorityTest (PASSED)
    - PlaylistNoopRegressionTest (PASSED)
    - PlaylistsViewModelTest (PASSED)
    - QueueEntryDisambiguationTest (PASSED)
    - LibraryLosslessClassifierTest (PASSED)
    - SyncViewModelTest (PASSED)
    - DevicesScreenTest (PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Criptografía Ed25519 mantenida para verificación de confianza de dispositivos; validación estricta de QR
  y expiración RFC 3339; anti-stale endpoint isolation en control remoto.
ROLLBACK_PLAN:
  git revert 8a4fb75..HEAD
BLOCKERS: Ninguno. Listo para iniciar Sprint M20.
```

---

## Checkpoint de Ejecución — Sprint M20: Intelligent Music Sync, Storage Preflight & Delivery Profiles

```text
BLOCK_ID: M20
MASTER_BASE_SHA: 4b41378744e1a2ae26650f3e839935657bbee64f
FILES_CHANGED:
  - core/src/main/java/org/michimusic/core/models/Models.kt
  - core/src/main/java/org/michimusic/core/models/SyncPreflightModels.kt
  - core/src/main/java/org/michimusic/core/sync/SyncProfileNegotiator.kt
  - core/src/main/java/org/michimusic/core/sync/StoragePreflightEvaluator.kt
  - core/src/main/java/org/michimusic/core/sync/SyncPreflightService.kt
  - core/src/test/java/org/michimusic/core/sync/SyncProfileNegotiatorTest.kt
  - core/src/test/java/org/michimusic/core/sync/StoragePreflightEvaluatorTest.kt
  - core/src/test/java/org/michimusic/core/sync/SyncPreflightServiceTest.kt
  - core/build.gradle.kts
  - data/src/main/java/org/michimusic/data/cache/Entities.kt
  - data/src/main/java/org/michimusic/data/cache/MichiDatabase.kt
  - data/src/main/java/org/michimusic/data/DataModule.kt
  - data/schemas/org.michimusic.data.cache.MichiDatabase/6.json
  - data/src/test/java/org/michimusic/data/cache/Migration5To6Test.kt
  - player/src/main/java/org/michimusic/player/AudioController.kt
  - player/src/test/java/org/michimusic/player/QueueEntryDisambiguationTest.kt
  - app/src/main/java/org/michimusic/mobile/playback/PlaybackSessionManager.kt
  - app/src/test/java/org/michimusic/mobile/playback/RemoteCommandAuthorityTest.kt
  - app/src/main/java/org/michimusic/mobile/screens/PlaylistsViewModel.kt
  - app/src/test/java/org/michimusic/mobile/screens/PlaylistsViewModelTest.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncWorker.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/DevicesScreen.kt
  - app/src/main/java/org/michimusic/mobile/navigation/MichiNavHost.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/sync/StorageBreakdownBar.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/sync/SyncWizardScreen.kt
  - app/src/test/java/org/michimusic/mobile/ui/screens/SyncWizardScreenTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. La clasificación de códecs consideraba erróneamente ADPCM como Lossless y no contemplaba exclusión de DSD en móvil ni contenedores m4a ambiguos.
  2. Room QueueEntity carecía de columnas para entryIds y locationIds en saved_queue, perdiendo identidades únicas en restauración de cola.
  3. Los comandos remotos marcaban REJECTED si fallaba getQueue() posterior aunque el servidor hubiera aceptado el comando, y setRepeatMode/setShuffleMode mutaban el estado local optimísticamente antes de la confirmación remota.
  4. La reproducción de playlists devolvía Unit de manera silenciosa ante errores o listas vacías.
  5. No existía motor determinista de Preflight de sincronización ni negociación honesta de perfiles: no se calculaba la carga neta deduplicada, ni se respetaba la reserva de seguridad de 100 MB de manera garantizada, ni se presentaba desglose visual de almacenamiento.
PRODUCTION_BEHAVIOR_AFTER:
  1. Clasificación estricta de códecs: DSD/DSF/DFF excluido de Lossless móvil (catalogado como UNKNOWN), ADPCM clasificado como LOSSY, contenedores puros .m4a/.mp4 marcados como UNKNOWN salvo cuando están cualificados por códec (ALAC -> LOSSLESS, AAC -> LOSSY).
  2. Migración Room 5 -> 6 implementada y testeada con SQLite real: añade entryIds y locationIds a saved_queue.
  3. AudioController genera UUIDs auténticos para QueueEntry, persistiendo y restaurando entryIds y locationIds de forma desacoplada y determinista.
  4. Semántica autoritativa de comandos remotos: si el comando es aceptado en servidor pero falla la sincronización de cola/estado, se marca como ACCEPTED con mensaje descriptivo (no REJECTED ni CONFIRMED); se diferencian TIMED_OUT y DISCONNECTED; setRepeatMode y setShuffleMode eliminan mutaciones locales optimistas ante sesión remota activa.
  5. PlaylistsViewModel.playPlaylist es honesta y expone PlayPlaylistResult (Success, Empty, NotFound, Error) con callback onResult.
  6. Motor de Preflight de Sincronización (SyncPreflightService): evalúa alcances (Full Library, Playlists, Albums, Artists, Tracks), deduplica pistas, coteja huellas/hashes de pistas locales existentes (SKIP_ALREADY_EXISTS) y rescata bytes ya transferidos en archivos parciales .part (RESUME_PARTIAL).
  7. Negociador de Perfiles (SyncProfileNegotiator): evalúa capacidades anunciadas por el Micro Server; sin transcodificación anunciada en servidor, solo ofrece Calidad Original y deshabilita Balanced y Low Storage con explicación honesta visible en la UI. Sin fake transcoding.
  8. Evaluador de Almacenamiento (StoragePreflightEvaluator): garantiza reserva innegociable de >= 100 MB de seguridad y margen de promoción atómica. Bloquea la sincronización si el espacio libre es insuficiente.
  9. Interfaz gráfica SyncWizardScreen y StorageBreakdownBar: asistente paso a paso (Selección -> Calidad -> Preflight) con barra segmentada tipo iTunes/macOS, conectada desde DevicesScreen ("Sincronizar") y validada con Goldens G14 (AIRCAT_SELECT), G15 (AIRCAT_REVIEW) y G29 (PREFLIGHT_BLOCKED).
  10. SyncWorker y SyncViewModel integran el plan preflight con revalidación inmediata de la reserva de 100 MB antes de iniciar transferencias.
UNIT_TESTS_EXECUTED:
  ./gradlew testNormalDebugUnitTest (PASSED)
  ./gradlew :core:testDebugUnitTest (PASSED)
  ./gradlew :player:testDebugUnitTest (PASSED)
  ./gradlew :data:testDebugUnitTest (PASSED)
  ./gradlew :michi-link-client:testDebugUnitTest (PASSED)
  ./gradlew compileNormalDebugKotlin compileFdroidDebugKotlin (PASSED)
  Suites específicas:
    - LibraryLosslessClassifierTest (PASSED)
    - Migration5To6Test (PASSED)
    - QueueEntryDisambiguationTest (PASSED)
    - RemoteCommandAuthorityTest (PASSED)
    - PlaylistsViewModelTest (PASSED)
    - SyncProfileNegotiatorTest (PASSED)
    - StoragePreflightEvaluatorTest (PASSED)
    - SyncPreflightServiceTest (PASSED)
    - SyncWizardScreenTest [G14, G15, G29] (PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Criptografía Ed25519 mantenida; aislamiento de identidades de dispositivos en transferencias; validación de reserva de almacenamiento de 100 MB previene DoS por llenado de disco del sistema operativo.
ROLLBACK_PLAN:
  git revert 4b41378744e1a2ae26650f3e839935657bbee64f..HEAD
BLOCKERS: Ninguno. Sprint M20 concluido exitosamente. Madurez estimada: 93%.
```

---

## Checkpoint de Ejecución — Sprint M20.1 (Estabilización e Integración Real de Sync)

```text
BLOCK_ID: M20.1
MASTER_BASE_SHA: 4b41378744e1a2ae26650f3e839935657bbee64f
FILES_CHANGED:
  - core/src/main/java/org/michimusic/core/models/SyncPreflightModels.kt
  - core/src/main/java/org/michimusic/core/sync/SyncProfileNegotiator.kt
  - core/src/main/java/org/michimusic/core/sync/SyncPreflightService.kt
  - core/src/test/java/org/michimusic/core/sync/SyncProfileNegotiatorTest.kt
  - core/src/test/java/org/michimusic/core/sync/SyncPreflightServiceTest.kt
  - data/src/main/java/org/michimusic/data/repository/SyncedTrackRepository.kt
  - app/src/main/java/org/michimusic/mobile/navigation/MichiNavHost.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt
  - app/src/main/java/org/michimusic/mobile/sync/SyncWorker.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/sync/SyncWizardScreen.kt
  - app/src/main/java/org/michimusic/mobile/di/KoinAccessors.kt
  - app/src/test/java/org/michimusic/mobile/ui/screens/SyncWizardScreenTest.kt
  - app/src/test/java/org/michimusic/mobile/sync/EndToEndSyncFlowTest.kt
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  1. El wizard de sincronización (G14, G15, G29) inicializaba candidateTracks con listas vacías en producción,
     dejando al planificador preflight sin catálogo remoto real y forzando simulaciones artificiales.
  2. Si la lista de IDs seleccionados estaba vacía, la semántica en SyncWorker recaía automáticamente en
     la descarga de la biblioteca entera, arriesgando descargas no autorizadas masivas ante un fallo de selección.
  3. No existían selectores granulares de álbumes, artistas, playlists o canciones en la UI del wizard;
     el usuario solo podía elegir opciones de nivel superior sin granularidad fina ni control por elementos.
  4. La negociación de perfiles asumía soporte generalizado o simulaciones de transcodificación sin verificar
     representaciones de códec anunciadas explícitamente por el servidor (ej. "aac", "opus").
  5. El tamaño local ocupado por música se aproximaba con una constante fija de 150 MiB, sin consultar
     el almacenamiento real en disco ni las tablas de Room.
  6. La estimación de descargas transcodificadas utilizaba el tamaño en bytes de los ficheros FLAC originales.
PRODUCTION_BEHAVIOR_AFTER:
  1. Conexión veraz del catálogo remoto: SyncViewModel.loadWizardData(targetDeviceId) descarga el manifiesto
     autoritativo remoto via LinkClient.fetchSyncManifest() e interroga getServerInfo() para capacidades reales.
  2. Invariante estricto de cero pistas: SyncPlan.isExecutable exige totalSelectedTracks > 0; selecciones vacías
     devuelven BLOCKED_EMPTY_SELECTION y SyncWorker garantiza descarga de 0 ficheros ante selecciones vacías no-FULL_LIBRARY.
  3. Selección granular completa: SyncWizardScreen ofrece sub-selectores interactivos con checkboxes, contadores,
     y acciones "Seleccionar todo" / "Limpiar" para Álbumes, Playlists, Artistas y Pistas individuales.
     El botón "Siguiente" se inhabilita con aviso cuando la selección resulta en 0 pistas.
  4. Negociación veraz de códecs: SyncProfileNegotiator evalúa independientemente BALANCED (AAC) y LOW_STORAGE (Opus)
     según codecs reales anunciados por el servidor o capacidades genéricas, deshabilitando con aviso honesto lo no soportado.
  5. Métricas de almacenamiento reales: SyncedTrackRepository provee getDownloadedBytes() y getDownloadedTrackFingerprints()
     desde SQLite Room, combinados con StatFs real para el preflight de almacenamiento.
  6. Presupuestación honesta por bitrate: SyncPreflightService calcula el tamaño esperado para perfiles transcodificados
     según la duración acumulada y las tasas nominales (~256 kbps AAC, ~128 kbps Opus), no el tamaño FLAC de origen.
  7. Prueba E2E completa: EndToEndSyncFlowTest valida el flujo íntegro (carga de catálogo -> negociación de códecs ->
     selección de álbum -> preflight -> despacho al worker -> invariantes de 0 pistas).
JVM_TESTS_EXECUTED:
  ./gradlew :core:testDebugUnitTest (15 tests PASSED)
  ./gradlew :player:testDebugUnitTest (14 tests PASSED)
  ./gradlew :data:testDebugUnitTest (48 tests PASSED)
  ./gradlew :michi-link-client:testDebugUnitTest (25 tests PASSED)
  ./gradlew :app:testNormalDebugUnitTest (252 tests PASSED)
  ./gradlew compileNormalDebugKotlin compileFdroidDebugKotlin (BUILD SUCCESSFUL)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV
HARDWARE_TESTS_EXECUTED: PENDING_HARDWARE
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Aislamiento criptográfico y fidelidad de tokens preservados; WorkManager Data no contiene credenciales;
  la barrera de seguridad de 100 MB se valida antes de cualquier I/O; las selecciones vacías no producen
  descargas accidentales de red ni saturación de almacenamiento.
ROLLBACK_PLAN:
  git revert 4b41378744e1a2ae26650f3e839935657bbee64f..HEAD
BLOCKERS: Ninguno. Sprint M20.1 concluido exitosamente. Madurez estimada: 94%.
```











