# Matriz de Pruebas y Certificación — Mega Sprint M47 (Aero Convergence)

Fecha de ejecución: 2026-10-31  
Rama: `integration/m46d-aero-premium`  
HEAD Commit: `0ed9309`  
Entorno de ejecución: Linux x86_64 / JDK 17 (OpenJDK 64-Bit) / Android SDK 35

---

## 1. Resumen Ejecutivo de Verificación

| Suite / Módulo | Tareas Gradle | Estado | Tipología de Pruebas |
|---|---|---|---|
| `:core` | `testDebugUnitTest` | **PASSED** (0 fallos) | Contratos de modelos, serialización, tipado estricto |
| `:michi-link-client` | `testDebugUnitTest` | **PASSED** (0 fallos) | SSE, protocolo Link, resolución de capacidades |
| `:player` | `testDebugUnitTest` | **PASSED** (0 fallos) | Motor ExoPlayer, capas de Signal Truth, ReplayGain |
| `:data` | `testDebugUnitTest` | **PASSED** (0 fallos) | Room DAOs, repositorios, persistencia local |
| `:app` (Normal Debug) | `testNormalDebugUnitTest` | **PASSED** (0 fallos) | Robolectric Compose UI, ViewModels, sincronización de sesión |
| `:app` (F-Droid Debug) | `testFdroidDebugUnitTest` | **PASSED** (0 fallos) | Compilación y tests de variante 100% FLOSS |
| `:app` (Build Packaging) | `assembleNormalDebug` & `assembleFdroidDebug` | **SUCCESS** | Generación binaria de APKs para ambas variantes |

---

## 2. Matriz Detallada por Compuerta (Gates 0 a H)

### Compuerta A: Autoridad Remota, CONFIRMED Verificable y Epoch de Sesión (K46D-45 / K46D-46)
- **Ficheros:** `PlaybackSessionManager.kt`, `RemoteCommandModels.kt`, `PlaylistsViewModel.kt`
- **Tests ejecutados:** `PlaybackSessionManagerTest`
- **Casos verificados:**
  1. `optimisticUpdate_rollsBack_whenRemoteRejectsCommand`: Reversión atómica y emisión de `RemoteCommandStatus.REJECTED` al fallar el endpoint remoto.
  2. `rapidCommands_onlyLatestPendingIsKept`: Prevención de flooding y garantía de consistencia ante secuencias rápidas de comandos.
  3. `serverDisconnect_clearsPendingCommands_andMarksDisconnected`: Detección de pérdida de transporte SSE/HTTP y transición inmediata a `DISCONNECTED`.
  4. `staleResponse_fromPreviousTarget_isIgnored`: Validación estricta de Session Epoch (`_sessionEpoch`); respuestas tardías de un target previo quedan invalidadas.
  5. `activeEndpoint_tracksCorrectTarget`: Gestión de targets sin colisión de identidades.
- **Veredicto:** `TESTED ON JVM/ROBOLECTRIC` — **APROBADO**.

---

### Compuerta B: Fuente Única de Verdad (Single Source of Truth / K46D-49)
- **Ficheros:** `ActivePlaybackPresentation.kt`, `MiniPlayer.kt`, `NowPlayingScreen.kt`, `HomeScreen.kt`, `SearchScreen.kt`, `AlbumsScreen.kt`
- **Tests ejecutados:** `ActivePlaybackPresentationTest`
- **Casos verificados:**
  1. `resolvePlaybackPresentation_whenRemoteActive_prioritizesRemoteSession`: Cuando un endpoint remoto está activo, la UI proyecta exclusivamente el estado del host remoto.
  2. `resolvePlaybackPresentation_whenLocalActive_usesLocalController`: En reproducción local autónoma, proyecta `AudioController`.
  3. `resolvePlaybackPresentation_idleState`: Estado inactivo sin pistas fantasmas.
  4. `resolvePlaybackPresentation_michiStream_preprovisionedReceiver`: Endpoint Stream gestionado como salida directa I2S/PCM sin pairing físico.
  5. `resolvePlaybackPresentation_remoteQueueDesync_resolvedCleanly`: Cola remota mapeada sin interferencia de la cola local.
- **Veredicto:** `TESTED ON JVM/ROBOLECTRIC` — **APROBADO**.

---

### Compuerta C: Capas de Verdad de Señal (Signal Truth Layered Authority / K46D-47)
- **Ficheros:** `SignalTruthResolver.kt`, `SignalProvenanceItem.kt`, `SignalLayer.kt`, `SignalEvidenceType.kt`, `NowPlayingScreen.kt`
- **Tests ejecutados:** `SignalTruthResolverTest`
- **Casos verificados:**
  1. `localPlayback_noDsp_showsCleanDirectSignal`: Pipeline limpio sin DSP con resolución de capas de audio.
  2. `remotePlayback_preservesOriginMetadata_andAudioSinkEvidence`: Muestra procedencia de origen de archivo y decodificador remoto.
  3. `lossyFormat_reportsExactContainerAndBitrate`: Formatos MP3/AAC reflejan bitrate y contenedor exacto.
  4. `losslessFlac_reportsBitDepthAndSampleRate`: FLAC muestra profundidad de bits y frecuencia de muestreo exacta (sin claims falsos de DSD).
  5. `activeReplayGain_addsGainStageLayer`: Ganancia ReplayGain añadida en la capa canónica de volumen.
  6. `activeEqualizer_addsEqualizationLayer`: Equalizador de 5 bandas reflejado como etapa de procesamiento DSP.
  7. `multiDsp_reportsPipelineInStrictOrder`: Orden estricto de la cadena: Origen -> Decodificación -> DSP/EQ -> Control de Ganancia -> Salida.
- **Veredicto:** `TESTED ON JVM/ROBOLECTRIC` — **APROBADO**.

---

### Compuerta D: Rediseño Monumental de Michi Devices (Prioridad Visual 1)
- **Ficheros:** `DevicesScreen.kt`
- **Diseño de referencia:** `V13_05_DEVICES_REMOTE.png` (pantalla izquierda)
- **Tests ejecutados:** `DevicesScreenTest`
- **Casos verificados:**
  1. `devicesScreen_editorialHeaderAndSections`: Renderizado del encabezado editorial, tarjetas de nodo con roles (Desktop Host, Michi Stream, Servidor), insignias de confianza criptográfica y CTA exclusivo para AirCat.
  2. `deviceNodeCard_verifiedDevice`: Verificación de tokens de sesión y certificados.
  3. Invariante Michi Stream: Receptor I2S/PCM preaprovisionado sin solicitud de pulsación de botón físico.
- **Veredicto:** `TESTED ON JVM/ROBOLECTRIC` & `VERIFIED ON BUILD` — **APROBADO**.

---

### Compuerta E: Rediseño de Michi Control / Remote (Prioridad Visual 2)
- **Ficheros:** `RemoteScreen.kt`, `RemoteViewModel.kt`, `MichiNavHost.kt`
- **Diseño de referencia:** `V13_05_DEVICES_REMOTE.png` (pantalla derecha)
- **Tests ejecutados:** `RemoteScreenTest`
- **Casos verificados:**
  1. `remoteScreenContent_connectedState_displaysControlsAndTargetSizes`:
     - Encabezado editorial con botón atrás, título con nombre del dispositivo (`remote_title`), subtítulo de estado (`Conectado`).
     - Carátula con esquinas redondeadas (`28.dp`), título de pista, artista y álbum.
     - Barra de progreso interactiva con marcas de tiempo.
     - **Pictogramas desnudos puros:** Controles `remote_prev` (48x48 dp), `remote_play_pause` (64-72 dp) y `remote_next` (48x48 dp) sin discos, anillos ni burbujas de fondo.
     - Tarjeta "SALIDA SELECCIONADA" con chip activo.
     - Botón de handoff "Continuar en este teléfono".
  2. `remoteScreenContent_pendingCommand_displaysFeedbackPill`: Píldora ámbar ante órdenes pendientes.
  3. `remoteScreenContent_disconnectedState_displaysSearchCta`: Estado sin conexión con CTA "Buscar Dispositivos".
- **Veredicto:** `TESTED ON JVM/ROBOLECTRIC` & `VERIFIED ON BUILD` — **APROBADO**.

---

### Compuerta F: Experiencia Integral de AirCat y Asistente de Sincronización (Prioridad Visual 3)
- **Ficheros:** `SyncComponents.kt`, `AirCatTransferClient.kt`, `SyncViewModel.kt`, `DevicesScreen.kt`
- **Diseños de referencia:** `V13_03_AIRCAT_SAFE_LAYOUT.png` y `V13_04_AIRCAT_ERROR.png`
- **Tests ejecutados:** `AirCatContractTest`, `AirCatSafeLayoutTest`
- **Casos verificados:**
  1. `airCatSafeLayout_activeTransfer_displaysSteppersAndAtomicNotice`: Stepper de 4 fases (Preparación -> Transferencia -> Verificando -> Completado), bytes transferidos en vivo, aviso de checkpoint atómico ("Transferencia atómica: si se interrumpe, se conservan los archivos validados").
  2. `airCatSafeLayout_recoveringError_displaysPreservedFilesNoticeAndRetryAction`: Estado de error recuperable con mensaje "Se conservaron los archivos validados", botón "Reintentar" y botón "Cerrar".
  3. `airCatSafeLayout_completedTransfer_displaysSuccessConfirmation`: Estado de finalización con confirmación de integridad SHA-256 y botón "Aceptar".
  4. `airCatTransfer_atomicPromotionAndPartialResume`: Verificación de que descargas interrumpidas conservan archivos `.part` y no corrompen la biblioteca local.
- **Veredicto:** `TESTED ON JVM/ROBOLECTRIC` & `VERIFIED ON BUILD` — **APROBADO**.

---

### Compuerta G: Convergencia de CoverFlow, Cola y Shell (K46D-48 & K46D-50)
- **Ficheros:** `MichiCoverFlowHost.kt`, `QueueScreen.kt`, `MichiNavHost.kt`, `NavigationPresentationPolicy.kt`
- **Tests ejecutados:** `MichiCoverFlowHostTest`, `CoverFlowGeometryTest`, `CoverFlowMotionPolicyTest`, `QueueScreenTest`, `NavigationPresentationPolicyTest`, `ComposeNavigationIntegrationTest`
- **Casos verificados:**
  1. `coverFlowHost_initialState_centersInitialAlbum`: Álbum inicial centrado y seleccionado sin rebotes.
  2. `coverFlowHost_clickingSideAlbum_scrollsAndSelectsTarget`: Al tocar carátula lateral se desplaza de forma fluida y se actualiza la selección en el settledPage.
  3. `coverFlowHost_whenSelectedAlbumDeleted_recoversGracefullyWithoutCrash`: Manejo robusto ante álbumes eliminados o filtrados; recuperación automática a índice válido.
  4. `coverFlowHost_whenAlbumListShrinks_clampsToLastValidIndex`: Clamping ante reducción de biblioteca.
  5. `queueScreen_whenRemoteActiveAndEmpty_doesNotFallBackToLocalQueue`: La cola remota no cae falsamente a la cola local del teléfono.
  6. `queueScreen_whenRemoteQueueSyncPending_showsPendingMessage`: Notificación de sincronización con host remoto.
  7. `navigationPresentationFor`: Estabilidad de insets y 4 pestañas de navegación (Inicio, Biblioteca, Buscar, Dispositivos).
- **Veredicto:** `TESTED ON JVM/ROBOLECTRIC` & `VERIFIED ON BUILD` — **APROBADO**.

---

## 3. Clasificación de Rigor Adversarial

| Dimensión | Estado de Certificación | Observaciones |
|---|---|---|
| Lógica de Dominio y Modelos | `TESTED ON JVM/ROBOLECTRIC` | Cobertura total de contratos, serialización y validaciones |
| Interfaz Compose y Semántica | `TESTED ON JVM/ROBOLECTRIC` | Verificación de jerarquías de árbol semántico, tamaños de toque y tags |
| Empaquetado Binario | `VERIFIED ON BUILD` | APKs `NormalDebug` y `FdroidDebug` construidos satisfactoriamente |
| Reproducción de Hardware Físico | `NOT_TESTED_ON_DEVICE` | DAC externo USB, hardware Stream I2S físico y transceptor físico Wi-Fi P2P sin hardware adjunto en entorno CI/agente |
