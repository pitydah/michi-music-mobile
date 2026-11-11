# UI5 — Informe de integración acumulada

## Fuente y baseline

- Repositorio: `pitydah/michi-music-mobile`.
- Baseline de integración: `master@7917d5c29c859e395455c0d3b84c1fc966f627e3`.
- Fuente UI5 canónica: `ui5.patch`, 335 804 bytes, SHA-256
  `c6f3cad9e4e5f14554fbeecf315ef647a238a1ec495a25344b5e2aeeb5aa6b9c`.
- Plan: `plan.md`, 366 489 bytes, SHA-256
  `e4b5151037f89061c4f5d08b97eca18401fb44b3e31bc3b690801f28e08acb5f`.
- El parche declara base `5d1dd07b926197040ea9a3a64caef18f68e4b01b` y
  contiene UI5.1–UI5.32 de forma acumulada.

El parche se aplicó primero sobre su base exacta, sin conflictos, y se
confirmó allí como `34ffa25`. Después se trasladó con `cherry-pick` sobre el
`master` actual para que cualquier divergencia apareciera explícitamente.

## Conflictos y reconciliación

El parche y el `master` actual solo se solapaban en dos archivos:

- `SettingsScreen.kt`: se conservaron la sección `APPEARANCE` de UI5 y la
  sección `LIBRARY_STORAGE` del directorio musical configurable de PR #17.
- `SearchViewModel.kt`: se conservaron la búsqueda estructurada, el filtro de
  playlists y la normalización de UI5, junto con la recarga forzada que PR #17
  necesita cuando cambia la raíz musical.

No quedan marcadores de conflicto.

## Ajustes de integración

- Una revisión confirmada de la raíz musical invalida una carga de catálogo en
  curso. La carga antigua no puede publicar resultados ni finalizar el estado
  de una raíz nueva.
- El filtro `PLAYLISTS` publica un resultado estructurado vacío con su filtro y
  generación, en lugar de dejar el snapshot anterior.
- `HomeScreen` y `PlaylistScreen` reciben `AppDao` como dependencia explícita
  para que sus pruebas de UI no dependan de un contenedor Koin global.
- Las pruebas de navegación aceptan que Playlists es ahora una pestaña
  primaria.
- Las pruebas de detalle de playlist desplazan la lista antes de interactuar
  con filas que quedan fuera del viewport tras el nuevo hero visual.

## UI5-POLISH-02

- **Inicio:** el hero editorial es ahora un pager manual de elementos reales de
  la biblioteca (pista destacada, álbumes y playlists existentes). Cada página
  tiene identidad estable, acción exacta y no reproduce al deslizar. Los
  indicadores son discretos. `Escuchar`, `Todo` y `Mezclar` conservan sus tags y
  callbacks, pero usan superficies más finas, radios menores y altura táctil de
  48 dp. La etiqueta visible se acorta a `Escuchar`/`Abrir` cuando es necesario;
  el nombre accesible conserva el elemento exacto y `Todo` anuncia
  «Reproducir todas las canciones».
- **Biblioteca:** las pestañas `Canciones / Álbumes / Artistas` usan una variante
  compacta del rail sin reducir el objetivo de 48 dp. Los filtros
  `Todo / Lossless / Descargadas` usan chips secundarios con menor masa visual,
  selección semántica y los mismos `testTag`. CoverFlow, Mosaico y Lista no se
  modificaron.
- **Playlists:** el ordenamiento se oculta cuando no existen listas. El estado
  vacío es una tarjeta editorial compacta bajo la cabecera con el flujo real de
  `Crear playlist`. Con listas existentes se muestra un resumen, chips de orden
  intrínsecos, una marca lateral para fijadas y filas con portada real, nombre,
  cantidad y reproducción contextual. La edición, persistencia SAF, renombrado y
  eliminación existentes no cambian.
- **Dock:** se corrigió `DockLayoutComposeTest` para convertir 48 dp a píxeles con
  la densidad del test; la comparación anterior mezclaba `boundsInRoot` en px con
  un valor dp.
- **Rescan:** volver a indexar la misma raíz SAF ya no llama a `clearQueue()`.
  Solo una migración de identidad de fuente limpia la cola local.
- **Buscar:** las consultas con espacios finales se normalizan a su forma
  recortada y siguen encontrando resultados. Se conservan las guardas de
  generación, filtro, raíz musical y snapshot vigente.
- **Waveforms:** se mantiene la caché LRU privada en RAM de 24 entradas. La clave
  exige `filepath`, tamaño y fecha de modificación fiables; los resultados
  negativos y las fuentes mutables no se cachean, y dos decodificaciones no
  corren en paralelo. No se escribe PCM ni rutas de audio en disco: una caché
  persistente añadiría invalidación, limpieza y exposición de rutas sin una
  necesidad medida que justifique ese coste. Esta es una decisión explícita, no
  una omisión.

## UI5-APM-03 — Aero Precision Materials

SHA inicial del sprint: `5b0563e9d02516feaadd87ebd96470c5b4e8aa2c`.
SHA de implementación APM: `06b6be13159fa575712c446e76a3a98530468b1e`.
El commit documental posterior registra la validación exacta y solo añade este
informe.

### Tickets

| Ticket | Estado | Implementación verificable |
|---|---|---|
| AERO-MAT-01 | DONE (estático + pruebas) | `AeroMaterialResolver.kt` resuelve rol, acabado solicitado, perfil, microtexturas, alto contraste y pulsación/foco en una sola función. `AeroSurface`, `AeroActionButton`, `AeroLibraryFeature`, hero de Inicio, rails Inset, PEQ, almacenamiento y Signal Truth la consumen. No queda ninguna llamada directa a `aeroFinishSpec()` fuera de la política/resolver. |
| AERO-GLASS-02 | DONE (estático + pruebas; banner real no capturado) | El banner base permanece nítido. Solo una banda del mismo painter, del tamaño de la bandeja inferior, se recorta y desenfoca bajo Cristalino. `aeroPlaylistGlassRegionTopPx()` falla cerrado sin imagen, sin glass o con geometría inválida. Se conserva Smoked, alto contraste, movimiento reducido, ahorro y low-RAM. Sin Haze ni captura de otra ventana. |
| AERO-LIGHT-03 | DONE (estático + pruebas) | `AeroPerimeterPath.kt` convierte el `Outline` real en un perímetro reutilizable. `aeroFinishDrawing()` recibe la forma, dibuja la luz sobre el perímetro redondeado y limita el rebote a la arista inferior recta. Las esquinas no reciben líneas rectas; `drawWithCache` evita recrear paths por fotograma. |
| AERO-ART-04 | DONE (estático + capturas) | `AeroArtworkDepthPolicy.kt` sustituye la heurística `inset >= 4.dp` por `Standard / Featured / Hero`. Solo hero, destacados, detalle de álbum, portada de playlist y Now Playing reciben elevación; las filas densas quedan planas. Alto contraste fuerza cero sombra y cero luz direccional. El bitmap original no se tiñe ni se duplica. |
| AERO-COVER-05 | DONE (estático + pruebas) | Preferencia persistente `DISCRETO / NORMAL / INTENSO` en `michi_settings` y controles en Ajustes → Apariencia Aero → CoverFlow. Solo cambia el brillo del marco y la elevación de contacto de la portada elevada. `NORMAL` conserva exactamente el borde `AeroHairline` y los 3 dp previos. No toca pager, gestos, orden, selección ni añade espejos de carátula. |
| AERO-MODAL-06 | DONE (estático) | `MichiBottomSheet` pinta una línea especular dentro de su propio drag-handle; no usa el borde del root de ventana que producía el rectángulo desplazado. Los `DropdownMenu` de playlist, pista, Michi Control y Dispositivos reciben `aeroMenuTopLight()` sobre su superficie interna. Signal Truth conserva Smoked, foco y z-order. |
| AERO-OPTICS-07 | DONE como fallback documentado | `AUDIO_ROUTE_SELECTOR` sigue devolviendo `SMOKED_FALLBACK`. No existe una fuente de backdrop auténtica y segura entre la pantalla de Now Playing y la hoja técnica; Android 12 solo desenfoca el contenido propio, no la ventana inferior. No se simula vidrio con una imagen falsa ni se altera Media3/Signal Truth. |

### Decisiones de material

- El techo alfa canónico de `AeroFinishSpec` sigue siendo `0.12`; el resolver no lo eleva.
- `CRISTALINO` solo habilita óptica en superficies autorizadas. No convierte tarjetas, filas ni el Dock en vidrio.
- Las microtexturas apagadas eliminan el grano de todos los componentes, pero conservan color, contorno, luz de borde y sombra estructural.
- El alto contraste prevalece sobre perfil, microtexturas, profundidad de carátula y reflejos de CoverFlow.
- CoverFlow conserva su renderer Compose; la preferencia es paint-only y no duplica arte en cachés.

### Evidencia APM

Workflow gestionado `d080ab472a38d378cbda9fc236d6c0e9`.

| Gate | Resultado |
|---|---|
| `:app:compileNormalDebugKotlin` tras resolver/perímetro/glass/arte/CoverFlow/menús | PASS |
| `:app:testNormalDebugUnitTest` completo | PASS |
| `:app:testFdroidDebugUnitTest` completo | PASS |
| `lint` | PASS |
| `:app:compileFdroidDebugAndroidTestKotlin` | PASS |
| `assembleNormalDebug`, `assembleNormalRelease` | PASS |
| `assembleFdroidDebug`, `assembleFdroidRelease` | PASS |
| `:app:connectedNormalDebugAndroidTest` en AVD API 36 | **92 tests, 0 fallos, 8 skipped** |
| Nuevas pruebas de resolver, perímetro, profundidad, glass regional, CoverFlow y persistencia | PASS |

Capturas en `UI5_APM_03_06b6be1/` (fuera del repositorio):

- `before-album-detail.png` / `after-album-detail.png`: marco, luz interior y
  sombra de contacto de la portada de álbum.
- `before-playlist-detail.png` / `after-playlist-detail.png`: portada
  independiente de la playlist con marco Aero.
- `playlist-menu-toplight.png`: menú de tres puntos con la línea especular
  interna.
- `library-current.png`, `playlists-empty.png`: estado real de Biblioteca y
  Playlists en el AVD.

El AVD mantuvo el indicador de carga en Inicio tras completar el asistente;
Biblioteca, álbum y Playlists sí cargaron y se usaron para las capturas. No se
presenta una captura de Inicio como evidencia de este sprint. El banner Glass
tampoco pudo capturarse con una playlist poblada porque el AVD no persistió la
selección de pistas en esa sesión; su validación queda en código y pruebas
unitarias, no en certificación visual.

### Límites APM

- Certificación física, TalkBack, rotación, batería, temperatura y jank real
  siguen pendientes de un teléfono autorizado.
- No se instaló Haze ni se añadió una dependencia de blur de backdrop.
- El cristal auténtico se limita al banner de playlist; Dock, MiniPlayer y
  Audio Route conservan Smoked.
- Las capturas son de AVD API 36 y no equivalen a certificación Android física.

## UI5-FINAL-04 — Functional Repair & Precision Polish

SHA inicial del sprint: `710c1cbfae271b2eb6a8dc349d67106deb054a17`.
Base actual tras integrar Signal Truth Mobile R12: `83603eb`.
SHA de implementación UI5-FINAL-04 rebasado: `1ff5156`. La base remota ya incluye
`40f024c`, que corrige el lint heredado de Signal Truth; este informe registra la
validación posterior al rebase. El HEAD final publicado es el que valida la CI
exacta.

La base remota avanzó durante el sprint con Signal Truth R12. El commit se
rebasó sobre `83603eb` sin force push ni reaplicar parches; también se corrigió
un error de lint introducido por esa base en `SignalTruthReplayGainControls.kt`
(`StateFlow.value` leído durante composición, sustituido por
`collectAsStateWithLifecycle`).

### Tickets

| Ticket | Estado | Implementación |
|---|---|---|
| UI5-SEEK-01 | DONE (código + pruebas + instrumentación) | Los cinco estilos comparten un único `now_playing_seeker` con semántica, tap y arrastre. Wavy conserva su forma al pausar/arrastrar y solo anima cuando corresponde. Liquid Glass usa un carril óptico propio de 12 dp, sin blur ni backdrop falso. Bars/Continuous solo dibujan amplitudes PCM reales. Si una waveform se intentó y terminó sin datos utilizables, aparece `now_playing_seeker_fallback` con «Forma de onda no disponible; barra original»; no hay datos sintéticos ni fallback silencioso. Se mantienen los límites de 12 min, 2 MiB y 20 s. |
| UI5-DOCK-02 | DONE (código + prueba + instrumentación) | Se eliminó únicamente la línea coral de 24×2 dp y su spacer. El `DockTab` recupera altura mínima de 48 dp con `MichiSpacing.minTouchTarget`; fondo, borde, icono, tipografía, `Role.Tab`, cinco destinos y una sola fila se conservan. La regresión de 320 dp / fontScale 1.3 quedó cubierta por `DockLayoutComposeTest`. |
| UI5-MC-01 | DONE (código + prueba + compilación) | Nueva política `michiControlGlyph()`: teléfono, escritorio, servidor, receptor, sala y desconocido tienen icono propio. El badge local usa `PhoneAndroid`, no auriculares. Píldora visual de 34 dp dentro de objetivo de 48 dp, icono 17 dp, texto 12 sp, chevron 13 dp y punto de estado de 4 dp. |
| UI5-MC-02 | DONE (código + prueba) | El popup conserva su anclaje, cierre, foco y semántica; mantiene cabecera MICHI CONTROL, subtítulo, estados observados, error coral y luz especular. No inventa controladores, no presenta Stream sin `REMOTE_CONTROL` como seleccionable y no transfiere la cola al elegir controlador. |
| UI5-PL-01 | DONE (código + pruebas) | Importación privada con validación MIME/magic/decodificación, límites de tamaño/dimensiones, normalización EXIF, temporal + rename atómico y limpieza. Tokens `michi-playlist://visuals/...` resuelven a copias privadas; los `content://` antiguos siguen siendo legibles. Un fallo de sustitución conserva la imagen anterior y no borra recursos compartidos. Editor «Editar apariencia de playlist» con Portada y Banner independientes: elegir, previsualizar, ajustar, quitar, deshacer, cancelar y confirmar. |
| UI5-PL-02 | DONE (código + prueba) | `PlaylistVisualHero` es una sola tarjeta continua: banner a sangre, portada de 68 dp encastrada, placa informativa localizada, una silueta exterior y altura mínima de 190 dp. Cristalino desenfoca solo la banda detrás de la placa reutilizando el mismo painter; el banner base permanece nítido. |
| UI5-PL-06 | DONE (código + prueba) | Cápsula Smoked para «PLAYLIST» y placa translúcida localizada para título/cantidad; fallback opaco en alto contraste. Sin análisis de brillo por fotograma. |
| UI5-PL-04 | DONE (código + prueba) | Editor real de encuadre: arrastre sobre la vista previa, zoom acotado 1–3×, sliders accesibles de foco X/Y, restablecer, cancelar y confirmar. Persiste foco y escala por campo; la clave legacy `focal` sigue siendo compatible. |
| UI5-PL-05 | DONE (código + prueba) | Estado vacío junto al hero, sin «Vista compacta» ni acciones de reproducción inaplicables, con botón principal «Añadir canciones» que reutiliza el selector existente. El `+` superior permanece. |

### Validación UI5-FINAL-04

Workflow pre-rebase `d080ab472a38d378cbda9fc236d6c0e9`; workflow posterior
al rebase sobre Signal Truth R12 `4c283f0fa613ec8a00a7b7470c23a9c3`.

| Gate | Resultado |
|---|---|
| `:app:testNormalDebugUnitTest` completo | PASS (pre y post rebase) |
| `:app:testFdroidDebugUnitTest` completo | PASS (pre y post rebase) |
| Nuevos tests de seeker, Dock, Michi Control, store/import/editor de playlists | PASS |
| `lint` | PASS post rebase, tras corregir el lint heredado de Signal Truth |
| `assembleNormalDebug`, `assembleFdroidDebug` | PASS post rebase |
| `assembleNormalRelease`, `assembleFdroidRelease` | PASS pre rebase; el build local post rebase quedó bloqueado por `metadata.bin` corrupto en la caché de transforms de Gradle. La CI exacta del HEAD ejecuta ambos release en un runner limpio. |
| `:app:connectedNormalDebugAndroidTest` en AVD API 36 | **92 tests, 0 fallos, 8 skipped** (pre y post rebase) |
| Prueba funcional manual AVD API 36 | Home, reproducción, Dock, MiniPlayer, hero/editor de playlist y Now Playing verificados; defecto de wrap `Quitar` corregido a `Cerrar` |

La prueba focal de Dock se ejecutó después de corregir la altura mínima de 48 dp y volvió a pasar antes de la suite completa.

### Límites UI5-FINAL-04

- El teléfono físico se desconectó antes de la certificación. No se declaran capturas
  físicas nuevas de seekers, Dock, Michi Control o editor de playlists; la
  validación de comportamiento es JVM + instrumentación Compose en AVD API 36.
- La ruta de rotación EXIF está implementada, pero el fixture automatizado es un
  PNG sin rotación; falta una imagen EXIF rotada real.
- La prueba funcional en AVD API 36 confirmó Home, reproducción, Dock de una fila
  sin raya coral, MiniPlayer, hero unificado de playlist, editor independiente de
  portada/banner, encuadre y cancelación sin cambios. También reveló que a 360 dp
  la etiqueta `Cerrar ajuste` hacía que `Quitar` se partiera como `Qu / itar`.
  Se corrigió a `Cerrar` con `maxLines = 1`, conservando el objetivo de 48 dp;
  compilación, `PlaylistDetailScreenTest`, lint y build Normal debug pasan.
- El límite de waveform de 12 minutos y el presupuesto de 2 MiB se conservan; una
  pista fuera de esos límites muestra el fallback visible, no datos falsos.
- TalkBack real, rotación, batería, jank y temperatura siguen pendientes de un
  teléfono autorizado.

## UI5-POST — M1 a M5 (auditoría acumulada)

Base congelada: `95133dd2b8788f99a6dda9b7c132c02cf949c177`.
Plan: `MICHI_UI5_PLAN_MAESTRO_CONSOLIDADO.md` (SHA-256 no fijado en este
informe porque el archivo se leyó desde Descargas).

### M1 — seeker y editor

| Ticket | Estado | Implementación |
|---|---|---|
| U5-POST-01 | DONE | `PlaybackSessionManager.seekTo()` mantiene el seek local inmediato y, en remoto, envía posiciones a un `Channel.CONFLATED` con ventana de 120 ms. El worker comprueba epoch y endpoint antes de `sendSeek`; no se confirma una posición por el resultado HTTP. |
| U5-POST-02 | DONE | `GradientProgressBar` conserva un único host semántico/gestual de 48 dp (`heightIn(min = MichiSpacing.minTouchTarget)`) con el dibujo de 32 dp centrado. No hay handlers superpuestos ni un segundo control de seek. |
| U5-POST-03 | DONE | El preview del editor usa la misma altura mínima que el hero (`PLAYLIST_HERO_MIN_HEIGHT_DP`), `ContentScale.Crop`, alineación focal, escala y `TransformOrigin`; el recorte mostrado corresponde al encuadre final. |
| U5-POST-04 | DONE | `PlaylistPrivateImageImporter.pruneOrphans()` elimina solo copias privadas publicadas y no referenciadas. `PlaylistVisualStore.pruneOrphanPrivateImages()` conserva tokens referenciados y archivos ajenos, y se invoca al abrir Playlists y el detalle. |

### M2 — UI5-HEADER-06

- `MichiNavHost` deja de consumir el inset superior del sistema en el `Scaffold`;
  conserva horizontal y bottom. Cada destino mantiene su `statusBarsPadding`, por
  lo que el fondo de la pantalla llega a y=0 sin crear una banda opaca.
- `PlaylistScreen` gana `AeroPremiumBackdrop` y `statusBarsPadding`, igual que
  Inicio, Biblioteca, Buscar y Dispositivos.
- Capturas AVD: `01-home-top.png`, `02-library-top.png`, `03-playlists-top.png`,
  `04-search-top.png`, `05-devices-top.png`. No se observa banda negra ni doble
  techo en las cinco capturas comparadas.

### M3 — playlist hero editorial

- `PLAYLIST_HERO_MIN_HEIGHT_DP` pasa a 220 dp (rango objetivo 210–250).
- `playlistHeroCoverSizeDp()` devuelve 104 dp en 340 dp o más y 92 dp en pantallas
  estrechas; la portada deja de ser una miniatura.
- La placa de texto usa `weight(1f, fill = false)`, por lo que se ajusta al
  contenido en vez de ocupar todo el ancho restante. El banner sigue siendo la
  superficie dominante, la portada es el segundo ancla y se conserva una sola
  silueta exterior.
- Captura AVD `07-playlist-hero-m3.png`: banner amarillo dominante, portada
  I Love 80s de 104 dp y placa localizada; el estado vacío y `Añadir canciones`
  permanecen debajo.

### Validación M1–M3

Workflow `b17f738d7f3ca6022094235b1566b319`.

| Gate | Resultado |
|---|---|
| `:app:compileNormalDebugKotlin` | PASS |
| `:app:testNormalDebugUnitTest` + `:app:testFdroidDebugUnitTest` | PASS |
| `PlaylistHeroPolicyTest`, `PlaylistVisualStoreTest`, `PlaylistDetailScreenTest`, `RemoteCommandAuthorityTest` | PASS |
| `lint` + `assembleNormalDebug` + `assembleFdroidDebug` | PASS |
| `:app:connectedNormalDebugAndroidTest` API 36 | **92 tests, 0 fallos, 8 skipped** |
| Builds release locales | BLOQUEADO por metadata corrupta de transform de Gradle (`/home/cristian/.gradle/caches/8.13/transforms/6e33aecd...`); la CI debe ser la autoridad |

### M4 UI5-EDITORIAL-05 — controles compactos y Home editorial

- `AeroSegmentedChoice` con `treatment = Filter` conserva un objetivo táctil de
  48 dp y dibuja un riel visual de 36 dp. `AeroFilterChip` conserva 48 dp y
  dibuja una píldora de 34 dp. Semántica, rol, selección y testTags no cambian.
- Playlists sustituye los dos botones grandes de orden por un único
  `AeroFilterChip` «Ordenar: …» y un `DropdownMenu` con `Nombre` y `Más
  canciones`. El contenedor `playlist_catalog_sort` se mantiene; las opciones
  `playlist_sort_name` y `playlist_sort_tracks` viven dentro del menú.
- El hero de Inicio ya se construye exclusivamente con pistas, álbumes y
  playlists indexados reales y declara «SELECCIÓN DE TU BIBLIOTECA». La auditoría
  no encontró una fuente fiable de última playlist reproducida ni de origen de
  cola; no se añadió una tarjeta inteligente inventada ni se prometió
  `Continuar` sin sesión recuperable.
- Validación local M4: `PlaylistNoopRegressionTest`, `PlaylistDetailScreenTest`,
  `Ui5PolishComposeTest`, suite Normal/F-Droid completa, lint, builds Normal
  debug/F-Droid debug y API 36 instrumentation **92 tests, 0 fallidos, 8
  skipped** (workflow `b17f738d7f3ca6022094235b1566b319`, run 0020).

### M5 — auditoría acumulada y certificación

- SHA final del bloque: `834b954fba87eafe639fefa408ddf03c054f9b89`.
- CI exacta `42738724425` sobre ese SHA: **8/8 PASS**, incluido Quality Gate.
- `git diff --check master...HEAD`: limpio. Los marcadores encontrados son
  separadores de sección y el merge histórico esperado
  `83603eb Merge Signal Truth Mobile R12 on UI5`; no hay conflictos sin resolver.
- Diff acumulado del PR: **171 archivos, +12.527 / −577** (150 de app, 15 de
  player, informe, script Signal Truth, NOTICE, workflow, documento de terceros
  y un archivo de core). Es la rama UI5 + Signal Truth completa, no solo
  APM/FINAL-04.
- No se añadieron Haze, AGSL ni dependencias de blur; Coil y Media3 conservan
  sus versiones.
- `python3 tools/ui4_acceptance.py`: **47/47 PASS**.
- `scripts/verify_signal_truth_ui5.sh` con `ANDROID_HOME` y `JAVA_HOME` reales:
  `BUILD SUCCESSFUL`, `PASS: UI5 + Signal Truth tested and APK built at 834b954`.
- Validación local M4: suite Normal/F-Droid completa, lint, builds debug
  Normal/F-Droid y API 36 instrumentation **92 tests, 0 fallidos, 8 skipped**
  (workflow `b17f738d7f3ca6022094235b1566b319`, run 0020). Los builds release
  locales estaban bloqueados por metadatos de transformación de Gradle
  corruptos; los Jobs E/F de CI en runner limpio son la autoridad release.
- `adb devices -l` no devolvió ningún dispositivo: el teléfono físico sigue
  desconectado. AVD y CI no sustituyen la certificación física.

### GO / NO-GO

- **GO técnico** para el estado integrado del PR: implementación, pruebas
  unitarias/Compose, lint, builds Normal/F-Droid, instrumentación API 36 y CI
  exacta 8/8.
- **NO-GO físico** para merge/release: faltan TalkBack, rotación, batería,
  temperatura, jank, audio remoto real y capturas en el teléfono autorizado.
- **NO-GO de release** hasta que el propietario autorice el cierre y el PR deje
  de ser draft. No se hace merge, release, reset ni force push en este bloque.

## UI6 — Álbumes y artistas en tarjetas

- Commit: `0550d68` — `feat(ui6): integrate album and artist card views`.
- Alcance: 14 archivos, +1736/−48. No sustituye Mosaico, CoverFlow ni Lista.
- Álbumes: cuarta vista `Tarjetas` con `MichiCardDeck`. El selector es un menú
  anclado de cuatro opciones (`Mosaico`, `CoverFlow`, `Tarjetas`, `Lista`), con
  la vista actual marcada y `testTag` por opción. `Mosaico` sigue siendo el
  valor predeterminado.
- Identidad: las tarjetas consumen la misma presentación
  `albumCoverFlowPresentation` que CoverFlow. Una selección ambigua no se
  convierte en índice persistido; la tarjeta frontal es la única accionable.
  Deslizar no abre el álbum, no reproduce y no elimina nada. Abrir usa el mismo
  callback de navegación; las acciones de reproducción, aleatorio y añadir a
  playlist reutilizan `CoverFlowAlbumActions`.
- Artistas: `ArtistMosaicGrid` conserva el mosaico circular por defecto y añade
  una alternativa `Tarjetas` con retratos circulares, no portadas de álbum. La
  selección se reconcilia por crédito canónico y se reinicia por
  `libraryScopeKey`.
- Movimiento/contraste: `MichiCardDeck` respeta movimiento reducido y alto
  contraste; la pila conserva profundidad sin rotación decorativa en contraste
  alto.
- Correcciones de integración: comprobación nullable de `localImagePath`,
  captura de `availableHeight` fuera del DSL de `Column`, e imports inválidos de
  `assertExists`/`assertDoesNotExist` retirados del test UI6.
- Validación: compilación Normal PASS; tests UI6 declarados PASS
  (`MichiArtworkMemoryTest`, `MichiCardDeckTest`, `MichiCardDeckUiTest`,
  `AlbumArtistCardContractsUiTest`, `AlbumViewModeComponentsTest`,
  `AlbumViewModePolicyTest`); 16 regresiones focales de álbum, artista,
  CoverFlow y Dock PASS.

## Signal Truth R17 — recuperación USB y evidencia causal

- Commit: `34ccb3d` — `feat(signal-truth): integrate mobile R17 and USB recovery`.
- Alcance: 17 archivos, +484/−38. SHA-256 del parche aplicado:
  `b77aa76604b3710c151efcf302233eb8620822fc0938d5f4d67bf3b7edebfbfc`.
  El parche aplicó limpio sobre el HEAD de UI6; no se usó `--3way` ni `--reject`.
- Reproducción: `LocalPlaybackGateway.playFromUserGesture(): Boolean` conserva
  un resultado booleano hasta `PlaybackSessionManager`. Solo una acción local
  explícita puede solicitar la recuperación; `true` significa que el
  MediaController aceptó la petición, nunca que AudioTrack o el DAC confirmen
  sonido.
- USB: tras perder el DAC observado, la reproducción queda en pausa y conserva
  pista/posición. Un Play manual busca un altavoz del teléfono seguro, solicita
  la preferencia nativa y la valida antes de reanudar. No hay cambio automático
  a otro DAC. Un error de salida de AudioTrack no se interpreta como archivo
  corrupto ni salta de pista.
- Signal Truth: los ajustes configurados pero fuera de la ruta activa se separan
  en `controls`; no se dibujan como etapas del flujo. PEQ solo aparece activo con
  evidencia de fotogramas de la revisión vigente.
- Michi Stream: un contador de envíos UDP de la sesión activa se muestra como
  recibo del emisor local, nunca como recepción, reproducción o DAC remoto. La
  hoja rechaza muestras de sesiones mezcladas y solo consulta mientras está
  abierta.
- Validación: compilación app/player/link-client PASS; tests USB DAC/hotplug,
  proyector Signal Truth, recibo de auriculares y recibo RTP PASS; suites
  completas `:player:testDebugUnitTest` y `:app:testNormalDebugUnitTest` PASS.
- Límite: la desconexión física de un DAC y la recepción RTP real siguen
  requiriendo hardware; no se declaran certificadas.

## Cierre técnico master — UI6 + R17

- HEAD de integración: `34ccb3dc56f12e692242e7d6c15973820d6e4973`
  (`0550d68` UI6 → `34ccb3d` R17; base `4ded35616034eca14361983f8a5a43e538c770af`).
- Suites completas `:data:testDebugUnitTest`, `:player:testDebugUnitTest`,
  `:app:testNormalDebugUnitTest` y `:app:testFdroidDebugUnitTest` PASS.
- `lint`, `assembleNormalDebug` y `assembleFdroidDebug` PASS.
- Instrumentación Normal en AVD API 36: **92 tests, 0 fallos, 8 skipped**.
- Tests UI6 declarados y 16 regresiones focales de álbum/artista/CoverFlow/Dock
  PASS. Tests R17 de USB DAC/hotplug, proyector Signal Truth, recibo de
  auriculares y recibo RTP PASS.
- Los builds release locales quedaron bloqueados por metadatos corruptos de la
  caché de transformación de Gradle
  (`~/.gradle/caches/8.13/transforms/6e33aecd2a956a578b7ba21f1e65716f/metadata.bin`).
  No es un fallo de código; la autoridad de release es la CI exacta (Jobs E/F).
- El teléfono físico no está conectado; la certificación Android física,
  TalkBack, rotación, batería/temperatura y jank real siguen pendientes. El AVD
  API 36 no sustituye esa certificación.
- GO técnico para integración en master. NO-GO para release/merge hasta la
  certificación física y autorización explícita. El PR histórico #18 permanece
  en draft; este bloque no crea ramas ni PRs nuevas.

## v2.3 — Reconciliación, LIB-ND y cierres de calidad

### Fuente

- Plan v2.3: SHA-256 `32ed79f6c17e4c23b4177fea4211869a8df4d8d431a2f76f86d41248f329979b`.
- Parche acumulativo: SHA-256 `ed5955e19eb2305b93963013e980d670d8766b595e6639ba9851b32d569ec1fc`,
  idéntico al valor requerido.
- Base de trabajo: `master@1cf321b007f80bbd99bdba3f8ab81d17cd664764`.
  Integración publicada en `master@fbfe0ac4878309352b599e285f8819917c38289b`
  tras autorización expresa; sin rama ni PR nueva, sin release.
- Diff reconciliado: `/tmp/opencode/ui23/reconciled.diff`, 58 archivos,
  138 hunks, SHA-256 `6a6f110d5abef1a7b3ce37a9ace6464362f19b52a717d951973a50975ba7ac04`.

### Bloqueos corregidos

1. **`LibraryExclusionDao.copyLegacySource()`** ya no usa
   `@Query("INSERT ... SELECT ..."): Int`, que Room no puede verificar de forma
   estable. Es un método `@Transaction` sobre el propio DAO: lee las filas
   heredadas, copia solo metadatos con `hide()` (IGNORE) y devuelve el número
   real de filas nuevas. No toca audio ni playlists.
2. **Room 8→9** exporta `data/schemas/org.michimusic.data.cache.MichiDatabase/9.json`
   mediante el proceso oficial de KSP. El esquema pasa de 19 a 20 entidades y la
   única tabla nueva es `library_exclusions` con PK compuesta
   `(sourceId, trackId)`. `MIGRATION_8_9` está registrada en `DataModule`.
   `Migration8To9Test` conserva una playlist y una pista existentes y añade una
   exclusión después de migrar.
3. **Reconciliación del parche:** 52 archivos/136 fragmentos de origen; 11
   archivos no aplicaban sobre el checkout real y se resolvieron manualmente
   (`MusicRootAuthority`, `PlaylistsViewModel`, `SettingsScreen`,
   `MusicRootAuthorityTest`, `PlaylistRepository`, `SafMediaRepositoryTest`,
   `ResumableTransferTest`, `DataModule`, `LocalMediaRepository`,
   `AlbumDetailScreen`, `ArtistDetailScreen`). No se usó `--3way` ni `--reject`.
   El diff reconciliado contextual se generó contra `HEAD` limpio, excluyendo
   este informe para que su hash sea estable:
   `/tmp/opencode/ui23/reconciled.diff`, 58 archivos, 138 hunks,
   SHA-256 `6a6f110d5abef1a7b3ce37a9ace6464362f19b52a717d951973a50975ba7ac04`.
   `git apply --check --cached` sobre un índice limpio de `HEAD`: **PASS**.
   Los 6 archivos adicionales sobre el parche son los generados/necesarios para
   el esquema Room, la búsqueda, la marca de reproducción observada y sus tests.
4. **Propagación de canciones ocultas:** `LocalMediaRepository.fetchTracksResult`
   aplica la exclusión de la fuente activa antes de publicar la caché;
   `PlaylistRepository.getTracksForPlaylist` no resucita una pista oculta desde el
   fallback de `TrackDao`; `SearchViewModel` filtra también la caché sincronizada
   de la fuente activa; `MusicRootAuthority` concentra hide/restore/batch. Ni el
   borrado de playlist ni `clearDownloads()` eliminan audio o copias completas.
5. **Identidades:** la exclusión usa fuente estable + huella de archivo
   (ruta/tamaño/duración/fecha), no título/artista mutable. La migración de una
   raíz SAF copia las exclusiones al nuevo `sourceId` antes de confirmar el
   cambio; si falla, no se confirma la migración. Pruebas cubren fuente
   incorrecta, huella cambiada, duplicados, lote atómico, reinicio de caché y
   caché sincronizada.

### UI/UX cerrados o reconciliados

- **PL-03:** filas de playlist con densidad, drag con una única escritura al
  soltar, marcador de pista activa basado en `resolvePlaybackPresentation`
  (sesión/estado real, no en el último tap), menú de opciones y fallback de
  mover arriba/abajo.
- **HDR-01:** el Scaffold no consume el inset superior; cada uno de los cinco
  destinos posee `statusBarsPadding()` y un `AeroPremiumBackdrop` desde y=0.
  Prueba de propiedad de insets añadida; no se detectó doble techo ni franja
  negra en la auditoría de código.
- **Vinyl Crate:** `MichiVinylCrate` está conectado a la vista Tarjetas de
  Álbumes con fundas 1:1, selección estable y acciones canónicas; CoverFlow,
  Mosaico y Lista permanecen disponibles.
- **Seekers:** se mantiene un único control `now_playing_seeker` y la política de
  waveform/fallback; `WaveformVisualPolicy` y los tests de composición cubren
  los cinco estilos sin datos sintéticos.
- **Estados y ajustes:** se retiraron interruptores sin coordinador real
  (escaneo al iniciar/descarga de carátulas), se añadió Canciones ocultas con
  restauración, diagnóstico de almacenamiento y creación de subcarpetas SAF
  solo con permiso de escritura explícito. No se simula una operación exitosa.

### Validación real ejecutada

| Gate | Resultado |
|---|---|
| `:data:testDebugUnitTest` | PASS |
| `:player:testDebugUnitTest` | PASS |
| `:app:testNormalDebugUnitTest` | PASS |
| `:app:testFdroidDebugUnitTest` | PASS |
| `:michi-link-client:testDebugUnitTest` | PASS |
| `Migration8To9Test` (playlist + pista preservadas; tabla nueva) | PASS |
| `LibraryVisibility*`, `MusicRootAuthorityTest`, `SafMediaRepositoryTest`, `ResumableTransferTest`, reorder policies | PASS |
| `lint` | PASS |
| `assembleNormalDebug`, `assembleNormalRelease`, `assembleFdroidDebug`, `assembleFdroidRelease` | PASS |
| `:app:connectedNormalDebugAndroidTest` en AVD API 36 | **93 tests, 0 fallos, 8 skipped** |
| CI exacta `42873732379` sobre `fbfe0ac` | **8/8 PASS**, incluido Quality Gate |

La caché de transforms de Gradle estaba corrupta en el entorno local; se
detuvo el daemon y se movió únicamente esa caché a `/tmp/opencode` para
reconstruirla. No se cambió ninguna versión de dependencia, configuración
Gradle ni código para ocultar el fallo.

### Veredictos separados

- **GO_CODE:** sí. Compila, Room 8→9 exporta y migra, y las suites unitarias y
  de composición ejecutadas pasan.
- **GO_CI:** sí. `master@fbfe0ac` pasó la CI exacta `42873732379` con 8/8 jobs,
  incluido Quality Gate. No se reutilizó ninguna CI anterior.
- **GO_VISUAL:** pendiente. Falta aprobación visual/física en dispositivo real;
  el AVD API 36 no certifica materiales, TalkBack, rotación, batería ni jank.
- **GO_RELEASE:** NO-GO. No hay autorización de release, no se ha validado
  hardware y el PR/entrega no debe publicarse sin revisión.

## Auditoría correctiva P0/P1 post-integración

Base auditada: `master@c9756c9a40e2593fe68f6ea9eedfcda210daae0f`.
Integración publicada en `master@9857365f1e64820e9e747ce5cf11929c88230c21`;
CI exacta `42895634245`: **8/8 PASS**, incluido Quality Gate.

### P0 — Identidad entre bibliotecas

| ID | Gravedad | Archivo | Causa raíz | Corrección |
|---|---|---|---|---|
| ID-P0-01 | P0 | `TrackDao.kt` | `getTrackById(id)` usaba `ORDER BY sourceId LIMIT 1` y elegía una biblioteca arbitraria. | Eliminado. `getTracksById(id)` devuelve todos los propietarios; los llamadores fallan cerrado si hay más de uno. |
| ID-P0-02 | P0 | `PlaylistRepository.kt` | `getById` caía a `getAllPlaylists().find` y `getTracksForPlaylist` resolvía IDs por el primer `cached_tracks`. | Añadido `sourceId` a `Playlist`; `getById` local no cruza fuentes; `getByIdForSource` exige la fuente exacta; la resolución devuelve `PlaylistTrackResolution` con `tracks`, `unresolvedTrackIds`, `ambiguousTrackIds` y `hiddenTrackIds`. |
| ID-P0-03 | P0 | `PlaylistRepository.kt` | Playlists remotas y locales compartían el mismo catálogo de IDs. | El catálogo local solo publica playlists con `sourceId == LOCAL_SOURCE_ID`; las playlists remotas permanecen aisladas en Room hasta que exista una ruta source-aware. |
| ID-P0-04 | P0 | `SyncedTrackRepository.kt`, `TrackDao.kt`, `SyncWorker.kt` | `markDownloaded`/`markDownloadedWithPath` actualizaban todas las filas con el mismo ID. | Firma y SQL exigen `sourceId`; `SyncWorker` pasa el `deviceId` real. `getById(sourceId, id)` exige la clave compuesta. |
| ID-P0-05 | P0 | `MichiPlaybackService.kt`, `LibraryProvider.kt` | La sesión multimedia y el provider resolvían un ID sin fuente. | Prioridad a la biblioteca local activa; después `getTracksById(id).singleOrNull()`. Si hay dos propietarios, no se resuelve ninguno. |

Pruebas nuevas: `PlaylistTrackIdentityTest` cubre colisión local/remota, dos servidores con el mismo ID, playlist remota que no cae a otra fuente y resolución ambigua explícita. `DaoTests` verifica que `markDownloaded` solo afecta a la fuente indicada. `PlaylistRepositoryTest` conserva las pruebas de orden, slots irresolubles y persistencia.

### P0 — Protección de archivos

Auditoría de APIs destructivas:

- `LocalMediaRepository`, `LibraryExclusionDao`, `LibraryVisibilityPolicy`, `SafMediaRepository`, `MusicRootAuthority`, `PlaylistRepository`, `SearchViewModel`: **ninguna ruta de ocultar/restaurar/eliminar playlist toca audio**.
- `LinkTransferManager.clearDownloads()` solo limpia estado en memoria; no borra archivos completos.
- `LinkTransferManager`, `AirCatTransferClient`, `SyncWorker` y `ArtistArtworkStore` solo eliminan temporales, backups de transferencia o cachés de carátulas. La eliminación de un archivo descargado solo ocurre en una transferencia fallida no registrada y con restauración previa; no es una acción de biblioteca.
- `deleteForSource` de SAF elimina el índice cacheado de esa fuente, no documentos del proveedor.
- Las exclusiones se guardan por fuente + huella y sobreviven a reinicio/reescaneo/migración; la migración SAF copia antes de confirmar.

### P1 — Propagación, Playlists y Signal Truth

- Las canciones ocultas se filtran en `fetchTracksResult` antes de Albums/Artists/Home/Favorites/History/Search; `PlaylistRepository` las excluye del fallback DAO; `SearchViewModel` filtra la caché sincronizada de la fuente activa.
- La cola/Now Playing no se interrumpe al ocultar una pista; conserva la identidad observada hasta una decisión explícita.
- PL-03: marcador de pista activa basado en `resolvePlaybackPresentation`, drag con una escritura al soltar, alternativa accesible arriba/abajo y persistencia con `writeMutex`.
- Signal Truth R18/R19: se auditó la cadena `Archivo → PCM → AudioTrack → ruta/dispositivo`, la separación `DECLARED/CONFIGURED/OBSERVED/UNKNOWN`, el PEQ solo con ruta de auriculares confirmada y el receipt RTP como evidencia de emisor local, nunca de recepción/DAC. No se encontró una afirmación bit-perfect/DSD. No se cambió el motor de audio.
- HDR-01: ownership de insets verificado con `HeaderInsetOwnershipTest`; cinco destinos con `statusBarsPadding` y backdrop desde y=0.
- Vinyl Crate, seekers y estados de Ajustes conservan Aero/Inter y los componentes canónicos.

### Validación

| Gate | Resultado |
|---|---|
| Suites data/player/Normal/F-Droid/link-client | PASS |
| `PlaylistTrackIdentityTest`, `PlaylistRepositoryTest`, `DaoTests` | PASS |
| Migración Room 8→9 | PASS |
| Lint | PASS |
| Normal/F-Droid debug + release | PASS |
| Instrumentación AVD API 36 | 93 tests, 0 fallos, 8 skipped |
| UI4 static acceptance | 47/47 PASS |

### Veredictos

- **GO_CODE:** sí para el working tree.
- **GO_IDENTITY:** sí para las rutas actuales; la UI de playlists remotas queda deliberadamente aislada hasta disponer de una ruta source-aware.
- **GO_LIBRARY_SAFETY:** sí en código y pruebas; la certificación física de proveedores SAF reales sigue pendiente.
- **GO_SIGNAL_TRUTH:** sí para los contratos R18/R19 disponibles en el plan; no se certifican rutas físicas/DAC/RTP.
- **GO_CI:** sí. `master@9857365` pasó la CI exacta `42895634245` con 8/8 jobs,
  incluido Quality Gate.
- **GO_VISUAL:** pendiente; faltan capturas y aprobación en dispositivo real.
- **GO_PHYSICAL:** pendiente; el AVD API 36 no sustituye un teléfono.
- **GO_RELEASE:** **NO-GO**.

## KILLCRITIC post-integración — identidad, playlists y seeks

Base auditada: `master@9a7a16ff8b5b013ee1b7825e0609cc73033fbf70`.
Integración publicada en `master@8f1b29312e409948063e28a725bbd3103a24ab4e`;
CI exacta `42908021081`: **8/8 PASS**, incluido Quality Gate.

### P0 corregido

- **Playlist + exclusiones:** `resolvePlaylistTracks()` ya no usa `getTracksById()` para una playlist local. Una referencia local ausente de la biblioteca activa queda `unresolvedTrackIds`; no se recupera de una fila DAO oculta ni de otra fuente. Las pistas ocultas locales se separan en `hiddenTrackIds`.
- **Playlist local con referencia remota:** una coincidencia única en caché remota tampoco demuestra procedencia. La referencia se conserva sin resolver; no se asigna por título, artista ni primer propietario.
- **Sync por servidor:** `getDownloadedIds(sourceId)` y `getDownloadedTrackFingerprints(sourceId)` consultan solo el `sourceId` real. `SyncWorker` y `SyncViewModel` pasan el dispositivo correspondiente. Dos servidores con el mismo `trackId` ya no comparten el estado de descarga.
- **DAO y sesión:** `getTracksById(id)` devuelve todos los propietarios; `LibraryProvider` y `MichiPlaybackService` solo aceptan una coincidencia única, y priorizan la biblioteca local activa. `markDownloaded` exige `sourceId`.
- **Archivos:** las rutas de ocultar/restaurar/eliminar playlist siguen siendo metadata-only; la auditoría no encontró borrado de audio desde acciones de biblioteca.

### UI/UX implementado

- **PL-CAT-11:** título 28/32 sp, contador 12–13 sp, ordenación compacta no seleccionada permanentemente, tarjetas con portada 64 dp, altura mínima 92 dp, título 16 sp y padding 12 dp.
- **PL-DETAIL-11:** eliminado el rectángulo del título y la segunda imagen localizada. El banner usa un scrim continuo de altura completa (transparente → 0.12 → 0.52) y el texto lleva sombra suave; alto contraste usa superficie sólida continua. Portada 92/104 dp, hero 220 dp, título 22/26 sp, acciones Reproducir/Mezclar con jerarquía 1f/0.55f.
- **Filas:** densidad compacta 44 dp de portada, 10 dp vertical y 6 dp entre filas; marcador de pista activa basado en `resolvePlaybackPresentation`.
- **ST-POPUP-07:** Signal Truth deja `ModalBottomSheet` y pasa a `Dialog` + `AeroFloatingSurface` (radio 26 dp, 82 % de altura, padding 18 dp). Conserva etapas, evidencia, una expansión y acciones R18/R19.
- **SEEKERS:** Barras y Continuo aumentan su masa real (barras 1,8–10,2 dp; continuo 12,5 dp por perfil), mantienen reducción por picos y no inventan datos. `LIQUID_GLASS` cambia de etiqueta pública a **Aero Capsule**, conserva la clave persistida y usa rail anodizado de 9 dp, canal 4 dp y thumb 12/14 dp.

### Validación

| Gate | Resultado |
|---|---|
| Suites data/player/Normal/F-Droid/link-client | PASS |
| `PlaylistTrackIdentityTest`, `PlaylistRepositoryTest`, `SyncedTrackRepositoryTest`, `DaoTests` | PASS |
| `AeroSeekerStylePolicyTest`, `AeroSeekerCompositionTest`, `PlaylistDetailScreenTest`, `PlaylistNoopRegressionTest` | PASS |
| Lint | PASS |
| Normal/F-Droid debug + release | PASS |
| Instrumentación AVD API 36 | 93 tests, 0 fallos, 8 skipped |

Capturas parciales en `/tmp/opencode/killcritic/`: catálogo vacío, catálogo con una playlist, detalle vacío, selector de pistas y detalle poblado. La captura con banner amarillo y la del popup Signal Truth no se completaron porque el AVD se cerró durante la selección del banner; no se declaran aprobadas.

### Gates

- **GO_IDENTITY:** sí para las rutas actuales; playlists remotas siguen fuera de la UI local hasta una ruta source-aware.
- **GO_LIBRARY_SAFETY:** sí en código y pruebas; falta validación física de proveedores.
- **GO_SYNC:** sí para identidad por servidor; falta prueba con dos servidores reales y checksum/reanudación física.
- **GO_PLAYLIST_VISUAL:** parcial; catálogo y detalle poblado revisados, falta el caso banner amarillo.
- **GO_SIGNAL_TRUTH_VISUAL:** pendiente; cambio de contenedor compilado, sin captura final.
- **GO_SEEKERS:** código y pruebas de política pasan; falta captura comparativa de los cinco estilos.
- **GO_CODE:** sí en working tree.
- **GO_CI:** sí. `master@8f1b293` pasó la CI exacta `42908021081` con 8/8 jobs.
- **GO_VISUAL:** pendiente.
- **GO_PHYSICAL:** pendiente.
- **GO_RELEASE:** **NO-GO**.

## Evidencia local

La integración inicial usó el workflow gestionado
`34c7065a22805c88c3bc85848ca9b155`. UI5-POLISH-02 usa el workflow
`c5ddd3dc1e69939d98c02b7909da684a`.

| Gate | Resultado |
|---|---|
| `:data:testDebugUnitTest` | PASS |
| `:app:testNormalDebugUnitTest` | PASS |
| `:app:testFdroidDebugUnitTest` | PASS |
| `lint` | PASS |
| `:app:compileNormalDebugAndroidTestKotlin` | PASS |
| `:app:compileFdroidDebugAndroidTestKotlin` | PASS |
| `assembleNormalDebug`, `assembleFdroidDebug` | PASS |
| `assembleNormalRelease`, `assembleFdroidRelease` | PASS |
| `tools/ui4_acceptance.py` | 47/47 PASS |
| Contrato modal Aero | 16/16 PASS |
| Contrato release Aero | 18/18 estático PASS |
| Verificador launcher UI3 | PASS |
| UI5-POLISH-02: app Normal/F-Droid, lint, AndroidTest compile, debug/release | PASS |
| `DockLayoutComposeTest` + `Ui5PolishComposeTest` en AVD API 36 | PASS |
| Matriz real-font 320/360/480 dp × 1.0/1.5/2.0 | PASS, sin elipsis ni targets <48 dp |
| CI exacta `42700995591` sobre `08d7abc` | **8/8 PASS**, incluido Quality Gate |
| CI exacta `42735461707` sobre `a837354` (M1–M3) | **8/8 PASS**, incluido Quality Gate |
| CI exacta `42738724425` sobre `834b954` (M4/M5) | **8/8 PASS**, incluido Quality Gate |
| M5 `tools/ui4_acceptance.py` | 47/47 PASS |
| M5 `scripts/verify_signal_truth_ui5.sh` sobre `834b954` | PASS, APK construido |
| Certificación física Android | **NO EJECUTADA**, teléfono desconectado |

La compilación de AndroidTest no equivale a ejecución de toda la suite. Las
matrices focales de Dock y UI5-POLISH-02 sí se ejecutaron en el AVD API 36. No se
ejecutaron pruebas físicas ni se instaló este candidato en el teléfono.

El commit documental posterior solo registra este resultado; su CI exacta se
consulta en el PR #18.

## Revisión independiente

La revisión independiente encontró un P1 real en la búsqueda: una consulta como
`"rock "` se rechazaba antes de publicar una generación válida y podía dejar el
spinner permanente. Se corrigió publicando el snapshot rechazado y se añadió una
regresión que exige `requestGeneration >= 0` y ausencia de estado de búsqueda.

UI5-POLISH-02 revisa deliberadamente ese comportamiento: una consulta con
espacios finales ya no se rechaza, sino que se normaliza y busca `rock`. La
prueba de generación sigue exigiendo que el snapshot resuelto no deje un spinner
permanente.

También señaló que los tokens de acabado por defecto de UI5 no son idénticos a
los de UI4: los roles, acabados y comportamiento de contraste se conservan, pero
los valores alfa de `Satin`, `Anodized` y `Smoked` cambian deliberadamente. No se
revirtió ese cambio visual de UI5; se documenta para no afirmar una preservación
pixel-perfect.

## Límites que siguen abiertos

- Casos físicos consolidados U45-01…U45-20 y de cola U45-21…U45-33.
- TalkBack, foco, rotación y tamaños reales de interacción.
- Waveform con audio local real y formatos PCM admitidos.
- Salto remoto `/queue/jump` contra un servidor real y sus estados
  PENDING/ACCEPTED/CONFIRMED.
- Glass/backdrop en hardware API 31+ y su fallback.
- Rendimiento, batería y temperatura.
- La integración no autoriza merge ni release.

El PR debe permanecer en borrador hasta que el CI del head exacto y los gates
físicos aplicables estén resueltos.

HEAD auditado: `834b954fba87eafe639fefa408ddf03c054f9b89`; CI exacta
`42738724425` **8/8 PASS**. Cualquier commit documental posterior solo cambia
este informe y requiere su propia CI exacta.

## KILLCRITIC QUALITY CLOSURE 02 — sync, procedencia, Playlists, Signal Truth y seekers

Base: `master@c542181d0068ecffed6fd8b72631b08cd732de0a` (árbol limpio).
Integrado y publicado en `master@7d1c942c9f94ea4995ee680a840f84b5a8c773ae`;
CI exacta `42929058047`: **8/8 PASS**, incluido Quality Gate.

### SYNC-FINGERPRINT-02

Causa raíz: `SyncPreflightService` recibía un `Set<String>` que mezclaba IDs con
`título_artista_duración`; una coincidencia heurística podía producir
`SKIP_ALREADY_EXISTS`.

Solución implementada:

- `SyncIdentityVerdict`: `IDENTICAL_VERIFIED`, `SAME_OWNER_REFERENCE`,
  `POSSIBLE_DUPLICATE`, `DIFFERENT_SOURCE`, `CONTENT_MISMATCH`, `UNKNOWN`.
- `LocalSyncTrackEvidence`: propietario, trackId, metadatos, tamaño, checksum,
  ruta, verificación física y completitud.
- `SyncIdentityPolicy`: solo `IDENTICAL_VERIFIED` omite la descarga. Exige
  archivo físico, copia completa y una señal comparable (checksum coincidente o
  tamaño coincidente). Un `trackId` de otro servidor es `DIFFERENT_SOURCE`; los
  metadatos solos son `POSSIBLE_DUPLICATE`; ambos se descargan.
- `SyncedTrackRepository.getDownloadedTrackEvidence(sourceId)` comprueba la
  existencia física en `Dispatchers.IO`; no confía en la fila Room `downloaded`
  ni calcula hashes en el hilo principal.
- `SyncWizardScreen`, `SyncViewModel` y `MichiNavHost` consumen evidencia
  estructurada. `SyncWorker` conserva su verificación física y de checksum antes
  de omitir una pista.
- Pruebas nuevas: mismo ID en otro servidor, metadatos idénticos con contenido
  distinto, fila descargada con archivo desaparecido, checksum distinto, checksum
  ausente con tamaño físico coincidente, tamaño físico distinto y reutilización
  verificada. Las pruebas existentes de reanudación y almacenamiento se
  adaptaron al contrato, sin ocultar regresiones.

### TRACK-PROVENANCE-02

- `Track` incorpora `sourceId` con valor por defecto, preservando serialización
  histórica. `sourceId` identifica al propietario; `TrackSource` describe dónde
  viven los bytes. No se colapsan.
- `CachedTrack.toTrack()` conserva `sourceId` y clasifica: descargado → `SYNCED`;
  raíz SAF/MediaStore (`safroot_`, `mediastore:`) → `LOCAL`; otro propietario no
  descargado → `STREAMING`.
- `LibraryProvider`, `MichiPlaybackService` y `SyncedTracksViewModel` conservan
  el propietario. La reproducción remota usa el endpoint activo como `sourceId`.
- Pruebas: SAF local, fila remota en streaming, fila sincronizada físicamente y
  dimensiones independientes `sourceId`/`TrackSource`.

### PL-EDITORIAL-02 y PL-HERO-FINAL-02

- Catálogo: título 28/32 sp, contador 12–13 sp, separación título/contador 4 dp,
  separación con contenido 16 dp; chip de orden 34 dp visible dentro de objetivo
  de 48 dp; tarjetas de mínimo 92 dp, portada 64 dp, radio 11 dp, padding 12 dp,
  título 16 sp semibold y contador 12 sp.
- Gestión contextual: pulsación larga sobre la acción de reproducción abre
  Renombrar, Fijar/Desfijar, Gestionar canciones y Eliminar. Se reutilizan los
  diálogos y operaciones existentes; no se añadió un tercer bloque visual ni se
  duplicó la reproducción.
- Hero: fuera la placa y el `playlist_visual_tray`; el texto se apoya directamente
  en un scrim vertical continuo (0 → 0.12 → 0.52) y una sombra suave. Alto
  contraste usa una superficie continua. Radio 20 dp, altura mínima 220 dp,
  portada 92/104 dp, padding 14 dp, etiqueta 10 sp, título 22/26 sp.
- CTAs: Reproducir primario de 52 dp y peso 1; Mezclar `Quiet`, peso 0.55 y misma
  altura táctil. Gestión de apariencia/renombrado/eliminación sigue en el menú
  superior del detalle.
- Filas compactas: portada 44 dp, padding vertical 10 dp, separación 6 dp,
  padding horizontal 12 dp; título `bodyMedium` semibold y artista `bodySmall`.
  El indicador activo depende de la reproducción observada.

### ST-COMPACT-POPUP-02

- `Dialog` + `AeroFloatingSurface`, radio 26 dp, anchura máxima 420 dp, márgenes
  laterales 16 dp y verticales 24 dp.
- Altura natural según contenido, con máximo 84 % del área útil; la cadena de
  etapas pasa a una columna desplazable que solo scrollea cuando excede el alto.
  Ya no se impone 82 % fijo.
- Cabecera 19/23 sp, cierre con objetivo de 48 dp; resumen de ruta en superficie
  Aero compacta; una sola etapa expandida por `stableId`; conectores sólidos solo
  entre etapas con arista real.
- Se conservan R18/R19, las diferencias declarado/configurado/observado/
  confirmado/sin verificar y la autoridad de los controles.

### NP-WAVE-AUTO-02 y AERO-CAPSULE-01

- La clave de la envolvente incluye `sourceId`, ruta, tamaño, duración, origen y
  host remoto. Cambiar de Barras a Continuo reutiliza la misma decodificación;
  cambiar de pista cancela el trabajo anterior mediante `produceState`.
- Solo se decodifica PCM local elegible, con duración válida y sin host remoto.
  Alta contradicción usa Aero Original. Si no hay envolvente válida se muestra el
  fallback original y un aviso discreto; nunca se inventan amplitudes.
- Barras: líneas discretas, extremos redondeados, separación visible y reducción
  determinista de picos; masa visible aproximada 26–28 dp.
- Continuo: silueta cerrada con contorno superior/inferior y relleno; masa
  aproximada 29 dp; progreso por máscara y playhead visual.
- Aero Capsule conserva la clave persistida `LIQUID_GLASS`, etiqueta pública
  “Aero Capsule”, rail de 9 dp, canal de 4 dp, thumb 12/14 dp y objetivo táctil
  de 48 dp. `GradientProgressBar` sigue siendo la única autoridad de gestos,
  semántica, testTag y seek.

### Validación ejecutada

| Comprobación | Resultado |
|---|---|
| `:core`, `:data`, `:player`, `:app` Normal/F-Droid y `:michi-link-client` unit tests | PASS |
| `SyncPreflightServiceTest`, `SyncedTrackRepositoryTest`, `CachedTrackMapperTest`, `PlaylistTrackIdentityTest`, `PlaylistRepositoryTest` | PASS |
| `PlaylistNoopRegressionTest` (incluye pulsación larga y renombrado) | PASS |
| `PlaylistDetailScreenTest`, `PlaylistVisualPolicyTest`, `AeroSeekerStylePolicyTest` | PASS |
| Lint | PASS |
| Normal/F-Droid debug y release | PASS |
| Instrumentación Normal API 36 | 93 tests, 0 fallos, 8 skipped |

### Evidencia visual

Capturas reales en `/tmp/opencode/closure02/`:

- `06-playlists.png`: catálogo vacío.
- `08-one-playlist.png`: catálogo con una colección.
- `11-detail-populated.png`: detalle con cuatro canciones.
- `18-detail-yellow-or-fallback.png`: banner amarillo real, sin placa ni borde
  rectangular, con título y contador legibles sobre el scrim continuo.

El AVD se desconectó al iniciar la reproducción antes de capturar el popup Signal
Truth y los tres seekers en la pantalla real. Esas capturas **no existen** y no
se declaran aprobadas. Tampoco se ejecutaron capturas a 320/480 dp, fuente 150/200 %
ni contraste alto en esta sesión.

### Medidas implementadas (desviaciones incluidas)

| Elemento | Implementado | Objetivo | Desviación |
|---|---|---|---|
| Título catálogo | 28/32 sp | 27–29/31–33 | dentro |
| Chip orden | 34 dp visible / 48 táctil | 34–38/48 | dentro |
| Tarjeta catálogo | 92 dp, portada 64, radio 11 | 92–104/62–68/10–12 | dentro |
| Hero | 220 dp, radio 20, portada 92/104 | 210–225/20–24/92–104 | dentro |
| Título hero | 22/26 sp | 21–23/25–28 | dentro |
| Filas | 64 dp efectivos, portada 44 | 64–72/40–44 | dentro |
| Signal Truth | máx. 84 %, ancho máx. 420 | 80–84 %, 420 aprox. | dentro |
| Barras | ~26–28 dp | 24–30 | dentro |
| Continuo | ~29 dp | 26–32 | dentro |
| Cápsula | rail 9/canal 4/thumb 12–14 | 7–10/3–5/12–14 | dentro |

### Gates

- **GO_IDENTITY:** sí para las rutas actuales; las referencias sin propietario
  siguen sin resolverse.
- **GO_SYNC:** sí para la política y las pruebas de colisión; falta una prueba
  real con dos servidores, checksum y reanudación física.
- **GO_LIBRARY_SAFETY:** sí en código y pruebas; no se encontraron borrados de
  audio desde acciones de biblioteca.
- **GO_PLAYLIST_VISUAL:** parcial. Catálogo, detalle poblado y banner amarillo
  revisados; faltan 320/480 dp, fuente 150/200 %, contraste alto y banner oscuro.
- **GO_SIGNAL_TRUTH_VISUAL:** pendiente de captura.
- **GO_WAVEFORM_AUTO:** código y pruebas de política; falta captura real y prueba
  con MP3/FLAC/SAF/inaccesible/remoto.
- **GO_AERO_CAPSULE:** geometría y política; falta captura comparativa.
- **GO_CODE:** sí. Publicado en `master@7d1c942c9f94ea4995ee680a840f84b5a8c773ae`.
- **GO_CI:** sí. La CI exacta `42929058047` pasó 8/8 jobs sobre `7d1c942`.
- **GO_VISUAL:** pendiente.
- **GO_PHYSICAL:** parcial. APK Normal debug instalado y `MainActivity` iniciada en
  `25010PN30G` (`26dfb91d`); faltan pruebas físicas de reproducción, rutas y accesibilidad.
- **GO_RELEASE:** **NO-GO**.

## KILLCRITIC QUALITY CLOSURE 03 — integridad, procedencia, Media3, waveforms y Playlists

Base auditada: `master@b0a950d5af6235e2d3728ae7093f2c0edfe1f12d`.
Integración funcional publicada en `master@bba0a51ebf1040113852117ef8e7372c515e2c7a`;
CI exacta `42942758864`: **8/8 PASS**, incluido Quality Gate.
El APK Normal debug de ese código se instaló y lanzó en el teléfono físico
`25010PN30G` (`26dfb91d`).

### SYNC-INTEGRITY-03

**Causa raíz:** `SyncIdentityPolicy.isVerified()` trataba `archivo físico + tamaño
coincidente` como identidad suficiente. `SIZE_COMPATIBLE` se confundía con
`HASH_VERIFIED`; además el preflight deduplicaba referencias por checksum antes
que por identidad lógica.

**Solución:**
- Estados separados: `REFERENCE_MATCH`, `SIZE_COMPATIBLE`, `HASH_VERIFIED`,
  `CONTENT_MISMATCH`, `POSSIBLE_DUPLICATE`, `DIFFERENT_SOURCE`, `UNKNOWN`.
- `SyncIdentityMatch.canSkip` es la única autorización de `SKIP_ALREADY_EXISTS`.
  Solo `HASH_VERIFIED` o una copia gestionada persistente (`REFERENCE_MATCH`)
  pueden omitir. `SIZE_COMPATIBLE` siempre descarga.
- Los checksums solo se comparan cuando ambos son SHA-256 compatibles; un
  checksum con semántica incompatible no se presenta como verificado.
- Deduplicación por `trackId` lógico, nunca por checksum: dos referencias de
  álbum/playlist al mismo audio se conservan.
- Room 9 → 10 añade `verifiedSize`, `verifiedSha256`, `verifiedAt`,
  `verifiedAlgorithm` a `cached_tracks`; la migración es no destructiva.
- `SyncedTrackRepository.getDownloadedTrackEvidence()` comprueba archivo,
  tamaño, huella persistente y antigüedad; hashea una sola vez en IO las copias
  heredadas y persiste el resultado.
- `SyncWorker` guarda el SHA-256 verificado tras la transferencia y limpia la
  verificación si solo registra una ruta.

**Pruebas:** mismo tamaño con contenido distinto, checksum válido/incorrecto/
ausente/incompatible, archivo desaparecido, copia gestionada reutilizable,
reanudación, dos servidores con el mismo `trackId` y dos referencias al mismo
contenido.

### SAF-PROVENANCE-03

**Causa raíz:** `SafMediaRepository` guardaba `downloaded = true` para documentos
SAF y `CachedTrack.toTrack()` evaluaba `downloaded` antes que la raíz local.

**Solución:**
- SAF se indexa con `downloaded = false`; no es una copia gestionada por Sync.
- El mapper prioriza `isLocalLibrarySource(sourceId)` sobre `downloaded`, por lo
  que las filas históricas SAF tampoco se reclasifican como `SYNCED`.
- Se conservan las tres dimensiones: propietario (`sourceId`), bytes
  (`TrackSource`) y ruta (`filepath`). Un SAF sigue siendo local aunque su
  archivo esté disponible.
- `Track.albumArtist` y `CachedTrack.albumArtist` preservan la autoría de álbum.

**Pruebas:** SAF con `downloaded=true` histórico, MediaStore local, sincronizada
física, remota no descargada y evidencia SAF que no se hashea como copia Sync.

### META-SESSION-03 / META-ART-04

**Causa raíz:** cada ruta de reproducción construía `MediaMetadata` con título y
artista; la carátula nunca llegaba a la sesión. Además, un `coverId` numérico se
convertía siempre en URI de álbum de MediaStore, incluso para SAF o servidores.

**Solución:**
- Nueva autoridad `PlaybackMediaMetadata` usada por `PlayerController`,
  `AudioController`, `LibraryProvider` y las rutas de cola.
- La URI numérica de MediaStore solo se construye para la biblioteca local del
  dispositivo; SAF y bibliotecas remotas no pueden tomar prestada una portada
  local.
- `EmbeddedArtwork` extrae la imagen incrustada fuera del hilo principal, la
  reduce a 512 px y la recomprime como JPEG ≤ 256 KB para cruzar Binder con
  seguridad. Caché acotada por identidad de archivo; sin red.
- `PlayerController` enriquece la ventana actual±1 y refresca la carátula al
  cambiar de pista; una portada tardía se descarta si el `mediaId` ya cambió.
- `MediaMetadata` incluye título, artista, álbum, album artist, track number,
  año, duración, tipo de medio, artwork y extras de identidad/propietario.
- `LocalMediaRepository` agrupa por `ALBUM_ID` de MediaStore o por
  álbum + album artist; no parte recopilatorios por el artista invitado de cada
  canción. La tabla de álbumes aporta el crédito de álbum.

**Pruebas:** URI de portada local, SAF/sincronizada sin portada prestada,
`artworkData` con prioridad, agrupación de recopilación por `ALBUM_ID`,
álbumes homónimos con IDs distintos y SAF con `ALBUMARTIST`.

### NP-WAVE-DYNAMICS-03

**Causa raíz:** la envolvente real se dibujaba con amplitudes absolutas. En
masterizaciones comprimidas la variación era mínima y Barras/Continuo compartían
una masa visual casi plana.

**Solución:**
- `waveformRenderLevels()` aplica una ventana robusta p10–p90 y una curva de
  contraste suave solo de representación; el silencio sigue siendo silencio y no
  se inventan picos.
- Barras: 45–60 barras según ancho, 2.5–3.5 dp de ancho, 38–42 dp de altura,
  extremos redondeados, progreso por color de segmento.
- Continuo: 80–180 puntos, silueta cerrada con suavizado cuadrático que conserva
  transitorios, 40 dp de altura, máscara de progreso y playhead fino.
- Ambos comparten una única decodificación PCM; cambiar Barras ↔ Continuo no
  vuelve a decodificar. Ahora Playing 44 dp de canvas y mantiene el objetivo
  táctil de 48 dp.
- Aero Capsule conserva `LIQUID_GLASS`, rail 9 dp, canal 4 dp, thumb 12/14 dp y
  la única autoridad de gestos en `GradientProgressBar`.

**Pruebas:** masterización comprimida, silencio, transitorio aislado, geometrías
distintas, valores no finitos y política de fallback.

### PL-EDITORIAL-03 / PL-DETAIL-03

- El catálogo ya no depende de mantener pulsado Play. Reproducir y **Más
  opciones** son acciones separadas, visibles y con objetivos de 48 dp. El menú
  conserva Renombrar, Fijar/Desfijar, Gestionar canciones y Eliminar.
- Se mantienen las medidas de CLOSURE 02: título 28/32 sp, tarjetas de 92 dp,
  portada 64 dp, hero de 220 dp, portada 92/104 dp, título 22/26 sp, filas
  compactas de 44 dp y CTAs Reproducir/Mezclar con jerarquía 1f/0.55f.
- La pulsación larga sobre Play ya no abre administración.

### UI5-LIB-HEADER-03

- Eliminado el botón «+» y `library_add_playlist_button` de Biblioteca.
- Eliminados el estado, el diálogo y los imports de creación de playlist.
- Canciones, Álbumes, Artistas, filtros, menús de canción y «Añadir a playlist»
  siguen funcionando. El texto vacío ahora remite a la sección Playlists.

### ST-VISUAL-03

- Se conserva el popup Aero de CLOSURE 02: `Dialog` + `AeroFloatingSurface`,
  radio 26 dp, ancho máximo 420 dp, altura natural con máximo 84 %, scroll
  interno, una etapa expandida por `stableId` y conectores sólidos solo con
  causalidad real.
- No se modificó la evidencia R18/R19 ni se declaró verificación física del DAC.
- **No hay captura nueva de Signal Truth en CLOSURE 03.**

### Medidas implementadas

| Elemento | Implementado | Objetivo | Estado |
|---|---|---|---|
| Sync skip | solo hash o copia gestionada persistente | sin evidencia insuficiente | dentro |
| Room | versión 10, migración aditiva | no destructiva | dentro |
| Barras | 45–60, 2.5–3.5 dp, 38–42 dp | 45–60, 2.5–3.5, 38–44 | dentro |
| Continuo | 40 dp, 80–180 puntos | 38–44 dp | dentro |
| Canvas seeker | 44 dp / táctil 48 dp | ≥48 táctil | dentro |
| Cápsula | rail 9, canal 4, thumb 12/14 | 7–10/3–5/12–14 | dentro |
| Playlist card | 92 dp, portada 64, menú 48 | 92–104/62–68/48 | dentro |
| Library header | sin «+» | sin acceso duplicado | dentro |
| Media art | URI local o embedded ≤256 KB | identificable y seguro | dentro |

### Validación ejecutada

| Comprobación | Resultado |
|---|---|
| Unit `:core`, `:data`, `:player`, `:app` Normal/F-Droid, `:michi-link-client` | **PASS** |
| `SyncPreflightServiceTest`, `SyncedTrackRepositoryTest`, `Migration9To10Test`, `CachedTrackMapperTest` | **PASS** |
| `LocalMediaRepositoryTest`, `PlaybackMediaMetadataTest`, `MichiMediaLibrarySessionCallbackTest` | **PASS** |
| `WaveformVisualPolicyTest`, `PlaylistNoopRegressionTest`, `PlaylistDetailScreenTest` | **PASS** |
| Lint | **PASS** |
| Normal/F-Droid debug + release | **PASS** |
| Instrumentación AVD API 36 | **93 tests, 0 fallos, 8 skipped** |

### Evidencia visual real

En `/tmp/opencode/closure03/`:
- `01-library-header.png`: Biblioteca sin «+».
- `02-playlists-empty.png`: estado vacío editorial.
- `03-playlists-one.png`: una colección, Play y Más opciones separados.
- `04-playlist-menu.png`: menú contextual visible.
- `05-detail-empty.png`, `07-detail-populated.png`: detalle y filas compactas.
- `09-detail-yellow.png`: banner amarillo sin placa ni rectángulo; título y
  contador legibles sobre el scrim continuo.

No existen capturas de Signal Truth, Waveform Barras, Waveform Continuo ni Aero
Capsule: el AVD se cerró dos veces al iniciar la reproducción. No se declaran
aprobados.

### Gates

- **GO_SYNC_INTEGRITY:** sí en política, migración y pruebas; falta una prueba
  real con dos servidores y checksums de extremo a extremo.
- **GO_TRACK_PROVENANCE:** sí en código y pruebas; falta validación física SAF.
- **GO_MEDIA3_ARTWORK:** código y pruebas de resolución; falta captura en
  Android/HyperOS.
- **GO_ANDROID_MEDIA_CONTROLS:** pendiente de dispositivo.
- **GO_METADATA:** sí para agrupación/álbum artist; falta matriz real de tags
  MP3/FLAC/M4A/OGG/WAV.
- **GO_ARTWORK_IDENTITY:** parcial; falta comprobar portadas embebidas reales.
- **GO_WAVEFORM_VISUAL:** código y pruebas; sin captura comparativa.
- **GO_WAVEFORM_AUTO:** código y pruebas; sin captura real MP3/FLAC/SAF.
- **GO_PLAYLIST_CATALOG_VISUAL:** parcial; una colección y menú capturados,
  falta muchas colecciones y matriz de anchos.
- **GO_PLAYLIST_DETAIL:** parcial; banner amarillo y filas capturados, faltan
  banner oscuro, textura, título largo y fuente 200 %.
- **GO_PLAYLIST_REORDER:** pendiente; no se modificó el drag-and-drop.
- **GO_LIBRARY_HEADER:** sí con captura y pruebas de compilación.
- **GO_SIGNAL_TRUTH_VISUAL:** pendiente de captura.
- **GO_CODE:** sí. Publicado en `master@bba0a51ebf1040113852117ef8e7372c515e2c7a`.
- **GO_CI:** sí. La CI exacta `42942758864` pasó 8/8 jobs sobre `bba0a51`.
- **GO_VISUAL:** parcial; catálogo, detalle y banner amarillo capturados, faltan
  Signal Truth, seekers y la matriz completa de anchos/fuentes.
- **GO_PHYSICAL:** parcial. APK Normal debug instalado y `MainActivity` iniciada
  en `25010PN30G` (`26dfb91d`); faltan pruebas físicas de reproducción, rutas,
  portada en controles Android/HyperOS y accesibilidad.
- **GO_RELEASE:** **NO-GO**.

## Michi Mobile UI5 / Signal Truth R20.1 — corrección acumulativa

Base: `master@9dc783d3bebacb6bfeb1f59cb29d19866dbef768`.
Cambios en working tree, **sin commit ni push**. El contrato ejecutado es
`MICHI_MOBILE_UI5_OPENCODE_MEGA_PROMPT.md`; los ejemplos del documento se
adaptaron a las firmas reales, no se copiaron como pseudocódigo.

### P0 Sync — una sola autoridad de omisión

**Causa raíz:** el preflight y `SyncWorker` tomaban decisiones distintas. El
worker omitía con `exists + size` cuando el manifiesto no publicaba checksum;
`SyncIdentityPolicy` además permitía `REFERENCE_MATCH` con un hash local
autogenerado. Ninguna de las dos pruebas demuestra equivalencia remota.

**Corrección:**
- `SyncIdentityPolicy` solo marca `canSkip=true` para `HASH_VERIFIED`: mismo
  propietario y `trackId`, archivo físico completo y SHA-256 compatible igual al
  checksum remoto.
- `REFERENCE_MATCH` conserva la referencia lógica pero ya no omite. Un hash
  local sin hash remoto se revalida/descarga.
- `SyncWorker` consume `getDownloadedTrackEvidence(deviceId)` y clasifica cada
  pista con la misma política; eliminó el filtro paralelo por tamaño.
- Se mantienen checkpoints, reanudación y la verificación física existente.

**Pruebas:** hash remoto correcto/incorrecto/ausente, hash local sin hash
remoto, mismo tamaño sin verificación, fuente distinta, archivo desaparecido y
dos referencias lógicas al mismo contenido. Todas PASS.

### P1 Media3 artwork — identidad de cola

**Causa raíz:** `enrichArtworkAt` solo comparaba `mediaId`/songId; dos
bibliotecas pueden compartir `trackId` y la cola puede reordenarse durante la
extracción.

**Corrección:** nuevo `ArtworkOwner(sourceId, trackId, canonicalUri,
queueGeneration, position)` y `MediaItem.belongsTo(owner)`. La generación se
incrementa al reemplazar, insertar, mover, eliminar o limpiar la cola. La imagen
tardía se descarta si cambió la generación, el propietario, la URI o el
`mediaId`. `PlaybackMediaMetadata` sigue siendo la autoridad de metadatos.

**Pruebas:** mismo `trackId` en otra biblioteca, URI distinta, generación stale,
extras ausentes fail-closed y coincidencia exacta. PASS.

### P1 Playlists — drag y autoscroll

- Se mantiene `detectDragGesturesAfterLongPress`, la identidad por `track.id` y
  el contrato DAO de IDs visibles únicos (`expectedVisibleIds`).
- La fila arrastrada gana escala 1.018, sombra de 12 dp y opacidad 0.97.
- El destino usa borde de 1.5 dp rosa; el resto no se colorea.
- Un único `LaunchedEffect` controla el autoscroll por zona de borde y se
  cancela al soltar, cancelar o salir de composición; ya no hay un job por
  evento.
- Si aparecen IDs visibles duplicados, el drag se desactiva y se conservan las
  acciones accesibles Mover arriba/abajo.
- El drop sigue persistiendo una sola vez y el conflicto DAO conserva el orden
  anterior.

### P1 Now Playing — hero y tipografía

- `aeroArtworkSideDp` usa 94 % del ancho disponible normal y 91 % compacto,
  con clamp a la altura libre y fail-closed para valores no finitos.
- Título normal 25/30 sp, compacto 22/27 sp, Inter Semibold, máximo 2 líneas.
- Artista normal 18/24 sp, compacto 16/22 sp, una línea con elipsis.
- Favorito conserva 48 dp; no se añadió volumen ni otro control.
- `AeroDynamicMeshBackground` se mantiene decorativo, 125 ms por fase y 850 ms
  de transición; no se alteró su contrato de lifecycle.

### P1 Waveform — cache anticipada

- `LocalWaveformDecoder.load` resuelve URI/fingerprint y consulta la LRU antes
  de adquirir el mutex de decodificación.
- Dentro del mutex revalida el fingerprint, vuelve a consultar la caché y solo
  decodifica en miss; tras decodificar exige un fingerprint estable para cachear.
- Sin fingerprint (algunos SAF) se lee una vez y no se cachea una identidad no
  revalidable.
- Barras y Continuo comparten la misma decodificación; no se añadió preview
  parcial porque aún no existe una representación temporal verificable.

### P1 Aero Capsule

- Se conserva la clave `LIQUID_GLASS` y la etiqueta pública “Aero Capsule”.
- Rail 16 dp, canal 9 dp, inset 3 dp, radio exterior `height/2`, contorno 0.8 dp,
  highlight 0.65 dp y relleno aurora.
- Thumb 15 dp normal / 16 dp al arrastrar; canvas 44 dp y objetivo táctil 48 dp.
- `GradientProgressBar` sigue siendo la única autoridad de gestos y semántica.
- Métricas centralizadas en `AeroCapsuleMetrics` y testeadas.

### P1 Signal Truth R20.1

- Eliminado el botón visible «Cerrar».
- Scrim hermano detrás de `AeroFloatingSurface` con cierre por toque exterior;
  Back y la acción semántica “Cerrar Signal Truth” también cierran.
- Hero compacto con icono, pista, salida humana, certeza de una línea y una
  única CTA “Cambiar salida”.
- El nombre crudo del dispositivo ya no se muestra como salida humana:
  `humanOutputLabel()` mapea el tipo Android observado; `sdk_gphone64_x86_64`
  se presenta como “Altavoz del teléfono” solo si el tipo observado es speaker.
- Se conserva la cadena causal, la expansión única, la evidencia
  Declarado/Configurado/Observado/Sin verificar y los ajustes autorizados.
- R18/R19 y la autoridad de audio no se modificaron.

### Evidencia visual real

En `/tmp/opencode/r201/`:
- `02-now-playing-hero.png`: hero 94 %, título 25 sp, artista 18 sp.
- `07-capsule.png`: Aero Capsule 16/9/3 dp, thumb 15 dp, progreso real.
- `10-waveform-bars.png`: 45–60 barras PCM reales, progreso por color.
- `11-waveform-continuous.png`: silueta cerrada, playhead y misma pista.
- `08-signal-truth-final.png` y `09-signal-truth-fixed.png`: popup sin «Cerrar»,
  hero compacto, una CTA y fallback humano seguro.

La matriz 320/360/480 dp, fontScale 1.5/2.0, alto contraste, TalkBack y
HyperOS/lockscreen no se ejecutó en esta sesión.

### Validación ejecutada

| Comprobación | Resultado |
|---|---|
| Unit `:core`, `:data`, `:player`, `:app` Normal/F-Droid, `:michi-link-client` | **PASS** |
| Tests focados de sync, artwork owner, hero, cápsula, Signal Truth, rutas | **PASS** |
| Lint | **PASS** |
| Normal/F-Droid debug + release | **PASS** |
| Instrumentación Normal API 36 | **93 tests, 0 fallos** (runner mostró 8–9 skipped) |

### Gates R20.1

- **G0_SOURCE:** PASS. Base `9dc783d`, sin parches antiguos ni cambios ajenos.
- **G1_SYNC:** PARCIAL. Política y worker unificados; falta servidor real doble.
- **G2_ARTWORK:** PARCIAL. Identidad de cola testeada; falta HyperOS/lockscreen.
- **G3_PLAYLISTS:** PARCIAL. Feedback/autoscroll implementados; falta captura de
  drag en dispositivo.
- **G4_NOWPLAYING:** PARCIAL. Hero y tipografía capturados a 393 dp; falta matriz.
- **G5_WAVEFORM:** PARCIAL. Barras/Continuo capturados; faltan tiempos P50/P95,
  SAF y FLAC reales.
- **G6_CAPSULE:** PASS visual a 393 dp; faltan snapshots 0/1/25/50/99/100 %.
- **G7_SIGNAL_TRUTH:** PARCIAL. Captura y etiquetas seguras; falta prueba
  instrumentada de toque exterior real y ramas USB/Stream/remoto.
- **G8_ANDROID:** PASS. Unit, lint, builds e instrumentación ejecutados.
- **G9_DEVICE:** NO-GO. Sin teléfono físico conectado.
- **G10_RELEASE:** **NO-GO**.
- **GO_CODE:** sí en working tree.
- **GO_CI:** no aplica; no hay commit/push.
- **GO_VISUAL:** parcial.
- **GO_PHYSICAL:** no ejecutado en esta sesión.
- **GO_RELEASE:** **NO-GO**.

### Porcentajes honestos

- Código implementado: ~90 % del contrato (los ocho bloques tienen código real;
  Signal Truth conserva la cadena detallada en lugar de una macro-vista totalmente
  nueva).
- Integración local: ~80 % (builds, lint, unit e instrumentación PASS).
- QA visual: ~60 % (capturas reales de hero, cápsula, barras, continuo y Signal
  Truth; falta matriz de anchos/fuentes y estados).
- QA física: 0 % en esta sesión (sin teléfono conectado).
- Release: **NO-GO**.
