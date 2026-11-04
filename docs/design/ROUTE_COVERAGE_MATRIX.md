# Michi Aero · Matriz canónica de rutas AMR-80

**Referencia:** `pitydah/michi-music-mobile`, `master` @ `09a48411617daf81d2f93576fd27690da8c7962f`. La fuente de verdad de estas rutas es `app/src/main/java/org/michimusic/mobile/navigation/MichiNavHost.kt` del mismo SHA.

**Estado técnico del corte:** `PARTIAL_PATCH_IN_PROGRESS`; ninguna modificación está aplicada en `master`. `SOURCE_EDITED` significa intervención real dentro del diff acumulativo **pero no compilación ni prueba Compose**; por eso no equivale a `TESTED_OFFLINE`. Se prioriza la autoridad de los datos sobre la apariencia.

| Ruta declarada | Archivo de UI principal | Intervención material incluida | Código | Suite Android |
|---|---|---|---|---|
| `home` | `HomeScreen.kt` | Hero Anodized, continuidad, listas discretas | SOURCE_EDITED | NOT_RUN |
| `library` | `AlbumsScreen.kt` | Colecciones y filas Plain, CoverFlow preservado | SOURCE_EDITED | NOT_RUN |
| `search` | `SearchScreen.kt` | Campo Inset; filas Plain; IME y estados conservados | SOURCE_EDITED | NOT_RUN |
| `devices` | `DevicesScreen.kt` | Equipos conectados vs descubiertos; sheet de identidad | SOURCE_EDITED | NOT_RUN |
| `sync_wizard/{deviceId}` | `sync/SyncWizardScreen.kt` | Opciones, perfiles, resumen preflight y detalle de decisiones | SOURCE_EDITED | NOT_RUN |
| `nowplaying` | `NowPlayingScreen.kt` | Portada única, Signal Strip compacto ciruela de dos columnas | SOURCE_EDITED | NOT_RUN |
| `settings` | `SettingsScreen.kt` | Acciones satén, secciones anodizadas, controles Plain | SOURCE_EDITED | NOT_RUN |
| `playlist` | `PlaylistScreen.kt` | Colecciones Plain sin grano repetido | SOURCE_EDITED | NOT_RUN |
| `playlist/{playlistId}` | `PlaylistDetailScreen.kt` | Filas Plain y acciones de orden originales | SOURCE_EDITED | NOT_RUN |
| `queue` | `QueueScreen.kt` | Resumen satén, fila activa y modal por índice | SOURCE_EDITED | NOT_RUN |
| `synced` | `SyncedTracksScreen.kt` | Fila offline de solo descarga local verificable | SOURCE_EDITED | NOT_RUN |
| `diagnostics` | `DiagnosticsScreen.kt` | Telemetría Plain y reportes expurgados | SOURCE_EDITED | NOT_RUN |
| `audio-route` | `AudioRouteScreen.kt` | Observado anodizado vs anunciado Plain | SOURCE_EDITED | NOT_RUN |
| `remote` | `RemoteScreen.kt` | Panel de host y salida solicitada con evidencia real | SOURCE_EDITED | NOT_RUN |
| `sync` | `SyncScreen.kt` | Discovery y transferencia AirCat con progreso observado | SOURCE_EDITED | NOT_RUN |

**Total de inventario:** 15/15 rutas con archivo de UI intervenido o su composición compartida; **0/15 rutas con suites completas ejecutadas en Gradle**. Se evita llamar 15/15 “certificado”. Para cerrar el parche, cada ruta requiere verificación offline de datos reales, callbacks, loading/error/empty, alto contraste y accesibilidad; las capturas en Android físico no son requisito por decisión del usuario.

### Subflujos transversales realmente intervenidos

- `screens/sync/SyncComponents.kt`: prompt, candidatos LAN `Plain`, credenciales, conectado, transferencia AirCat `Raised+Anodized`; no altera autorización, checksum, reintentos ni contadores.
- `screens/sync/StorageBreakdownBar.kt`: carril `Inset` sin pintar el porcentaje como éxito; conserva colores de sistema, música local, próxima descarga, reserva y espacio libre.
- `screens/sync/SyncWizardScreen.kt`: Scope/Profile retienen habilitado, selección y callback; preflight queda en canal `Inset`; no modifica cantidades calculadas ni el gate de bloqueo.
- `screens/devices/DeviceDetailSheet.kt`: disclosure de identidad y capacidades `Content+Inset`, target único y badge de estado de confianza preexistente; no confundir anunciado y verificado.
- `artist/ArtistEnrichmentPanel.kt`: solo acabados de candidatos, MBID confirmado por elección, licencias/atribución sin cambios; no dispara búsqueda externa automáticamente.
- `components/TrackActionMenu.kt`: diálogo Playlist comparte Floating+Smoked y filas Plain; conserva operaciones atómicas y mensajes reales.
- `components/DialogComponents.kt`: modales originalmente sobre `AeroFloatingSurface`, campos repetidos reciben radio 12dp, sin cambiar IME/credenciales/callbacks.

### Estado que falta verificar

El repositorio parcial consultado no dispone de SDK, todas las dependencias del proyecto ni renderer Android. Por tanto: las líneas y el diff pueden inspeccionarse, pero NO se afirma compilación Kotlin/Compose completa, ejecución Robolectric, rendimiento real ni cobertura interactiva total. Ver `MODAL_COVERAGE_MATRIX.md`.
