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
