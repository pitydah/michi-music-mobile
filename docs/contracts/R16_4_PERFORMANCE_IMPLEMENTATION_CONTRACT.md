# Michi Music Mobile R16.4 — PERF: contrato de implementación OpenCode

**Estado: SPEC_ONLY / OBLIGATORIO PARA OPENCODE / NO INCLUYE OPTIMIZACIONES DE RUNTIME.**
**Fecha:** 2026-11-03. **Base:** `330b2018af1f17cb528533caccb9c0c2fc05d59d`.
**Autoridad:** §19 de `R16_4_AUDITORIA_IMPLEMENTADO_PENDIENTE.md` (mismo archivo Drive). Este archivo está **dentro del parche acumulativo**, no es una nueva versión de Drive. No ejecutar cambios de performance sin línea base y casos de regresión. `HANDOFF_PACKAGE_GO` no implica `PERFORMANCE_GO`.

## 0. Hechos contrastados en HEAD (no inferencias)

- `app/.../sync/SyncViewModel.kt`: `OneTimeWorkRequestBuilder<SyncWorker>()` con `.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)`; trabajo periódico `PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)` con `.setInputData(...).build()` **sin `Constraints` expresas**. No duplicar el backoff manual existente. `SyncRetryPolicy` solo limita `IOException` no gestionada a intentos `0..2`.
- `app/.../sync/SyncWorker.kt`: toma manualmente `PowerManager.PARTIAL_WAKE_LOCK` con timeout de 30 min, y tiene múltiples `release` en caminos de salida. Eso **no prueba una fuga** ni prueba que resulte necesario bajo WorkManager: auditar lifecycle y cancelación.
- `app/.../playback/PlaybackSessionManager.kt`: SSE primero, `remotePollingJob` cada **3 s** cuando SSE no está conectado y el endpoint permanece seleccionado/conectado; detiene/cancela jobs al desconectar.
- `app/.../MichiApp.kt`: Coil `MemoryCache.maxSizePercent(this, 0.25)` y `DiskCache.maxSizeBytes(50L*1024*1024)`.
- `app/.../ui/components/AlbumArtView.kt`: LRU aparte de `16 * 1024` KiB; imagen embebida hasta 25 MiB, decodificación con muestreo a ~512px en `Dispatchers.IO`, caché negativa TTL 45 s. Ya tiene límites; falta coordinar presupuestos.
- `app/.../screens/SearchViewModel.kt`: `Dispatchers.IO`, 250 ms debounce, `searchGeneration`, cancelación, pero filtra y agrupa listas completas en memoria y reconstruye datos locales+sincronizados por consulta.
- `app/.../ui/coverflow/MichiCoverFlowHost.kt`: Compose `HorizontalPager`, keys estables, `graphicsLayer`, `snapshotFlow` y `reducedMotion`. **NO** hace falta migrarlo de RecyclerView ni introducir `derivedStateOf` sin medición.
- `player/.../MichiPlaybackService.kt`: servicio Media3 que libera sesión/controlador en `onDestroy`; no hay medidas de CPU residual en pausas largas.

## 1. Tickets de implementación obligatoria — en orden, sin romper Signal Truth ni reproducción

### PERF-01 P0 — programación energéticamente responsable de sincronización
**Archivos:** `app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt`, `SyncWorker.kt`, configuración de `auto_sync`, tests de `app/src/test/.../sync/`.
**Implementar:** añadir `Constraints.Builder()` explícitas para trabajo **PERIÓDICO**: `NetworkType.CONNECTED`, `setRequiresBatteryNotLow(true)`, `setRequiresStorageNotLow(true)` (según compatibilidad WorkManager vigente); requerir `UNMETERED` o carga **solo cuando la preferencia de usuario lo exija**, sin cambiar los defaults manuales. Mantener 12 h o el intervalo que el usuario configure de manera válida; verificar mínimo legal de WorkManager. Si `auto_sync=false`, cancelar trabajos periódicos previos del/los dispositivos relevantes (no solo dejar de encolar). Respetar idempotencia y `ExistingPeriodicWorkPolicy` coherente para cambios de preferencias; no duplicar trabajos al recrear ViewModel. El manual sigue siendo posible sin carga/Wi-Fi salvo preferencia explícita. Mantener backoff manual exponencial existente, y asegurar una política coherente para periódico sin bucles locales inmediatos.
**Pruebas:** creación de `WorkRequest` y restricciones (con red metered/unmetered, batería baja, almacenamiento bajo), auto_sync off cancela jobs, trabajo manual sigue iniciando, server 503/timeout no hot-loops ni revivir planes terminales.
**PASS:** nunca reintentar indefinidamente, nunca lanzar background en batería baja bajo el perfil por defecto, sin regresión de cancelación/plan aprobado/identidad de endpoint.

### PERF-02 P0 — wakelock de SyncWorker y cancelación correcta
**Archivos:** `SyncWorker.kt`, tests de integración/seguridad y fixture de `WorkManager`.
**Implementar:** primero comprobar si el `PARTIAL_WAKE_LOCK` propio es redundante respecto al ciclo de vida de WorkManager/ForegroundInfo para el targetSdk real. Preferir eliminación documentada si seguro. Si es indispensable, encapsularlo en una única adquisición tardía con `try/finally` sin ramas que omitan `release()`; capturar `CancellationException` y relanzarla, sin transformarla en `Result.retry()`; limitar duración y evitar doble adquisición. No romper persistencia de checkpoints ni permitir transferencias que sigan corriendo tras cancelación.
**Pruebas:** credencial inválida, preflight storage, excepción antes y durante transferencia, cancelación, proceso muerto, timeout, ninguna referencia de `PowerManager.WakeLock` retenida. Tests con fake lease donde proceda.
**PASS:** liberar en todos los caminos incluso excepciones; sin spin/reintentos infinitos, sin pérdida de reanudación parcial.

### PERF-03 P1 — reducir polling remoto SSE fallback
**Archivos:** `app/src/main/java/org/michimusic/mobile/playback/PlaybackSessionManager.kt`, tests de controlador/endpoints; **no modificar semántica de `SignalTruthResolver`**.
**Implementar:** SSE como ruta primaria; fallback adaptativo controlado por estado (reproducción/pausa, foreground/background, conexión, endpoint actual), con intervalos acotados y backoff+jitter ante fallos; cancelar inmediatamente al cambiar de host/desconectar/pasar a SSE conectado. No usar `while(true)` incondicional ni observar pantalla con polling innecesario; preservar latencia de comandos explícitos mediante refresh inmediato. Mantener comprobaciones de epoch antes de publicar estado; no reciclar colas de otro endpoint.
**Pruebas:** fake clock/flows para SSE conectado/desconectado/reconectado, cambio de endpoint en vuelo, pausa prolongada, pantalla apagada, reintento/reconexión, cancelación total del Job.
**PASS:** sin polling mientras SSE está conectado; menos solicitudes en ausencia de interacción; estado veraz sin confundir observado vs declarado.

### PERF-04 P1 — presupuesto de memoria total de imágenes
**Archivos:** `MichiApp.kt`, `AlbumArtView.kt`, tests de carga de imágenes.
**Implementar:** medir por dispositivo el total *Coil + LRU embebida + bitmaps actualmente visibles*, no duplicar automáticamente caches. Introducir presupuesto acotado ajustado a `ActivityManager.isLowRamDevice`/`memoryClass` y tratamiento de `onTrimMemory` (sin bloquear UI), con un único propietario de cada thumbnail siempre que sea viable. Conservar decodificación en IO, límites de tamaño y caché negativa; evitar guardar portadas enormes o ficheros remotos sin validar.
**Pruebas:** scroll rápido biblioteca 1k/10k/50k, imágenes 25 MiB, fallback inválido, rotaciones, background/foreground y trim, sin OOM ni cambios de imagen incorrectos.
**PASS:** RAM estable tras iteraciones, sin fugas, comportamiento accesible sin red y sin redundancia observada.

### PERF-05 P1 — búsqueda escalable en catálogos grandes
**Archivos:** `SearchViewModel.kt`, `SyncedTracksViewModel.kt`, `data/src/main/.../cache/TrackDao.kt`, migración Room solo si se justifica.
**Implementar:** medir O(N) en memoria y limitar reconstrucción por pulsación. Preferir snapshot/index precomputado con invalidación, o SQLite FTS **solo con migración versionada respaldada y schema exportado**, `LIMIT`/paging, índices y desambiguación por biblioteca+origen+ID. No fusionar pistas diferentes por metadatos semejantes, no ejecutar queries/red en Main; conservar debounce/epoch/cancelación y búsqueda sin red.
**Pruebas:** 1k/10k/50k pistas, diacríticos/mayúsculas, múltiples bibliotecas, homónimos, consultas A→B→A y limpieza, resultado no obsoleto y sin ANR.
**PASS:** caída de latencia p95 respecto a baseline sin regresiones de identidad o precisión; ningún objetivo falso de SQL <16 ms universal.

### PERF-06 P2 — CoverFlow/NowPlaying medidos, no optimizaciones cosméticas
**Archivos:** `MichiCoverFlowHost.kt`, `CoverFlowGeometry.kt`, `NowPlayingScreen.kt`, `MichiFineSlider.kt` y tests.
**Implementar:** registrar trazas de composición, frames y cargas de imagen antes de refactor; `derivedStateOf` **solo** para valores discretos cuyo cambio resulte sustancialmente menos frecuente que la entrada; evitar lecturas `currentPageOffsetFraction` fuera de scopes adecuados; preservar `reducedMotion`, claves estables, TalkBack y hero artwork. No reintroducir RecyclerView ni raw GPU blur.
**Pruebas:** Macrobenchmark/Perfetto scroll/jank en dispositivos 60/90/120 Hz, 320/360/393/412 dp, fontScale 200%, alto contraste.
**PASS:** no degradar p95 frame/jank ni transiciones, sin cambiar selección de álbum o reproducción.

### PERF-07 P2 — sesiones de reproducción/pausa y recursos
**Archivos:** `player/.../MichiPlaybackService.kt`, `app/.../playback/PlaybackSessionManager.kt` y tests existentes.
**Implementar:** auditar CPU y decodificadores tras pausa de 5/30/60 min; liberar buffers/red/taps cuando resulte seguro **sin cortar reproducción de fondo**, notificación o sesión Media3 legítimas; distinguir reproducción local, remota y streaming RTP; verificar restauración/resume, focus, hotplug y salida DAC. No asumir que Foreground Service garantiza inmunidad a Doze/muerte de proceso.
**PASS:** sin actividad residual innecesaria, sin errores al reanudar ni pérdida de cola y sin regresión en Signal Truth.

### PERF-08 P0 transversal — observabilidad y criterio de GO
**Implementar:** `StrictMode` para disk/network en Main exclusivamente en debug (`MichiApp.kt`, no release), Performance tracing, Perfetto, Android Studio Profiler, Macrobenchmark/Baseline Profiles cuando el toolchain lo permita. Perfilar batería (wakelocks, red, jobs), memoria (PSS/GC/bitmaps), búsqueda (latencia p50/p95), UI (frame time/jank), reproducción en pausa (CPU). Registrar dispositivo/SoC/API/refresh rate/temperatura/carga y condiciones de prueba; 5 repeticiones como mínimo para comparar medianas. Baseline **antes** de cambios. No adjudicar ahorro porcentual no medido.
**PASS:** tabla antes/después por variante y dispositivo con logs, sin regresiones críticas P0/P1, 0 cambios a señal de audio por performance no certificada.

## 2. Secuencia exacta de OpenCode

1. Confirmar checkout limpio en base R16.4 admitida; ejecutar `bash R16_4_APPLY_AND_VERIFY.sh --verify-package`, luego `--check-only`, luego aplicar **solo una vez**. Si el parche ya está aplicado, no reejecutarlo: tomarlo como estado inicial y adaptar implementación al HEAD resultante.
2. **Instalar/verificar plugin Chris Banes**, leer `using-chrisbanes-skills`; aplicar `kotlin-concurrency-and-flow` para WorkManager/flows/cancelación, `kotlin-api-design` para políticas, `compose-state-and-effects` para UI, `compose-performance` al medir antes/después, `compose-ui-testing-patterns` para test, `gradle-run` para tareas Gradle; `to-plan` y `deliver-spec` en inicio/handoff. No limitarse a mencionar nombres.
3. Medir línea base; ejecutar P0 `PERF-08`, `PERF-02`, `PERF-01`, después P1 `PERF-03`/`PERF-04`/`PERF-05`, luego P2 `PERF-06`/`PERF-07`. Cambios pequeños, pruebas automáticas por ticket, sin regresiones.
4. Compilar variantes Android reales, KSP y Room, tests unitarios e instrumentados; Perfetto/Macrobenchmark/Power Profiler sobre al menos un terminal de gama media y uno low-RAM. No confundir test que compila con medición energética.
5. Evidencia final: commit, archivos modificados, logs y versiones, resultados CI, mediciones antes/después, capturas y matriz de tickets. Declarar `PERFORMANCE_GO` solo si P0/P1 aprobados con métricas y regresiones P0=0; `RELEASE_GO` exige además audio DAC/USB, RTP, PCM, Signal Truth y validación de accesibilidad.

## 3. Separación de responsabilidades y reglas de seguridad

**Esta entrega R16.4 únicamente incorpora el contrato como fuente dentro del parche; no implementa todavía PERF-01..08, ni afirma ahorro de batería/CPU/RAM.** Es intencional: cambiar políticas de trabajo y ciclo de vida sin correr WorkManager/Android/Media3 es inseguro. La especificación es obligatoria para el ejecutor OpenCode. Aero sigue siendo tema canónico, sin nuevos temas. No borrar bibliotecas, colas, favoritos, checkpoints, credenciales ni preferencias existentes; no modificar la política de autenticación de Michi Link y no desactivar `SignalTruth` para optimizar batería.
