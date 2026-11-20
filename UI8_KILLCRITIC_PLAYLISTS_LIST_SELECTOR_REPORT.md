# UI8-KILLCRITIC — Playlists: Lista con tarjetas completas y selector compacto

Fecha: 2026-11-20. Repositorio: `pitydah/michi-music-mobile`.
Rama de corrección: `fix/ui8-playlists-list-and-selector` (base `master` `13469381fbd05756bcc7071b17c98d7cf25e8ac0`, que ya contiene el PR #26).

Este documento cubre **solo las dos correcciones pendientes** del brief posterior al merge del PR #26. No reimplementa nada de UI8 ni del PR #26: la geometría compartida, el scrim radial, el piso de 198 dp, el mazo con `matchParentSize()`, los gestos, el movimiento reducido y los haptics se conservan tal como quedaron fusionados.

---

## §A — Integración

| Campo | Valor |
|---|---|
| HEAD de `master` al iniciar | `13469381fbd05756bcc7071b17c98d7cf25e8ac0` (merge del PR #26) |
| Rama de corrección | `fix/ui8-playlists-list-and-selector` (única rama, ambos defectos) |
| Rama anterior | `fix/ui8-playlists-card-parity` **no** se reutilizó (ya fusionada) |
| PR | Uno solo, contra `master`. Sin merge automático. |
| PR #27 | Documental (`docs/killcritic-merge-closure`); no se le mezcló código ni se editó su informe |

### Archivos de producción modificados

| Archivo | Cambio |
|---|---|
| `ui/components/AeroSegmentedChoice.kt` | Nuevo parámetro `fillWidth: Boolean = true` en `AeroSegmentRail`. Con el valor por defecto el modificador sigue siendo `fillMaxWidth()`, así que Biblioteca, Buscar, Ajustes y `Ui5PolishComposeTest` no cambian. |
| `ui/screens/PlaylistCatalogComponents.kt` | Se eliminó `PlaylistCatalogRow` (64 líneas) y sus cuatro imports exclusivos: era la representación compacta que el brief prohíbe, y quedó sin consumidores. El archivo pasó de 313 a 245 líneas. `PlaylistCatalogSpotlight`, `PlaylistCover`, `PlaylistCoverMosaic`, `captionShadow` y el scrim radial quedan intactos. |
| `ui/screens/PlaylistScreen.kt` | Encabezado `FlowRow`: título a la izquierda, grupo `PlaylistModeSelector` + botón `+` a la derecha. El selector de ancho completo desapareció de su fila propia. El modo Lista pasó de «spotlight + Otros playlists + filas» a un único `LazyColumn` de `PlaylistCatalogSpotlight`. |

### Archivos de prueba

| Archivo | Aporte |
|---|---|
| `app/src/test/.../UI8PlaylistListCardsTest.kt` (nuevo) | 11 casos sobre `PlaylistScreen` real (L01–L10 del brief) |
| `app/src/test/.../UI8PlaylistModeSelectorTest.kt` | 6 casos; 3 nuevos de geometría por `boundsInRoot` |
| `app/src/test/.../UI8PlaylistGeometryParityTest.kt` | 7 casos; 2 nuevos (posición y playlist fijada) |
| `app/src/androidTest/.../UI8VisualMatrixCaptureTest.kt` | Contrato del caption reescrito: mide el rectángulo real del título, exige AA y exige desvanecido del scrim |
| `app/src/androidTest/.../UI8PlaylistScreenFullCaptureTest.kt` (nuevo) | Captura la **pantalla completa real** de Playlists en la configuración impuesta por el dispositivo |
| `app/src/androidTest/.../ArtworkProbeProvider.java` (nuevo) | `ContentProvider` que entrega PNG reales por pipe al importador de producción (`content://`) |
| `app/src/androidTest/AndroidManifest.xml` | Declara el provider con `android:exported="true"` |

---

## §B — Modo Lista

### Contrato implementado

`LazyColumn` con `items(items = orderedPlaylists, key = { it.id }, contentType = { "playlist_spotlight" })`, un `PlaylistCatalogSpotlight` por playlist y `Modifier.fillMaxWidth()`. No hay encabezado «Otras playlists» y no existe ninguna fila compacta en el catálogo.

### Resultados medidos (Robolectric, `PlaylistScreen` completo, 393 dp)

| Escenario | Resultado |
|---|---|
| 1 playlist | 1 tarjeta completa, alto 198.0 dp |
| 2 playlists | 2 tarjetas completas, alturas `198.0, 198.0` |
| 20 playlists | 20 tarjetas con identidad estable; `Playlist %02d` ordena igual por nombre y por número; la última (`playlist_item_p-20`) es alcanzable con desplazamiento y mide ≥ 198 dp |
| 100 playlists | composición diferida: ≤ 8 tarjetas compuestas a la vez |
| Playlist fijada | va primero, mismo ancho (±1 dp), alto 207 dp frente a 198 dp por el rótulo «FIJADA» (altura dirigida por contenido, no por posición) |
| Título largo | tarjeta intacta, sin recorte de la caja |
| fontScale 2.0 | navegación y controles conservados |

Etiquetas de comportamiento verificadas: `theOtherPlaylistsHeadingIsGone`, `noEntryIsDrawnAsACompactRow`, `theCatalogDoesNotRenderACompactRowTag`.

### Paridad con el mazo

`everyCardKeepsTheHeroGeometryWhateverItsPosition` recorre las tarjetas 2 y 3 con `performScrollToIndex`, acumula sus medidas y las compara contra la frontal del mazo: **ancho y alto iguales** (288.0 × 198.0 dp en el entorno Robolectric de 288 dp útiles). Ninguna playlist recibe tratamiento geométrico distinto por su posición.

### Evidencia visual

`/tmp/opencode/ui8-playlist-matrix-final/` — 30 PNG de pantalla completa (15 configuraciones × 2 modos). En `600dp-fs1.0/lista.png` se ven **tres tarjetas completas** apiladas con banner real (azul, ámbar y la tercera sin arte), encabezado completo, selector compacto junto al `+` y el dock real.

---

## §C — Selector

### Implementación

`AeroSegmentRail(compact = true, fillWidth = false, modifier = Modifier.width(IntrinsicSize.Max))` con dos `AeroSegmentedChoice` (`weight(1f)` cada uno). `IntrinsicSize.Max` es lo que realmente acota el ancho: las etiquetas deciden el tamaño, los dos segmentos se reparten por igual y ninguno es aplastado por un hermano. No se usa `aeroSegmentStacking` (el apilamiento vertical genérico) porque generaba un control demasiado alto para dos etiquetas.

### Mediciones por configuración (device-driven, `wm size` + `font_scale`)

| fontScale | Ancho del selector (lista) | Ancho del selector (tarjetas) | Separación al `+` |
|---|---|---|---|
| 1.0 | 133.33 dp | 133.33 dp | 9.90 dp |
| 1.5 | 179.81 dp | 182.10 dp | 9.90 dp |
| 2.0 | 225.52 dp | 228.57 dp | 9.90 dp |

- El selector está **a la izquierda** del botón «Nueva Playlist» en las 30 capturas (`selector.right ≤ create.left`), sin solapamiento y con separación positiva constante de 9.90 dp.
- El grupo `selector + +` cabe en una sola fila dentro del 98 % del ancho en las 15 configuraciones, incluida 320 dp × 2.0 (`225.52 + 9.90 + 48 = 283.4 dp` sobre 320 dp); por eso no se activa ningún apilamiento artificial.
- El selector **no** ocupa una fila de ancho completo: es un control de ancho intrínseco dentro del encabezado. A 320 dp/2.0 el grupo baja a su propia línea bajo el título — la adaptación permitida — pero nunca se separa del `+`.
- Variación observada y declarada: el ancho cambia ~2–3 dp entre modos porque la etiqueta seleccionada altera el ancho intrínseco. Es cosmético y no rompe el contrato de fila.

### Accesibilidad

`playlist_mode_classic` y `playlist_mode_cards` se conservan, selección mutuamente excluyente verificada en ambos sentidos, `selectable` anunciado por TalkBack, altura táctil ≥ 48 dp, botón `+` con `contentDescription = "Nueva Playlist"`, y alternar el modo no crea playlists ni viceversa.

---

## §D — Pruebas

### Comandos reales y resultados

| Comando | Resultado |
|---|---|
| `gradle_run.py run … :app:compileNormalDebugKotlin :app:compileNormalDebugUnitTestKotlin :app:compileNormalDebugAndroidTestKotlin` (run 0021) | exit 0 |
| `:app:testNormalDebugUnitTest` completo (run 0022, antes de los tests nuevos) | exit 0, 77 s |
| `:app:testNormalDebugUnitTest` filtrado a las 3 clases nuevas (run 0027) | exit 0 — 11 + 6 + 7 tests, 0 fallos |
| `:app:testNormalDebugUnitTest` completo (run 0028) | exit 0, 77.6 s |
| `:app:testNormalDebugUnitTest` completo con el estado final (run 0046) | exit 0, 79.8 s |
| `:app:assembleNormalDebug :app:assembleNormalDebugAndroidTest` (runs 0030 y 0045) | exit 0 |
| `am instrument -w -e class …UI8VisualMatrixCaptureTest` | `OK (7 tests)`, 21.7 s |
| `bash /tmp/opencode/ui8-playlist-matrix.sh` | `MATRIX_FAIL=0`, 15/15 configuraciones, 30 capturas |

### Matriz visual de pantalla completa

15 configuraciones (320/360/393/430/600 dp × 1.0/1.5/2.0), 2 modos cada una, píxeles reales por ancho (840/945/1032/1129/1575 px de ancho), `cards` = 0 en Lista y 1 en Tarjetas en las 30 filas del manifiesto. Incluye barra de estado, encabezado completo y dock real.

Contraste del caption re-medido tras la restauración del scrim del PR #26: 18/18 configuraciones ≥ AA 4.5:1, mínimo 5.09:1, máximo 6.71:1.

### Rendimiento

- `LazyColumn` con `key` estable y `contentType` único: composición diferida confirmada (100 playlists → ≤ 8 tarjetas compuestas).
- No se agregaron escaneos, polling, corrutinas residentes, `SubcomposeLayout` ni lecturas de arrastre en composición.
- La transformación de Lista reutiliza el mismo componente que el mazo, así que no hay una segunda ruta de decodificación de banner/cover.
- **No se certifica** fluidez con banners reales en hardware: la matriz se ejecutó en el emulador `emulator-5554` con SwiftShader. No se usan las métricas anteriores del mazo sintético como evidencia de este catálogo.

---

## §E — Avance porcentual (separado, sin promedios)

| Área | Avance | Evidencia |
|---|---|---|
| P1-01 — Lista con tarjetas completas | **100 %** | 11 tests verdes; 30 capturas; «Otras playlists» eliminado; `PlaylistCatalogRow` eliminada |
| P1-02 — Selector compacto junto al `+` | **100 %** | 6 tests; geometría medida en 30 filas; gap 9.90 dp; sin fila de ancho completo |
| Conservación geométrica (PR #26) | **100 %** | paridad 0 dp Lista/Tarjetas; piso 198 dp; `matchParentSize()` intacto |
| Gestos y funciones del mazo | **100 %** | `UI8PlaylistDeckComposeTest` (5) y `deckOffersBothAccessibleDirectionsWithoutSwipe` verdes; rotación circular verificada |
| Accesibilidad | **80 %** | semántica, exclusividad, ≥48 dp y orden verificados por test; **falta** recorrido manual con TalkBack |
| Compilación y tests (local) | **100 %** | suite JVM completa + 7/7 + 15/15 instrumentados |
| Validación visual | **90 %** | 15/15 configuraciones capturadas e inspeccionadas; falta aceptación del usuario sobre el aspecto del scrim |
| Integración en `master` | **0 %** | PR pendiente de CI y de autorización |

---

## Limitaciones declaradas

1. **El scrim radial del PR #26 sigue leyéndose como matte oscuro** sobre banners muy saturados, porque cubre el ancho completo de la columna del caption. Se probaron dos alternativas sin borde recto y **ambas fallaron el contraste AA** medido en las letras: scrim elíptico (1.48:1 a 320 dp/1.0) y gradiente inferior anclado al borde de la tarjeta (2.94:1 a 320 dp/2.0, donde el caption ocupa el 33 %–74 % de la tarjeta). Se conservó el scrim fusionado; cambiarlo requiere una decisión de diseño del usuario, no un ajuste unilateral.
2. **Dock a 200 %**: en la captura 320 dp/2.0 el dock parte palabras («Bibliote ca», «Playlist s»). Es un defecto **preexistente** del dock, fuera del alcance del brief; solo se reporta.
3. **El ancho del selector varía ~2–3 dp** según qué etiqueta esté seleccionada (ancho intrínseco del texto). Cosmético, declarado.
4. **Arte de prueba**: los banners y covers de la matriz son PNG generados (gradientes azul y ámbar) entregados por `content://` a través del importador de producción. Prueban el camino real de importación, decodificación y dibujo, pero no sustituyen arte fotográfico.
5. **Navegación manual en teléfono físico**: `adb shell input` está bloqueado por MIUI en el teléfono `26dfb91d` (`INJECT_EVENTS`), y el manifest no declara deep links. La pantalla real se ejerció con tests instrumentados, no con una inspección manual del dispositivo.
