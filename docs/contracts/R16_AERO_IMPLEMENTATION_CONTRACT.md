# MICHI MUSIC MOBILE — R16 · CONTRATO CANÓNICO DE IMPLEMENTACIÓN AERO + AUDIO

**Estado del presente documento:** especificaciones de implementación aprobadas para ejecutar en OpenCode; NO son funcionalidades implementadas. Este archivo viaja **dentro del mismo parche acumulativo** y reproduce los requisitos operativos del plan maestro en Google Drive. El repo actual no debe asumir que una pantalla cumple un requisito solo porque el contrato existe.

**Repositorio:** `pitydah/michi-music-mobile` · **commit base:** `b963c820041c18d81609daef79b783047bce6aaf` · **fuente de imágenes:** capturas entregadas en conversación el 2 de noviembre de 2026. **UI nativa:** Kotlin + Jetpack Compose, no CSS ni QML. La nomenclatura de tickets UI es específica y no debe confundirse con las auditorías K15/K16.

## 0. Regla de autoridad y estados

Tres indicadores separados, nunca intercambiables:

- `IMPLEMENTATION_HANDOFF_GO`: existe plan suficientemente concreto y pruebas definidas para que OpenCode comience trabajo en rama aislada.
- `PATCH_APPLY_GO`: el parche se ha validado contra árbol limpio con commit exacto, SHA-256 y `git apply --check` mediante las opciones exigidas. **No presupone compilación ni funcionamiento.**
- `MERGE_GO/RELEASE_GO`: solo tras build Gradle, tests, CI, instrumentación Android, pruebas de hardware y P0/P1 cerrados. **NO-GO actualmente**.

Una corrección tiene estados `SPEC_ONLY`, `CODE_PATCHED_UNVERIFIED`, `BUILD_PASS`, `ANDROID_VALIDATED`, `HW_VALIDATED`, `MERGED`; los cambios enumerados en este contrato son `SPEC_ONLY` mientras no se añada código de producción y pruebas verificables.

## 1. Arquitectura de diseño Aero Premium v2 — tokens y ergonomía

**Reutilizar los tokens reales:** `SurfaceObsidian=#090C12`, `SurfaceContainer=#1B2029`, `AeroGraphiteRaised=#232A34`, `PrimaryPinkContainer=#FF5167`, `TertiaryCyan=#7DF4FF`, `TextSecondary=#BEC7D2`, `AeroHairline`, `MichiSurfaces.base/raised/hero/heroFade`, fuente Inter. Nunca introducir un gradiente distinto por pantalla. Superficie oscura opaca con **ilusión de cristal ahumado**, no blur GPU en tiempo real; hairline 0.6–0.8dp, en alto contraste aumentar según política central; sombra ambiental mínima; una sola capa dominante sin triples tarjetas anidadas. Rosa = acción de producto o favorito activo; cian = dato técnico confirmado/ruta observada; ámbar = desconocido; tonos neutros = solicitud/configuración. Color por sí solo no informa el estado.

**Accesibilidad y geometría:** objetivos táctiles >=48dp; Inter mínimo 12sp para labels ordinarios (10–11sp en eyebrow solo cuando TalkBack tenga contenido completo y contraste suficiente); texto normal mínimo 4.5:1, grande 3:1; alto contraste, tamaño 320/360/393/412dp, fontScale 1.0/1.3/1.5/2.0; salida de diseño vertical/stacked si tres controles o dos columnas no caben; reducedMotion elimina escala y animaciones no esenciales; RTL y paddings; teclado/foco semántico. NO reducir touch target por obtener captura estética.

**Skills** `https://github.com/chrisbanes/skills`: `using-chrisbanes-skills` enruta la implementación; `compose-component-design` para APIs y slots/layouts; `compose-state-and-effects` para ownership y `StateFlow`/diálogos; `compose-ui-testing-patterns` para semántica, screenshots y acciones; `compose-animations` SOLO para transiciones realmente necesarias; `kotlin-api-design` para interfaces de repositorios/identidad; `kotlin-concurrency-and-flow` para trabajo asíncrono, cancelación, snapshots y configuración; `gradle-run` cuando se ejecute Gradle; `compose-performance` únicamente ante medida de jank/recomposición; `to-plan`/`deliver-spec` si disponibles. Cargar los documentos `SKILL.md` originales ANTES de editar código y documentar qué decisión cubre cada uno. No añadir MCP redundantes de memoria.

## 2. UI-01 — Home: tres botones de la tarjeta editorial

**Fuentes verificadas:** `app/src/main/java/org/michimusic/mobile/ui/components/AeroM60Editorial.kt` → `AeroLibraryFeature`; `.../AeroPremiumControls.kt` → `AeroActionButton`; `.../ui/screens/HomeScreen.kt` callbacks.

**Problema:** `Escuchar`, `Todo`, `Mezclar` compiten visualmente; bordes gruesos e iconografía sin jerarquía; `Todo` resulta ambiguo fuera de la portada; en fontScale alto la distribución horizontal puede truncar etiquetas.

**Contrato:** `Escuchar` = acción primaria rosa Michi, icono PlayArrow fino (solo una vez), contraste de texto oscuro; `Reproducir todo` = acción secundaria grafito (se permite abreviar a `Todo` visualmente SOLO si description semántica completa); `Mezclar` = terciaria de cristal humo con shuffle icon 17–19dp. No inventar funcionalidad: callbacks deben seguir realizando PLAY de la portada, PLAY ALL y SHUFFLE. Reusar `AeroActionKind.Primary/Secondary/Quiet`; reducir borde secundario a hairline, eliminar halos fuertes, padding horizontal adaptable, targets 48dp y layout stacked/2+1 a 320dp/fontScale alto. Conservar testTags `m60_feature_play`, `quick_play_button`, `shuffle_button`; evitar doble activación por surface anidada. Ningún botón debe provocar salto de canción por tocar fondo o área vecina.

**Pruebas:** 3 callbacks exactos una vez, estado disabled/pressed/focus, etiqueta accesible, overflow y screenshot 320–412dp/200%, reducedMotion. Principal `compose-component-design`, secundarias `compose-ui-testing-patterns`, `compose-state-and-effects` si ownership cambia.

## 3. LIB-ART-01 — Biblioteca > Artistas: Artist Enrichment

**Fuentes:** `.../ui/screens/AlbumsScreen.kt`, rama `LibraryTab.ARTISTS`, usa `albums.groupBy { it.album.artist }`, `Icons.Default.Person`; `.../data/repository/LocalMediaRepository.kt::loadArtists()` deriva un ID de nombre textual. Tocar toda la tarjeta llama `playQueueSafe`, no abre ficha.

**Contrato por etapas (no intentar resolver todo con llamadas de red en Compose):**

1. Dominio `ArtistIdentity` local inmutable con namespace/origen y `musicBrainzId: String?` solo verificado; no fusionar homónimos o artistas conjuntos por texto. `albumArtist`, `trackArtist`, créditos y colaboraciones son relaciones distintas. NO dividir cadenas como `Patti Austin & James Ingram`, `feat.`, `,` sin créditos estructurados; migrar Room/IDs/playlist sin destrucción ni colisiones. Conservar catálogo offline. `kotlin-api-design` principal.
2. `ArtistEnrichmentRepository` interfaz + adaptadores de metadatos aprobados. MusicBrainz para MBID y relaciones, User-Agent propio, máximo 1 request/s, backoff, deduplicación; fotos únicamente desde fuentes autorizadas con licencia, autor, URL y atribución; no inventar biografía ni descargar imágenes con licencia ambigua. `UNKNOWN/UNRESOLVED` como estados legítimos. Opt-in de red según política de Michi Mobile, cache TTL, erase/refresh, `retrievedAt`, procedencia y confianza. `kotlin-concurrency-and-flow`.
3. UI: `ArtistListItem` con retrato real verificado o placeholder editorial Aero; nombre + álbumes/canciones disponibles y número fiable; tocar tarjeta abre `ArtistDetailScreen`; SOLO botón PlayArrow (mínimo 48dp) inicia cola; TalkBack distingue «Ver artista» de «Reproducir pistas». Detalle: hero, bio breve atribuida, álbumes/temas **presentes en colección**, colaboraciones acreditadas y play/shuffle, fuente de información externa visible. No presentar discografía remota como música descargada.
4. Tests: dos artistas homónimos, identidades con `&`, MBID conflictivo, nombre local sin MBID, sin permisos/licencia, red caída/HTTP 429/503, caché vieja, actualización de albumArtist, migración de playlists, Play vs detalle, back/foco, 320–412dp/200%, TalkBack; ninguna solicitud web por recomposición, ninguna degradación de audio o Stream.

**Advertencia:** en `master` no hay Artist Enrichment verificado. Esto es un desarrollo nuevo con dependencias y gates explícitos, no cambio cosmético de avatar.

## 4. Now Playing — tres tickets coordinados

### NP-01: MichiControlBadge (selector de controlador)

**Archivo:** `AeroM60Editorial.kt::MichiControlBadge`; uso desde `NowPlayingScreen.kt`.

- Reducir la pill a control flotante de 44–48dp visual con objetivo >=48dp, padding 12–14dp, radio 24dp, borde 0.6dp blanco 14–18%, fondo `AeroGraphiteRaised`, un highlight superior mínimo; sin blur ni borde blanco agresivo. Icono contextual (smartphone, servidor/endpoint) 16–18dp; punto cian de 5dp solo con conexión REAL; texto Inter 13sp medium, flecha 16dp; tooltip/semántica `Control de reproducción: {endpoint}. Elegir controlador` y expanded/collapsed.
- `CONTROL` del reproductor ≠ `ROUTING` de audio ≠ `HANDOFF` de cola. Selección del Stream-only NO debe simular que puede controlarse como un host; preservar `isMichiControlTargetAvailable`; nombres largos sin invasión del botón cerrar; dropdown 48dp por fila, estados disconnected/active/remote y preview basada en autoridad.
- Mantener `michi_control_badge`/`michi_control_menu`; auditar cambio/disconnect y 320dp/200%/reducedMotion.

### NP-02A: AeroSignalRouteStrip (una bandeja, DOS funciones)

**Archivo:** `AeroM60Editorial.kt::AeroSignalRouteStrip` y `NowPlayingScreen.kt`.

- Bandeja única `Audio Insight`: base #191F29/radio 19dp/borde 0.65dp; cabecera `Información de audio` (icono waveform + 2 destinos interactivos). Dos superficies ligeras contiguas (sin triples tarjetas) separadas por hairline; ambas con chevron y feedback de pulsación separado. Izquierda: `ARCHIVO ORIGINAL` + valor MP3/FLAC/bitrate *con calificador observado o declarado* y subtítulo `Ver Signal Truth`. Derecha: `SALIDA SOLICITADA` + endpoint + `Ver rutas de audio`. En ancho pequeño o fontScale>=1.4 apilar verticalmente; ningún texto de dato principal desaparecerá por maxLines=2 sin un acceso semántico completo.
- Taps independientes; conservar tags `signal_truth_button`, `audio_route_button`; contenedor NO clicable. Verdad técnica: archivo ≠ PCM decodificado ≠ salida solicitada ≠ salida observada ≠ DAC físico verificado. Fila inferior opcional de contexto PCM; si falta evidencia declarar `No observado`, nunca bit-perfect.

### NP-02B: modal Signal Truth (lectura técnica)

**Archivo REAL:** `NowPlayingScreen.kt::SignalTruthDialog`, `SignalTruthCard` (ambos privados), `SignalTruthResolver` y modelo `SignalProvenanceItem`.

- Familia `AeroTechnicalSheet` con surface floating 26–28dp radio, base `MichiSurfaces.raised` sin blur, hairline translúcido, header compacto con icono `audio-lines`, título `Signal Truth`, subtítulo `Procedencia de la señal`, close >=48dp. Contenido `LazyColumn`/scroll seguro con altura máxima responsive y cabecera accesible; evitar 5 rectángulos negros con 5 bordes repetidos.
- Renderizar etapas conectadas con pequeño marcador (fuente → decodificación → procesado → AudioTrack → dispositivo físico), valores de contraste alto y **chips de evidencia**, con texto además de color: `Declarado` neutro, `Configurado` violeta grisáceo, `Observado` cian únicamente si generación/epoch actual, `Desconocido` ámbar. Los chips no son botones. Mostrar `confidenceNote` y procedencia cuando exista; no duplicar nota `Los metadatos no certifican la salida física`.
- NO permitir que tocar una fila cambie la canción, dispare queue Play ni cierre/control remote. OnDismiss solo X/gesto afuera según política; no llamadas audio dentro del render. No presentar flags de DSP solo configurados como observados; revisar `SignalTruthTracker`.

### NP-02C: modal Audio Routing (acción operativa)

**Archivo:** identificar `AudioRouteDialog` en el árbol actual antes de editar; invocado por `NowPlayingScreen.kt` vía `showAudioRouteDialog`. No reimplementar el selector con suposiciones de backend.

- Mismo shell técnico del modal Signal Truth (radio, tipografía, icono, botón cerrar), pero dos secciones semánticas diferenciadas: `REPRODUCIR EN` endpoint Michi/controlador, y `SALIDA DE AUDIO DE ESTE TELÉFONO` ruta local de AudioTrack/DAC/altavoz. Cada opción `AeroSelectionTile` 48–56dp con icono, nombre, subtítulo REAL, seleccionado check + texto `Seleccionado`/`En uso`, disponible, incompatible o desconectado. No simular ruta física con tipo de endpoint.
- Usar `Listo` solo cuando represente acción verificada; si ya se aplica al tocar fila, botón `Cerrar` y mostrar feedback de selección con error/reversión si fracasa. Rosa para CTA confirmado; cian para salida efectivamente observada, nunca para «solicitada». Listas largas con scroll y cabecera/pie seguros; la disponibilidad no se infiere del simple nombre `Otra salida de audio`.
- Test: ausencia de DAC, DAC A/B, hotplug, Bluetooth, Stream remoto, permiso negado, invalidación de selección, una sola llamada de backend por tap, 320–412dp/200%/TalkBack.

### NP-03: favorito al lado del título

**Archivo:** `NowPlayingScreen.kt` título actual + fila de herramientas; persistencia `FavoritesRepository` ya disponible. Trasladar `now_playing_favorite_button` a la derecha del título en un `Row` centrado ópticamente y adaptable a maxLines 2–3, espacio espejo izquierdo sólo cuando quepa, título de 23–29sp según ancho; icono heart outline 22–24dp en objetivo 48dp sin fondo permanente. Inactivo blanco suave; activo rosa #FF5167 lleno. Estado, descripción y acción accesibles; no duplicar botón debajo; mantener el mismo repositorio/IDs; evitar doble toque y estado optimista sin persistencia. Cola, temporizador, EQ y acciones restantes se redistribuyen equitativamente. Probar títulos muy largos, sin pista, favoritar bajo error, scroll, dos tracks consecutivos, TalkBack, reducedMotion.

## 5. DSP-STUDIO-01/02/03 — Ecualizador & DSP Studio y Headphone Studio

**Subtickets de ejecución:** `DSP-STUDIO-01` = rediseño y contraste; `DSP-STUDIO-02` = veracidad del DSP y separación de motores; `DSP-STUDIO-03` = pruebas, accesibilidad y certificación. Todos son `SPEC_ONLY` en esta entrega.

**Fuentes verificadas:** `DialogComponents.kt::EqualizerDialog` (Android EQ y efectos, pestañas), `HeadphoneStudioSection.kt` (perfil PEQ y filtros), `MichiFineSlider.kt`, `MichiAudioEffects.kt`, `AudioModeManager.kt`, `HeadphoneProfileStore.kt`, `HeadphonePeqAudioProcessor.kt`, `MichiPlaybackService.kt`, `SignalTruthTracker.kt`.

### P0 visual: texto ilegible y layout

Las capturas muestran texto negro contra grafito: `Frecuencia`, `Ganancia`, `Q/anchura`, estado de ruta, headroom, filtros y botones. **Todos** los textos deben usar tokens `PureWhite`, `OnSurfaceVariant`, `TextSecondary` o equivalente comprobado con contraste AA. No usar `Text()` sin color explícito fuera de superficies Material cuyo `LocalContentColor` esté garantizado.

Un solo diálogo gigantesco mezcla 6 filtros paramétricos (3 sliders por filtro), efectos gráficos de Android, 8 presets, curva, Headphone Studio, nombre y perfil, ReplayGain/dinámica, cambios de modo y footer. Transformarlo en **Aero Audio Studio** de altura completa / navegación contextual, dos áreas hermanas `Ecualizador del sistema` y `Headphone Studio`; header fijo con modo y estado real, cuerpo scrolleable, accesibilidad para IME y landscape; tarjetas y controles con densidad consistente. Las dos áreas deben compartir tokens, NO el mismo preset/autoridad. Si no hay guardado pendiente, CTA inferior dice `Cerrar` (no `Guardar y Cerrar`), pues el código actual persiste en setters.

### Dos motores y tres modos

- **Android EQ:** `MichiAudioEffects` utiliza `android.media.audiofx.Equalizer/BassBoost/Virtualizer/LoudnessEnhancer` en sesión. Bandas/rangos/centros pueden variar por OEM; nunca representar las 5 bandas fallback como hardware medido; presets actuales son vectores posicionales de 5 valores y requieren validación/adaptación por frecuencia/rango antes de un `Applied` fiable.
- **PEQ local:** `HeadphonePeqAudioProcessor` procesa seis `PeqBand` tipo peak con Fc, gain y Q; solo local PCM, auricular USB/cable con ruta **confirmada** y el usuario seleccionó explícitamente; NO se aplica a altavoz ni RTP/Michi Stream. `HeadphoneProfileStore.enabled` es preferencia guardada, no telemetría audible. Nunca doblar Android EQ con PEQ local por accidente.
- **Modos:** `DSP` permite efectos aplicables, `TRANSPARENT` se presenta **«Sin efectos de Michi»** y no afirma bit-perfect ni ausencia de procesamiento por Android/DAC, `AUTO` decide según ruta OBSERVADA (USB puede activar bypass). Mostrar `Guardado`, `Configurado`, `Autorizado`, `Procesando PCM` y `No observado` con evidencias distintas. `DSP ACTIVO` solo si hay sesión/etapa real, no `effectsState.isEnabled` aislado.

### Nuevo diseño editor y curva

- Para PEQ: lista de seis chips con frecuencia, nombre y estado; cada filtro editable como panel técnico pequeño de `Frecuencia (Hz, log 20–20k)`, `Ganancia (dB, -12 a +12)`, `Q (0.3–8)`; valor numérico directo y deslizante fino visual 3–4dp con target 48dp (reusar `MichiFineSlider`/estilo Aero, no Material purple thick). El perfil siempre editable, pero mostrar `Editando perfil · No aplicado` si no elegible. Presets `Neutral`, `Warm`, `Voice` = **Headphone Studio**; presets `Michi Signature`, `Bass Punch` etc. = **Android EQ**. Diferenciarlos por sección.
- Curva PEQ: escala logarítmica 20 Hz–20 kHz y eje 0dB, magnitud calculada a partir de los mismos coeficientes RBJ, misma tasa PCM/sampleRate y versión que el procesador. Leyenda obligatoria `Curva teórica calculada`, no `Respuesta medida` salvo datos de medición autorizados. El gráfico actual de `EqualizerDialog` une ganancias de centros equiespaciados: etiquetar `Bandas configuradas (aproximación)` hasta que exista cálculo real; nunca HI-RES/bit-perfect como derivación de curva.
- Headroom: `peqHeadroomDb` suma boosts positivos (cota conservadora hasta 72dB), **no es medición física**. Exponer `Reserva estimada`, `Reducción aplicada` y `Clipping observado` con fuentes, no un número ambiguo. Mostrar advertencias si extrema atenuación; no silenciar audio bajo la premisa de seguridad sin diseño y prueba.
- Presets y guardado: `Guardar perfil por nombre` debe mostrar feedback, detectar duplicado y pedir confirmación antes de sobrescribir. No identificar auricular pasivo por nombre del USB DAC; `selectedRouteId`/epoch es efímero, requiere autorización manual tras hotplug. `Restablecer EQ` afecta solo Android EQ; `Restablecer PEQ` es otra acción; validación de 6-band roundtrip y migraciones.

### P0/P1 funcionales que bloquean el merge

- `K15-03`: transición PEQ activo → bypass puede saltar sin rampa, requiere crossfade/zero-click medido.
- `K16-16`: memorias IIR en cambio `onFlush`/publicación deben tener ownership estable y evitar acceso concurrente con hilo de audio.
- `K15-01`: ReplayGain llega tarde en transición si depende de DAO; snapshot previo o política sin boost hasta conocido, tests exactos de primera muestra.
- `K16-28`: reserva exagerada por suma de ganancias; evaluar oráculo headroom matemático y medición PCM de amplitud y clipping.
- `K16-29`: medir presupuesto de CPU y asignaciones en `queueInput`, no bloquear renderer por cálculo de curva, UI, strings ni persistencia.
- Compatibilidad Android PCM16/24/32/float y tasas 44.1/48/96/192 kHz, Stream/RTP pre-PEQ, DAC USB A/B hotplug y pérdida de ruta, suspensión y gapless.

### Skills de DSP

`compose-component-design` lidera layout reusable (`AeroStudioShell`, `AeroEqBandControl`, `AeroPeqFilterEditor`, `AeroResponseCurve`); `compose-state-and-effects` ownership; `compose-ui-testing-patterns` tests visuales; `kotlin-api-design` contratos de DSP/evidence; `kotlin-concurrency-and-flow` actualizaciones y cancelación; `gradle-run` para compilación; `compose-performance` solo tras medir.

## 6. Seguridad y persistencia R16 que siguen pendientes (no falsificar GO)

- `K16-01/02/25`: NO basta con suprimir delete global: migración transaccional Room a `SourceScope(deviceId,libraryId)` + `ScopedTrackKey` y playlist owner, sin colisión por IDs iguales entre 2 servidores, preservar offline/playlist local. Nunca usar `require` como sustituto permanente de actualización legítima de playlist homónima.
- `K16-03/07`: atomicidad de `ConnectionManager` con epochs, disconnect/token rotation y SSE; no `CONNECTED` tras desconexión; prueba multicorrutina.
- `K16-04`: SSE verificar HTTP 200 y Content-Type `text/event-stream` EXACTO, incluso si header ausente; reconexión y autenticación; no declarar connected en 401.
- `K16-08/26`: HTTP sólo LAN verificable, no host single-label arbitrario; política de DNS/IP y secretos. La compatibilidad con LAN y Michi Link prevalece; validar rutas Tailscale/HTTPS.
- `K16-09/14/23`: Sync retry/transfer: SHA, checkpoints, idempotencia, FAIL terminal, no pérdidas de archivo/Room, AirCat checksum vacío no se llama verified.
- `K15-02/04` y `M5`: Signal Truth con epochs Track/Decoder/Output/Route; un tap en vista de evidencia NUNCA cambia pista; no trasladar telemetría de un endpoint a otro.
- Tests de seguridad: imports traversal/symlink, race CREATE_NEW, manifest malicioso, LAN→público, SSE corrupto, tokens en reportes, 1000 datagramas, reproducir/pausar durante transferencia.

## 7. Plan ejecutable y definición de GO

Orden sugerido para OpenCode y sin saltarse P0: `G0` preflight/restore y baseline; `G1` catálogo y migración Room; `G2` endpoints/auth/SSE/discovery; `G3` sync/transfer idempotente; `G4` transición audio/PEQ/headroom; `G5` Signal Truth; `G6` estado de biblioteca/artistas; `G7` UI Aero (UI-01, LIB-ART-01, NP-01/02A/02B/02C/03 y DSP-STUDIO); `G8` build/CI/screenshots/accessibility/perf; `G9` hardware DAC A/B + Stream y release. Permitir sub-sprints aislados por pantalla con pruebas y PR separados; NO reimplementar código existente R15 ni mutar varios motores sin necesidad.

**Criterios de aceptación por archivo:** revisar fuente actual, diff contextual completo, test unitario negativo y positivo, screenshot antes/después 320/360/393/412dp y 200% fuente, TalkBack/focus, dispatch evento exactamente una vez, no autoplay inesperado; auditor independiente y rollback. Ninguna captura confirma por sí misma que el checkout corresponda al APK instalado.

**Estándar de salida:** cada sprint debe registrar commit SHA, rutas y hunks realmente modificados, resultado de `git apply`, `git diff --check`, Gradle con `gradle-run`, CI run ID si existe, capturas reales de Android, tests hardware y pendientes. `SPEC_ONLY` no equivale a `DONE`. No cambiar versión de plan por cada pasada: modificar el **mismo** `MICHI_MOBILE_R16_MEGA_REFACTOR_MASTER.md` y reemplazar **el mismo** `MICHI_MOBILE_R16_CORRECTIVE.patch` en Drive (conservando IDs), sin generar archivos R17/R18 ni parches extra.
