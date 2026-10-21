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
