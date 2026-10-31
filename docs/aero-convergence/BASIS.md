# MICHI MUSIC MOBILE — MATRIZ DE BASELINE Y CONGELACIÓN (GATE 0)
**Fecha:** 31 de Octubre de 2026  
**Rama:** `integration/m46d-aero-premium`  
**HEAD Baseline:** `327aa701f8ba2d29beeb32c6ac30b6622ce20d50`  
**PR:** [#7 en pitydah/michi-music-mobile](https://github.com/pitydah/michi-music-mobile/pull/7)  

---

## 1. INVENTARIO DE RUTAS, FUENTES DE DATOS Y CONTRATOS VISUALES

| Ruta / Componente | Archivo Fuente | Fuente de Datos / Modelo | Acciones Principales | Manejo de Error / Estados Límite | Tests Actuales | Referencia Visual Canónica |
|---|---|---|---|---|---|---|
| **Now Playing** | `ui/screens/NowPlayingScreen.kt` | `PlaybackSessionManager.sessionState`, `AudioController.state`, `SignalTruthTracker` | Play, Pause, Next, Prev, Seek, Shuffle, Repeat, Toggle Favorite, Signal Truth Dialog | `sessionState.currentTrack ?: localState` (K46D-49); `lastRemoteCommand.status == PENDING` para spinner | `NowPlayingSemanticsTest.kt`, `NowPlayingNoVolumeTest.kt` | `V13_02_ESTADOS_NOW_PLAYING.png`, `V13_06_SIGNAL_TRUTH.png` |
| **MiniPlayer** | `ui/components/MiniPlayer.kt` | `PlaybackSessionManager.sessionState`, `AudioController.state` | Tap to open NowPlaying, Play/Pause, Next | `currentTrack = sessionState ?: localState` (K46D-49 riesgo); timeline oculta si duration <= 0 | `MiniPlayerVisualTest.kt` (o integrado en NavHost) | `V13_00_COMPONENTES_ICON_ONLY.png`, `V13_01_ATLAS_CANONICO.png` |
| **CoverFlow / Álbumes** | `ui/coverflow/MichiCoverFlowHost.kt`, `ui/screens/AlbumsScreen.kt` | `LocalMediaRepository.getAllAlbums()`, `sessionManager.sessionState` | Pager scroll, select album, play album, play track, toggle view | `LaunchedEffect(selectedAlbumId)` vs `LaunchedEffect(currentPage)` riesgo de carrera (K46D-48) | `CoverFlowGeometryTest.kt`, `CoverFlowMotionPolicyTest.kt` | `V13_07_SEARCH_COVER_SETTINGS.png`, `V13_01_ATLAS_CANONICO.png` |
| **Michi Devices** | `ui/screens/DevicesScreen.kt`, `ui/screens/devices/` | `SyncViewModel.uiState`, `PlaybackSessionManager.sessionState` | Discover, Connect, Pair PIN/Password, AirCat transfer, Sync library, Handoff, View details | Card de AirCat activo; Dialogs de PIN/Manual; supresión de botón físico para Stream | `DevicesScreenTest.kt`, `DeviceActionResolverTest.kt` | `V13_05_DEVICES_REMOTE.png`, `V13_01_ATLAS_CANONICO.png` |
| **Michi Control / Remote** | `ui/screens/RemoteScreen.kt` | `RemoteViewModel.uiState`, `PlaybackSessionManager` | Play, Pause, Next, Prev, Handoff to local, Volume, Connect | Estados `DISCONNECTED`, `CONNECTING`, `CONNECTED`; handoff asíncrono; sin fallback de portada local | `RemoteScreenTest.kt` | `V13_05_DEVICES_REMOTE.png` |
| **AirCat / Sincronización** | `ui/screens/sync/SyncWizardScreen.kt`, `AirCatTransferClient.kt` | `SyncPreflightService`, `StoragePreflightEvaluator`, `AirCatTransferClient` | Selection, Quality, Review, Start sync, Cancel, Retry | Preflight de almacenamiento; modal fullscreen sin dock de MiniPlayer superpuesto | `SyncPreflightServiceTest.kt`, `StoragePreflightTest.kt` | `V13_03_AIRCAT_SAFE_LAYOUT.png`, `V13_04_AIRCAT_ERROR.png` |
| **Cola de Reproducción** | `ui/screens/QueueScreen.kt` | `PlaybackSessionManager.sessionState` | Reorder, Remove, Clear, Play track | Muestra estrictamente `sessionState.queue` en remoto; estado vacío honesto | `QueueScreenTest.kt` | `V13_01_ATLAS_CANONICO.png` |
| **Shell & Dock** | `navigation/MichiNavHost.kt`, `ui/components/BottomNavBar.kt` | `NavController`, `PlaybackSessionManager.sessionState` | Navegación entre 4 tabs: Inicio, Biblioteca, Buscar, Dispositivos | Scaffold insets coordinados; dock offset para MiniPlayer; spacers de lista a 24dp | `SettingsHierarchyTest.kt`, tests de navegación | `V13_00_COMPONENTES_ICON_ONLY.png`, `V13_01_ATLAS_CANONICO.png` |

---

## 2. AUDITORÍA DE RIESGOS Y DEFECTOS K46D-45 A K46D-50

1. **K46D-45 (Autoridad de Reproducción Remota Prematura / Falso CONFIRMED):**
   - *Ubicación:* `PlaybackSessionManager.kt:1162-1191`
   - *Riesgo:* `stRes.isSuccess && qRes.isSuccess` marca `synced = true` sin validar que `st.playing == true`, que `q.tracks` coincida con la cola solicitada, ni que `st.queueIndex == validIndex`.
2. **K46D-46 (Comandos Tardíos / Ausencia de Session Epoch):**
   - *Ubicación:* `PlaybackSessionManager.kt`
   - *Riesgo:* No existe `sessionEpoch` o generation counter. Si el usuario conmuta de host a teléfono mientras `transferQueue` está en curso, se dispara `play` al host anterior y se sobrescribe `_sessionState` con la respuesta tardía.
3. **K46D-47 (Signal Truth con Datos Locales Espurios en Remoto):**
   - *Ubicación:* `NowPlayingScreen.kt:686-735`
   - *Riesgo:* En modo remoto, si `observation.sourceFormat` contiene lecturas locales previas, se muestran como formato remoto; "Sin EQ activo" se declara como hecho configurado cuando en el host es `UNKNOWN`.
4. **K46D-48 (CoverFlow Pager Feedback Loop / Deselección):**
   - *Ubicación:* `MichiCoverFlowHost.kt:75-93`
   - *Riesgo:* `LaunchedEffect(selectedAlbumId)` anima el scroll mientras `LaunchedEffect(state.currentPage)` emite `onAlbumSelected` en cada página intermedia de la animación, generando rebote o aterrizaje erróneo. Si el álbum seleccionado es eliminado o filtrado, no se selecciona un sucesor válido.
5. **K46D-49 (Inconsistencia de Metadatos entre MiniPlayer y Now Playing):**
   - *Ubicación:* `MiniPlayer.kt:79`, `NowPlayingScreen.kt:129`, `HomeScreen.kt:114`, `SearchScreen.kt:344`
   - *Riesgo:* El patrón `sessionState.currentTrack ?: localState.currentTrack` mezcla la carátula o metadatos locales con el estado `isPlaying` o posición de un nodo remoto que está vacío o sincronizando.
6. **K46D-50 (Desincronización de Cola y Transiciones Inconsistentes):**
   - *Ubicación:* `QueueScreen.kt`, `PlaybackSessionManager.kt`
   - *Riesgo:* Pérdida de estado de cola o transiciones sin limpiar cuando se desconecta un endpoint o se conmuta de salida.

---

## 3. LÍNEA BASE DE COMPILACIÓN Y PRUEBAS

* **Rama:** `integration/m46d-aero-premium`
* **Commit:** `327aa701f8ba2d29beeb32c6ac30b6622ce20d50`
* **Tests Unitarios `:app`:** 414 tests aprobados en NormalDebug y FdroidDebug.
* **Tests Módulos Satélite:** `:player`, `:core`, `:data`, `:michi-link-client` aprobados.
* **Lint:** `:app:lintNormalDebug` 0 errores.
* **Builds:** `:app:assembleNormalDebug` y `:app:assembleFdroidDebug` exitosos.
