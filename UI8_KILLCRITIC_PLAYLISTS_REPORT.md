# UI8-KILLCRITIC — Refinamiento de Playlists post-merge

Fecha: 2026-11-20. Repo: `pitydah/michi-music-mobile`. Base: `master` `341c706b`.
Rama de corrección: `fix/ui8-playlists-card-parity`. Estado: **commiteada localmente, sin push, sin PR** (el brief lo exige).

---

## §A — P0: auditoría medida, no supuesta

### Los dos contratos divergentes que producían el salto entre modos

| Dimensión | Lista (clásico) | Tarjetas (deck) | Diferencia |
|---|---|---|---|
| Ancho de la tarjeta | pantalla − 32 dp (`screenHorizontal = 16.dp` × 2) | pantalla − 32 dp − **24 dp** (`.padding(horizontal = 12.dp)` sólo en el mazo) | **24 dp más angosta** |
| Alto de la tarjeta | alto intrínseco del contenido, piso `heightIn(min = 198.dp)` | **fijo** `playlistDeckHeightFor(screenHeightDp)` = `(screenHeight*0.40).coerceIn(210.dp, 278.dp)` | 210–278 dp, no coincide |

Además, dentro de `PlaylistCatalogSpotlight` había **dos contratos de medición** elegidos por un booleano `deck`:

```kotlin
// PlaylistCatalogComponents.kt, antes
modifier = (if (deck) Modifier.fillMaxSize()
            else Modifier.fillMaxWidth().heightIn(min = 198.dp)).padding(12.dp)
```

Es decir: el alto de la misma tarjeta dependía de quién la mostraba. Eso es lo que el usuario veía como "la tarjeta cambia de tamaño al cambiar de modo".

### Consecuencia técnica del alto fijo
`PlaylistSwipingDeck` derivaba su geometría de gestos de `BoxWithConstraints`:

```kotlin
val widthPx = maxWidth.toPx().coerceAtLeast(1f)
val heightPx = maxHeight.toPx().coerceAtLeast(1f)
```

Quitar la altura fija con ese diseño habría roto el gesto: dentro de un `verticalScroll` el `maxHeight` entrante es `Constraints.Infinity` y `heightPx` sería infinito. Por eso la corrección no podía ser "sólo borrar el `.height(...)`".

---

## §B — P1: una sola geometría para la tarjeta

### Decisión
La **tarjeta frontal define el tamaño del mazo**. Se layoutea en el flujo normal del `Box`; las traseras se dibujan con `matchParentSize()` detrás, sin imponer tamaño. Así el mazo mide exactamente el hero, con el mismo contrato que Lista, sin `SubcomposeLayout` y sin medir en cada frame.

### Cambios

1. **`PlaylistCatalogComponents.kt`** — se eliminó el parámetro `deck: Boolean = false`; un solo contrato:
   ```kotlin
   modifier = Modifier.fillMaxWidth().heightIn(min = 198.dp).padding(12.dp)
   ```
2. **`PlaylistSwipingDeck.kt`** — `BoxWithConstraints(modifier)` → `Box(modifier.onSizeChanged { deckSize = it })`, con el tamaño leído del layout medido:
   ```kotlin
   val widthPx = deckSize.width.toFloat().coerceAtLeast(1f)
   val heightPx = deckSize.height.toFloat().coerceAtLeast(1f)
   ```
   y la capa de cada tarjeta:
   ```kotlin
   Modifier.then(if (isFront) Modifier.fillMaxWidth() else Modifier.matchParentSize())
   ```
3. **`PlaylistScreen.kt`** — el mazo ya no impone alto ni inset propio:
   ```kotlin
   modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp)   // 26 dp = espacio de dibujo para las traseras
   ```
4. **`UI8VisualSpec.kt`** — se borraron `playlistDeckHeight`, `playlistDeckMinHeight` y `playlistDeckHeightFor()`: el mazo no tiene política de alto propia.

### Medición (JVM, pantalla real de Playlists, `UI8PlaylistGeometryParityTest`)

```
GEOMETRY-PARITY device=393dp fontScale=1.0 lista=288.0x198.0dp tarjetas=288.0x198.0dp
GEOMETRY-PARITY device=393dp fontScale=2.0 lista=288.0x198.0dp tarjetas=288.0x198.0dp
```

**Diferencia de ancho y alto: 0.0 dp** en ambos modos, también con escala 2.0. (288 dp = 320 dp del root de Robolectric − 32 dp; es la geometría compartida, no un número del test.) El caso 1.0x/2.0x y el de título largo («Las mejores baladas de los ochenta y noventa para viajar», 128 canciones) dan el mismo resultado, y 10 toggles consecutivos no la mueven.

### Verificación visual
`/tmp/opencode/ui8-playlists-matrix6/`: `listavsdeck_320dp-fs1.0.png`, `…393dp-fs1.5.png`, `…600dp-fs2.0.png` superponen `spotlight_no_art` (Lista) y `deck_two` (Tarjetas) — misma caja, mismo cover, mismo caption.

---

## §C — P2: la placa negra, sustituida por legibilidad medida

### Qué había
```kotlin
.clip(RoundedCornerShape(UI8VisualSpec.captionCorner))
.background(Color.Black.copy(alpha = UI8VisualSpec.captionMatteAlpha))   // 0.72
```
Una placa negra al 72 % con borde recto sobre el banner: se leía como una segunda tarjeta dentro del hero y tapaba el arte justo donde el usuario lo quiere ver.

### Qué hay
Un **scrim radial** que se desvanece en todas las direcciones (sin ningún borde recto) más sombra tipográfica:

```kotlin
.drawBehind {
    drawRect(brush = Brush.radialGradient(
        colorStops = arrayOf(
            0f   to Color.Black.copy(alpha = UI8VisualSpec.captionScrimAlpha),
            0.6f to Color.Black.copy(alpha = UI8VisualSpec.captionScrimAlpha * 0.92f),
            1f   to Color.Transparent,
        ),
        center = Offset(size.width * 0.42f, size.height * 0.5f),
        radius = size.maxDimension * 1.05f,
    ))
}
```

y `Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1.5f), blurRadius)` (4/7/5) en FIJADA, título y contador. `captionCorner` se borró del spec; `captionMatteAlpha` pasó a `captionScrimAlpha`.

### El token se fijó por medición, no a ojo
Primera medición con `0.78`: la mediana de luma bajo las letras sobre banner casi blanco fue **0.290** ⇒ `1.05/(0.290+0.05) = 3.09:1`, **por debajo de AA 4.5:1**. Se subió a **0.86** y el gradiente se ensanchó (centro 0.42, radio `maxDimension*1.05`) para que las letras nunca caigan en el borde de caída.

### Medición final: 18/18 configuraciones ≥ AA (texto blanco sobre el scrim)

| width | 1.0x | 1.5x | 2.0x |
|---|---|---|---|
| 320 dp | 6.58 | 6.42 | **5.09** |
| 360 dp | 6.71 | 6.58 | 5.96 |
| 393 dp | 6.44 | 6.70 | 6.39 |
| 412 dp | 6.09 | 6.58 | 6.42 |
| 430 dp | 5.83 | 6.42 | 6.55 |
| 600 dp | 5.16 | 5.26 | 5.48 |

Ratio **mínimo 5.09:1**, máximo 6.71:1. La luma del banner por encima del caption se mantiene en 0.79–0.82 (el arte sigue visible) y la franja justo sobre las letras en 0.67–0.93 (el oscurecimiento es local, no una capa sobre media tarjeta).

### Dos topes reales del test (honestidad sobre el método)
1. La medición inicial sobre **toda la caja** del caption mezclaba scrim con la zona ya desvanecida del degradado y daba 0.290 cuando las letras estaban mejor. Se corrigió midiendo el **rectángulo real del nodo del título** (`playlist_spotlight_title_<id>`, `useUnmergedTree = true` porque la raíz de la tarjeta es `clickable`), normalizado contra el nodo capturado.
2. La prueba de "no es una placa" es **`above > 0.45`** (el banner sobre el caption sigue claro) y la ausencia de borde recto en el dibujo; la aserción anterior `edge > caption * 1.25` se eliminó porque con el scrim ancho la franja derecha también se oscurece y no distinguía nada.

---

## §D — P3 / P4: selector único e interacción

### Selector (P3)
Dos `AeroFilterChip` sueltos («Clásica» / «Tarjetas») se leían como dos filtros independientes cuando el modo es una elección mutuamente excluyente. Ahora es un único control de dos segmentos con `AeroSegmentRail` y, cuando el ancho o la escala no dan (`aeroSegmentStacking`: ancho < 330 dp o escala ≥ 1.3), `AeroSegmentStack` vertical. Etiquetas: **«Lista»** y **«Tarjetas»** (el texto de ayuda del mazo pasó de «Usa Clásica…» a «Usa Lista para ver todas en lista.»). Se conservan los `testTag` `playlist_mode_classic` / `playlist_mode_cards` para no romper preferencias ni tests existentes.

`UI8PlaylistModeSelectorTest` (4 tests): exclusividad en ambos sentidos (`assertIsSelected`/`assertIsNotSelected`), cada modo muestra su layout y esconde el otro, las etiquetas dicen lo que muestran, y a 320 dp × 2.0x el selector apilado conserva ambas etiquetas completas.

### Interacción y accesibilidad (P4)
Sin cambios de código: el mazo ya cumplía y se verificó leyéndolo. Movimiento reducido desactiva tween/spring y las rotaciones (`rotationY`/`rotationZ` a 0); los haptics respetan la preferencia; la tarjeta frontal expone `stateDescription = "Playlist N de M"` y las acciones «Siguiente playlist»/«Playlist anterior» sólo cuando se puede avanzar; las traseras consumen punteros en `Initial` y llevan `clearAndSetSemantics`; `settle()` revalida habilitación e identidades antes de commitear y el guard `activeSettleJob` evita que un job cancelado pise offsets.

Secuencia de rotación cubierta por dos tests nuevos: `aFullForwardCycleReturnsToTheFirstCardInOrder` (4 cartas → `p2, p3, p4, p1`) y `goingBackwardWalksTheSameCycleInReverse` (3 cartas → `p3, p2, p1`).

---

## §E — P5: matriz visual y veredicto

### Matriz ejecutada en dispositivo (`emulator-5554`, API 36)
- `widths = 320, 360, 393, 412, 430, 600 dp` × `fontScale = 1.0, 1.5, 2.0` = **18 configuraciones**, ambos modos.
- `am instrument -w -e class …UI8VisualMatrixCaptureTest` → **`OK (7 tests)`** en 21.4 s.
- Evidencia: `/tmp/opencode/ui8-playlists-matrix6/` — **77 PNG**, **18 directorios de configuración**, `manifest.tsv` de 79 líneas (columnas `surface widthDp fontScale density bitmapW bitmapH colors clickables minClickablePx`).

### Tamaños táctiles (manifest)
`deck_two` reporta `minClickablePx` 491/489/**469** a 320/412/**430** dp y 336 px a 600 dp (densidad 1.798 ⇒ 187 dp). En dp el mínimo es **179 dp**: muy por encima de los 48 dp de la guía. `spotlight_no_art` da 497–520 px. Ningún configurable queda por debajo.

### Lo que no se puede afirmar
- La matriz del arnés renderiza **componentes** (spotlight, mazo, menú de modos), no la pantalla completa con sus barras; el `album_mode_menu` capturado es el menú de modos de **álbum**, no el de Playlists — el selector de Playlists se certifica con test JVM, no con captura.
- El banner usado es un PNG sintético liso (claro y oscuro). Sobre arte fotográfico real con zonas de alto detalle no se midió contraste; la sombra tipográfica está para eso, pero **no está certificado con fotos reales**.
- El emulador renderiza con SwiftShader: los tiempos de frame no son representativos y ninguna cifra de rendimiento de este tramo se usa como evidencia de hardware.

### Veredicto por fase
| Fase | Estado |
|---|---|
| P0 auditoría con medidas | **100 %** — divergencia de 24 dp de ancho y contrato de alto distinto, localizados y medidos |
| P1 geometría compartida | **100 %** — 0.0 dp de diferencia, verificado en JVM y en capturas |
| P2 placa negra eliminada con contraste verificado | **100 %** — 18/18 ≥ 4.5:1 (mínimo 5.09:1) |
| P3 selector unificado | **100 %** — 4 tests de semántica, testTags conservados |
| P4 interacción / accesibilidad / movimiento reducido | **100 %** — verificado por lectura y 2 tests de rotación |
| P5 matriz visual | **100 % de la matriz pedida** (18 configs, 77 PNG); arte fotográfico real **NOT_CERTIFIED** |
| Suite JVM completa | **verde** (`:app:testNormalDebugUnitTest`, run 0014) |
| Arnés instrumentado de Playlists | **verde** (7/7) |
| Push / PR | **0 % — no autorizado por el brief** |

### Comandos de reproducción
```bash
# unidad + paridad de geometría + selector + rotación
python3 ~/.local/share/chrisbanes-skills/skills/gradle-run/scripts/gradle_run.py run \
  --workflow <id> -- ./gradlew :app:testNormalDebugUnitTest

# matriz instrumentada
./gradlew :app:assembleNormalDebug :app:assembleNormalDebugAndroidTest
adb -s emulator-5554 install -r -t app/build/outputs/apk/normal/debug/app-normal-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/normal/debug/app-normal-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  -e class 'org.michimusic.mobile.ui.screens.UI8VisualMatrixCaptureTest' \
  org.michimusic.mobile.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull \
  /sdcard/Android/data/org.michimusic.mobile.debug/files/ui8-matrix/. \
  /tmp/opencode/ui8-playlists-matrix-final/
```
