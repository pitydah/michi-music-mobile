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
