# Michi Aero · Auditoría de componentes compartidos (AMR-90)

| Componente | Decisión | Evidencia disponible |
|---|---|---|
| `AeroSurface`, `AeroCard` | Conservar contrato de roles; añadir `AeroFinish` opt-in; no duplicar motor | Diff estático; suite Robolectric NOT_RUN |
| `AeroFinishDrawing` | Luz/grano en caché, sin AGSL/blur en tiempo de ejecución | Fuente original reproducible, sin benchmark real |
| `AeroArtworkMat` | Una sola carcasa para arte verdadero, un único borde | Diff Now Playing, captura de diseño manual histórica |
| `MichiIconAction` | Controles icon-only nunca reciben círculos de fondo | Invariante de fuente; físico NOT_RUN |
| `MichiFineSlider`, `PeqResponseCurve` | Pistas `Inset`, sin cambiar motor/coeficientes de DSP | Diff fuente; typecheck/Compose NOT_RUN |
| `MichiBottomSheet`, `AeroFloatingSurface` | Una única superficie `Floating+Smoked` | Código compartido; introspección de modales NOT_RUN |
| `GlassCard` | Mantener compatibilidad; migrar solo vistas concretas sin estilo especial | Código de compatibilidad y lista de callsites |
| `SyncComponents`, `StorageBreakdownBar` | Discovery ≠ conectado; progreso/completado no inferido | Fuente y test acotado pendiente |
| `SyncWizardScreen` | Perfil disponible ≠ transferido; métricas calculadas de preflight | Fuente, QA pendiente |
| `DeviceDetailSheet` | Identidad técnica expandable, estados de confianza sin recalificación visual falsa | Diff y callbacks conservados |
| `ArtistEnrichmentPanel` | Datos externos opt-in; licencia y atribución intactas | Diff de candidato, no test red |
| `TrackActionMenu` | Diálogo de playlist unificado; callbacks originales de negocio intactos | Diff, suite de repo pendiente |
| `PremiumUi`, `GlowPlayButton`, `NowPlayingComponents` | **No borrar** ni declarar LEGACY sin callsites completos | Baseline fuente disponible; reachability NOT_PROVEN |

**Veredicto:** todos los grupos tienen contrato de apariencia o intervención específica; las compatibilidades e interacciones siguen pendientes de compilación Gradle, pruebas de teclado, snapshots opcionales y revisión de alto contraste. Este archivo no habilita `OFFLINE_READY`.

| `AeroDialogFieldShape` / `aeroDialogFieldColors` | Token único para 9 campos de diálogo y Settings; Cian=conexión, rosa=playlists; alto contraste desactiva el acento de borde | 10 callsites; Kotlin PSI PASS; guard 16/16 PASS; Robolectric NOT_RUN |
| `aeroModalFormViewport` | Formulario simple con scroll acotado; no se agrega a los diálogos con LazyColumn | 6 formularios protegidos, sin ejecución con IME real |
| Confirmaciones de Playlist / DeviceDetail | CTA >=48 dp, guardado/eliminación accesiblemente deshabilitados, confirmación destructiva Floating | Diff fuente, tests Compose NO EJECUTADOS |
