# Baseline Inventory & Risk Matrix — Mega Sprint M48 (Aero PR7 Final Closure)

Fecha: 2026-10-31  
Repositorio: `pitydah/michi-music-mobile`  
Rama: `integration/m46d-aero-premium`  
HEAD de inicio: `61e73aa14f6a2e94f8d59028df6941845afbd3d9`  
PR: #7 (`https://github.com/pitydah/michi-music-mobile/pull/7`)  

---

## 1. Entorno de Herramientas y Diagnóstico de Dispositivos

| Herramienta | Ruta / Comando | Estado Observado |
|---|---|---|
| JDK | OpenJDK 17.0.14 64-Bit | Disponible y funcional |
| Gradle | `./gradlew` (Gradle 8.9 / AGP 8.9.1) | Funcional |
| Android SDK | `/home/cristian/Android/Sdk` | Instalado (API 35, Build-tools 35.0.0) |
| `adb` | `/home/cristian/Android/Sdk/platform-tools/adb` | Disponible. `adb devices -l`: Ningún dispositivo físico conectado |
| `emulator` | `/home/cristian/Android/Sdk/emulator/emulator` | Disponible. AVD detectado: `Medium_Phone` |

---

## 2. Matriz de Defectos K47-01 a K47-07

| ID | Ubicación | Diagnóstico del Defecto | Prueba Roja | Corrección Prevista |
|---|---|---|---|---|
| **K47-01** | `PlaybackSessionManager.kt` (`requestQueuePlayback`) | Condición `q.tracks.size >= tracks.size` y `trackMatches = ID || índice` confirma colas con pistas extra, permutadas o con track erróneo en el índice. | `PlaybackSessionManagerTest` (`requestQueuePlayback_withExtraTracks_isNotConfirmed`, `withPermutedTracks_isNotConfirmed`) | Crear verificador puro `RemoteQueueConfirmation` con coincidencia exacta de IDs y orden, estado playing y separación de `QUEUE_SYNCED`, `COMMAND_ACCEPTED`, `PLAYBACK_ACTIVE`. |
| **K47-02** | `SignalTruthResolver.kt` (`resolveProvenance`) | Host remoto lee `observation.sourceFormat` del tracker local, mostrando metadatos de pistas locales previas como formato del host remoto. | `SignalTruthResolverTest` (`remotePlayback_neverInheritsLocalSourceFormat`) | Derivar Fuente remota SOLO de metadatos autorizados del host/track remoto (`sessionState.activeTrack` o LinkClient). Si no hay dato -> `UNKNOWN`. |
| **K47-03** | `SignalTruthResolver.kt` | Defaults inventados `bitDepth=16` y `sampleRate=44.1 kHz` cuando las medidas no existen o son <= 0; `freshnessMs` inventado con `System.currentTimeMillis()`. | `SignalTruthResolverTest` (`invalidSampleRateOrBitDepth_isUnknown_neverDefaultsTo16_44`) | Eliminar defaults. Si valores son inválidos/cero -> `UNKNOWN`. `freshnessMs` debe provenir de `observation.observedAtMs`. |
| **K47-04** | `RemoteViewModel.kt` | Fallback en bloque `when`: asigna `RemoteConnectionState.CONNECTED` y `connected = true` a cualquier estado no reconocido o ausente. | `RemoteViewModelTest` (`unmappedOrAbsentConnectionState_isDisconnected_neverConnected`) | Mapeo exhaustivo de `SyncConnectionState` a `RemoteConnectionState`. Ausente = `DISCONNECTED` y `connected = false`. |
| **K47-05** | `DevicesScreen.kt` | Atajo «Sincronizar con AirCat» usa `activeDevices.firstOrNull()?.id ?: ""` y navega a `sync_wizard` con ID vacío o endpoint incompatible. `AirCatTransferSafeLayout` asume SHA-256 verificado sin telemetría. | `DevicesScreenTest` (`airCatShortcut_withoutEligibleDevices_showsGuidance_neverNavigatesEmptyId`), `AirCatSafeLayoutTest` | Selector de destino AirCat validando capacidades; navegación protegida. En `AirCatTransferSafeLayout`, requerir evidencia de `checksumVerified && libraryCommitted`. |
| **K47-06** | `PlaybackSessionManager.kt` | Comparación de epoch ocurre tras `transferQueue`. Comandos obsoletos o en carrera pueden lanzar `play` o mutar UI. No hay serialización/cancelación estructurada por endpoint/operación. | `PlaybackSessionManagerTest` (`cancelledOrSupersededOperation_doesNotTriggerSubsequentRemoteCommands`) | Cancelación estructurada y comprobación de generación/operación antes de cada efecto remoto, tras suspender y antes de mutar StateFlow. |
| **K47-07** | `MichiCoverFlowHost.kt` / Shell | Pruebas Compose Robolectric exhaustivas de CoverFlow ante filtrado, ordenamiento y swipes rápidos; comprobación de insets y MiniPlayer sin solapes. | `MichiCoverFlowHostTest` | Pruebas Compose con simulación de filtrado y animaciones concurrentes. |

---

## 3. Línea Base de Compilación y Suites Previas
- `:core:testDebugUnitTest`: PASS
- `:michi-link-client:testDebugUnitTest`: PASS
- `:player:testDebugUnitTest`: PASS
- `:data:testDebugUnitTest`: PASS
- `:app:testNormalDebugUnitTest`: PASS
- `:app:testFdroidDebugUnitTest`: PASS
- `:app:assembleNormalDebug`: PASS
- `:app:assembleFdroidDebug`: PASS
