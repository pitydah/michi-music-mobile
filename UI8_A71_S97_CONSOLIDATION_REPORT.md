# UI8 + A71-S9.7 — Informe de consolidación

**Repositorio:** `pitydah/michi-music-mobile`
**Ramas:** `feat/aircat-a71-experience` (PR #22), `feat/ui8-integration-2026-11-18` (PR #23)
**Fecha:** 2026-11-19
**Estado:** ambas ramas mergeadas a `master` con CI verde en cada head. `RELEASE = NO-GO` por el bloqueante externo de §11 (interoperabilidad con un par Michi real) y por el alcance acotado de la medición de rendimiento (§7.4), no por el estado de este trabajo.

## 1. Línea base

| Ref | SHA | Estado |
|---|---|---|
| `master` (línea base) | `07a646e608eafeb37da91f5c965aafdc5f24162e` | merge-base de ambas ramas |
| `master` (tras #22) | `ad9a5da9a5998bd644dadaffba59b488a499d914` | merge commit de PR #22 |
| `eeb3b943` | `eeb3b94392fff307ff4d5cca5bd346490a48bda9` | head previo de PR #22 (FIX_UI-05), no se reaplicó |
| `feat/aircat-a71-experience` | `6623fae5` | 11 commits sobre `eeb3b943`, mergeada en `master` vía #22 |
| `feat/ui8-integration-2026-11-18` | `b1375696` | 4 commits + merge de `master` (`42eaffa3`) + informe, CI combinada 9/9, mergeada vía #23 |
| `master` (final) | `e492b6b14b311c0d25f033a1534bedff6563effb` | merge commit de PR #23 |

Árbol de trabajo: limpio salvo `?? aircat-transfer/build/` (nunca stageado, nunca borrado).

## 2. Commits

### 2.1 `feat/aircat-a71-experience` — 11 commits

Heredados de la sesión anterior (sólo se publicaron, no se reescribieron):

1. `f9381305` feat(aircat): resolve Home anchors from the canonical Michi identity
2. `b8788089` feat(aircat): read recovery checkpoints under the canonical source identity
3. `e2308f68` fix(aircat): report unreadable checkpoint evidence instead of no evidence
4. `80da3ae0` feat(aircat): require review before repairing local checkpoints
5. `6e529d6e` test(aircat): cover the recovery review confirmation

Producidos y verificados en esta sesión:

6. `06430dd1` fix(ui): keep the QR scanner dialog usable at 200% font scale
7. `ce5ab3c8` fix(aircat): keep the AirCat header readable at 200% font scale
8. `09fbf8e6` test(aircat): capture the Home QR surface and guard header truncation
9. `08c3d680` perf(ui): keep the deck drag offset out of composition
10. `9d71f81d` test(ui): measure deck drag frames and card recompositions
11. `6623fae5` fix(aircat): keep the track row dark enough for its white title

Diff acumulado contra `eeb3b943`: **16 archivos, +833 / −31**.

### 2.2 `feat/ui8-integration-2026-11-18` — 4 commits

1. `c840fc54` feat(ui8): integrate the UI8 experience across library, playlists, settings and search
2. `06363f2c` test(ui8): assert the catalog scan contract instead of the empty-query spinner
3. `521d8177` fix(ui8): keep the Settings library screen within the architecture limit
4. `9b881727` fix(ui8): wake the display before the whole-window menu capture

Diff acumulado contra `master`: **73 archivos, +3692 / −552**, incluido `build/verification/michi-audio/skills-used.tsv` (+11).

## 3. Correcciones reales aplicadas

Sólo se declara corregido lo que tiene medición antes/después. Ningún test ni guard se debilitó para lograr verde.

### 3.1 Diálogo QR — tres defectos de accesibilidad y layout

| # | Defecto | Evidencia | Corrección |
|---|---|---|---|
| 1 | Botón de cerrar medía **40.0 dp** | `AssertionError: clickable ContentDescription : [Cerrar] … is 40.0dp tall, below the 48dp target` a 360 dp/1.0x | `IconButton(…, Modifier.size(48.dp))`, igual que el precedente del repo |
| 2 | A 2.0x el contenido excedía la ventana y la `Column` aplastaba el último hijo: confirmar medía **42.666668 dp** | `home_admin_qr_confirm … is 42.666668dp tall` en 360/393/430 dp a 2.0x | `verticalScroll(rememberScrollState())` en la `Column`; el CTA crece en alto en vez de recortarse |
| 3 | A 2.0x el título se quedaba con todo el ancho y **empujaba la X fuera del `Row`**: el diálogo quedaba sin botón de cerrar visible | visible en `a71-s7-qr/w360_fs2.0.png` pre-fix; post-fix la X reaparece alineada | `Modifier.weight(1f)` en el `Text` del título |

También se pasó el `TextButton` "Escanear con la cámara" a `heightIn(min = 48.dp)` y el botón de confirmación de `.height(48.dp)` a `.heightIn(min = 48.dp)`, para que el texto a 2.0x pueda crecer.

### 3.2 Header AirCat — pérdida de palabras a 200% (WCAG 1.4.4)

A 360 dp/2.0x el eyebrow se cortaba en «MICHI LINK · SEGURI…» y el subtítulo en «Gestiona los equipos que pueden compa…». `eyebrow` estaba en `maxLines = 1` y `subtitle` en `maxLines = 2`.

Corrección en `AirCatAeroHeader` (`AirCatAeroChrome.kt`): eyebrow a 2 líneas, subtítulo a 4, con la elipsis conservada sólo como último recurso. Verificado visualmente: a 360/2.0x el eyebrow entra completo en 2 líneas y el subtítulo completo en 4; en 360/1.0, 600/1.0 y 600/2.0 sin cambios.

### 3.3 Fila de pista sobre placa pálida — contraste 2.41:1

`TrackRow` en `AirCatExperienceContent.kt` pintaba `.background(GlassFillLow.copy(alpha = 0.64f))`. `GlassFillLow` es `Color(0x0FFFFFFF)`, un vidrio al 6%: `copy(alpha = 0.64f)` **reemplaza** el alfa por 64% de blanco y convierte la fila en una placa casi opaca. Era el único uso de `GlassFillLow.copy` en `app/src/main`.

Medición sobre píxeles reales del PNG `catalogue_w393_fs2.0.png`, banda `1032x70+0+2340`:

| Estado | Fondo de fila | Texto | Contraste |
|---|---|---|---|
| Antes | `#A6A7A9` | `#FFFFFF` | **2.41:1** (bajo AA 4.5:1) |
| Después | `#171A20` | `#FFFFFF` | **17.4:1** |

Corrección: `.background(GlassFillLow)`, la misma carga que el `AlbumHeader` contiguo. Defecto preexistente, no introducido por S9.7.

### 3.4 Guardas agregadas para que estos defectos no vuelvan

- **Truncamiento de header:** en el arnés de captura, por cada configuración se recolectan los `Text` descendientes de `aircat_aero_header`, se invoca `SemanticsActions.GetTextLayoutResult` y se falla si `multiParagraph.didExceedMaxLines`.
  - La primera versión usaba `hasVisualOverflow` y daba **falso positivo** en texto que se renderiza completo: el diagnóstico medido fue `size=337x63`, `lines=1`, `paragraph=729.0x63.0`, `didOverflowWidth=true`, `maxLines=2`, `constraints maxWidth=729`. `didOverflowWidth` compara el ancho del nodo contra el del párrafo, que ocupa el constraint completo. Se cambió a `didExceedMaxLines`, que es el veredicto propio del layout de que se cayeron palabras.
- **Contraste de fila de pista:** en la pantalla `CATALOGUE`, el arnés muestrea el píxel pintado dentro de cada nodo `aircat_track_selection` (a 6 px del borde derecho, en el centro vertical) y falla si la luminancia relativa WCAG del fondo es ≥ 0.20. Además asserta que al menos una fila se muestreó en la matriz, para que la guarda no pase sin probar nada.
- **Recomposición del deck:** test instrumentado que cuenta ejecuciones del lambda `cardContent` durante 20 movimientos de arrastre con el mismo signo y falla si hay más de una.
- **Ninguna guarda aserta un presupuesto de frames** (ver §7).

## 4. Identidad canónica y checkpoints (A71-S9.7, heredado)

- `UnifiedDevice.canonicalMichiId` se puebla desde `PairedDevice.effectiveMichiId` vía `LinkDeviceRosterProjector`; `AirCatSourceUi.canonicalMichiId` lo expone; `AirCatExperienceHost.toSourceUi` lo usa para `store.anchorForSource`, mantiene `deviceId` de registro para el coordinador y bloquea con causa visible si un equipo pareado no tiene identidad verificada. Discovery no puede inventar la identidad.
- Los checkpoints se persisten con `remoteDeviceId = intent.sourceMichiId` (identidad canónica, no el id de registro). Al reparar se mapea canónico→registro para `AirCatOperationCoordinator.recover`. Si el repositorio efectivo falta, hay error explícito, nunca lista vacía.
- `AirCatExperienceState.fileEvidenceUnavailable` distingue «no pudimos leer los registros locales» de «no hay evidencia».
- La reparación local exige revisión previa en un `AlertDialog` («Revisar reparación local» con origen, `transferId` corto y archivos afectados); cancelar no repara.
- Tests verdes: `LinkDeviceRosterProjectorTest` (7), `AirCatExperienceControllerTest`, `AirCatExperienceContentTest`.

## 5. Certificación visual

### 5.1 Metodología y un hallazgo que cambió el diseño del arnés

Las ocho superficies inline se capturan con `requiredWidth(widthDp.dp)` dentro de un `CompositionLocalProvider(LocalDensity provides Density(effectiveDensity, scale))`, con aserción de ancho medido (±3 px), targets táctiles ≥ 48 dp, ≥ 3 colores muestreados, y las guardas de §3.4.

La novena superficie (QR administrativo de Home) es un `Dialog`: **compone en su propia ventana y la densidad del `CompositionLocalProvider` no lo alcanza**. La primera versión del test la capturaba con el mismo override y produjo 16 PNG **byte-idénticos** (`md5 0d7c704f8b9ef2c4c6d3d08cf811dd6e` en las 16 celdas; `md5sum *.png | sort -u | wc -l` = 1). Ese hash duplicado fue lo que delató la matriz falsa; se descartó y se rediseñó. Ahora la matriz del QR la impone el dispositivo real (`adb shell wm size` para el ancho, `settings put system font_scale` para la escala), una captura por corrida, con `widthDp`, `fontScale`, densidad reales y `measuredPx` en el manifiesto.

### 5.2 Resultados

| Superficie | Configuraciones | Evidencia |
|---|---|---|
| hub, sources, catalogue, review, transfer, result, trust, trust_confirm | 4 anchos × 4 escalas = **128 PNG** | `/tmp/opencode/a71s9/shots12/` + `manifest.tsv` (129 líneas: encabezado + 128 filas) |
| QR administrativo Home | 4 anchos × 4 escalas = **16 configuraciones, 16/16 OK**, 32 PNG (estado inicial y tras scroll) | `/tmp/opencode/a71s9/shots7-qr/` + manifiesto de 33 líneas |

**Total: 9 superficies, 144 configuraciones, 160 PNG.**

Integridad de la matriz: 127 hashes únicos de 128 en `shots12`. La única colisión es `trust_w360_fs2.0.png` == `trust_confirm_w360_fs2.0.png`, y es explicable, no un defecto: la confirmación de trust es una tarjeta **inline** (`TrustConfirmationCard`), no un diálogo; a 360 dp/2.0x queda por debajo del pliegue y el viewport capturado coincide. En las otras 15 configuraciones de esa superficie los PNG difieren.

En el QR, las alturas medidas prueban que la escala es real y no una etiqueta: 798×**1029** px a 1.0 → **1468** a 1.3 → **2038** a 1.6 → **2232** a 2.0. A 600 dp el ancho es 1480 px y las alturas 903/1052/1131/1365.

**Limitación que se declara en vez de fabricarse:** a 360, 393 y 430 dp el ancho medido del diálogo es el mismo (798 px ≈ 304 dp), porque el contenedor de diálogo de la plataforma elige el ancho. En un teléfono, el eje ancho **no es una variable de reflow** para esta superficie; las cuatro columnas de ancho no producen cuatro layouts distintos y así se reporta.

### 5.3 Inspección visual (no sólo captura)

Se generaron contact sheets con `montage` y se inspeccionaron todas las superficies en las configuraciones extremas (360/1.0, 600/1.0, 360/2.0, 600/2.0) y en la banda intermedia (393/2.0, 430/2.0, 393/1.3, 430/1.6).

- **hub**: header completo en las cuatro configuraciones; subtítulo «Archivos originales · biblioteca musical» envuelve a 2 líneas a 2.0x sin recorte.
- **sources**: correcto; a 2.0x la segunda tarjeta queda bajo el pliegue, con la pantalla scrolleable.
- **catalogue**: el footer con «0 canciones seleccionadas · 0 MB» y los botones Continuar/Volver sigue visible a 360/1.0 (el defecto histórico del footer a 0 dp sigue corregido). Aquí se encontró y cerró el defecto de contraste de §3.3.
- **review**, **transfer**, **result**: correctos en las cuatro configuraciones, sin pérdida de texto.
- **trust**: a 360/2.0x el eyebrow y el subtítulo se cortaban (§3.2). Corregido y verificado en la captura post-fix.
- **trust_confirm**: muestra «Confirmar desvinculación», el impacto completo y los botones Desvincular/Conservar; a 360/2.0x aparece identica a `trust` porque la tarjeta queda bajo el pliegue (limitación de viewport ya explicada).
- **QR administrativo**: inspeccionado en 360/2.0 (scroll hasta el CTA con recorte que sólo alcanza el padding de 24 dp, no al botón) y en 600/2.0 (diálogo completo: título en una línea, campo a todo el ancho, botón entero).

Observación no bloqueante: a 2.0x la flecha de volver queda centrada verticalmente contra un bloque de header de 4 líneas. Sin pérdida de contenido ni de target táctil.

## 6. Settings (UI8)

- `SettingsLibraryDetails.kt`: 270 → **206 líneas**; nueva `SettingsLibraryRootSection.kt` de 121 líneas.
- `python3 tools/check_settings_architecture.py` → `SETTINGS ARCHITECTURE PASS: 160 root lines, 8 domain panels, no forbidden root dependencies` y `GENRES/PLAYLISTS ARCHITECTURE PASS: single genre owner, one playlist row`.
- Test instrumentado del menú de álbumes: se corrigió la captura de ventana en blanco con `wakeDisplay()` (`svc power stayon true`, timeout largo, `KEYCODE_WAKEUP`, `wm dismiss-keyguard`) antes de `setContent` y `captureWholeWindow()` con reintento acotado; verificado con pantalla dormida y despierta.

## 7. Rendimiento del deck (C2)

### 7.1 Qué se investigó

El deck de artistas (`MichiCardDeck.kt`, 405 líneas, usado desde `ArtistMosaicGrid.kt` en `cardsMode`) mostraba `val previewDirection = if (dragPx > 0f) -1 else 1` dentro de la composición, es decir, una lectura de estado de arrastre en el hilo de composición.

**La hipótesis se descartó por medición, no por intuición.** Un probe instrumentado (ya eliminado) contó ejecuciones del cuerpo de tarjeta durante un arrastre de 10 movimientos con el mismo signo: `DECK-PROBE left=0 flip=2 total=4 selected=artist-0`. Resultado: **0** cuerpos recompuestos mientras el signo no cambia; **2** al cruzar cero (los dos slots traseros cambian de item). Compose ya estaba salteando los cuerpos.

Aun así se aplicó el cambio defensivo `derivedStateOf { dragPx > 0f }`, con un comentario que **no afirma** una mejora de frames inexistente: mantiene el offset vivo fuera de la composición para que eso siga siendo cierto si el contenido de la tarjeta gana una dependencia más barata de leer.

### 7.2 Medición

- Arnés nuevo `MichiCardDeckFrameCaptureTest` (instrumentado, `Choreographer.FrameCallback` autoreposteado, arrastre de 60 movimientos) que escribe `deck-frames.txt` con dispositivo, `refreshHz`, frames, p50, p95, max, janky y la lista de deltas. No aserta un presupuesto de frames: sólo que la medición existe (`frames.size >= MOVES/2`).
- Resultado en emulador (`sdk_gphone64_x86_64`, API 36, SwiftShader): `DECK-FRAMES device=Google/sdk_gphone64_x86_64 api=36`, `refreshHz=60.00`, `frames=187 p50=16ms p95=16ms max=16ms`, `janky=0`.
- Test nuevo `MichiCardDeckRecompositionTest`: falla si un arrastre continuo recompone más de una vez los cuerpos de tarjeta.

### 7.3 Medición en hardware físico (cierre del bloqueante)

Con el teléfono conectado (`26dfb91d`, Xiaomi 25010PN30G, API 36, pantalla de 120 Hz) se corrió el mismo arnés tres veces, sobre el APK `normal` debug compilado desde `master` `e75f5d95`:

| Corrida | Frames | p50 | p95 | max | Janky |
|---|---|---|---|---|---|
| 1 | 279 | 8 ms | 8 ms | 33 ms | 1 |
| 2 | 301 | 8 ms | 8 ms | 24 ms | 1 |
| 3 | 295 | 8 ms | 8 ms | 16 ms | 1 |

`refreshHz=120.00` en las tres. **p50 y p95 = 8 ms contra un presupuesto de display de 8.33 ms** (120 Hz), con **1 frame janky por corrida** (0.3–0.4%): un único hitch de 16–33 ms en ~300 frames, siempre el primero tras iniciar el arrastre.

El emulador, para comparar, dio 16 ms p50/p95 con `janky=0` (SwiftShader, 60 Hz): por eso el emulador nunca sirvió como evidencia de suavidad y el número de hardware es el que vale.

### 7.4 Alcance exacto de esta certificación

Lo medido es el **costo de gesto, animación y composición del deck con contenido sintético** (tarjetas de color plano; sin decode de imágenes, sin red, sin Coil). Con ese alcance, el jank del deck **deja de estar `NOT_CERTIFIED`**.

Lo que **sigue sin medir** y no se presenta como certificado:

- Arranque en frío y primera composición con artwork real decodificándose.
- Scroll/arrastre con imágenes grandes y caché de disco fría.
- Rendimiento en dispositivos de gama baja distintos de este.
- La evidencia física previa de este informe (200 frames, 46 janky, p95 37,98 ms) provenía de un arnés que ya no existe en el repo y de un escenario no documentado; **no se usa como comparación** porque cambian a la vez el arnés y el escenario.

## 8. CI

### 8.1 `feat/aircat-a71-experience`

Run `44636422060` sobre `6e529d6e` (evento `pull_request`): **completed / success**, 9/9.

| Job | Estado |
|---|---|
| Job A — Lint | success |
| Job B — Unit Tests (Normal) | success |
| Job C — Unit Tests (F-Droid) | success |
| Job D — Room Migrations & Schemas | success |
| Job E — Build Normal | success |
| Job F — Build F-Droid | success |
| Job G — Instrumented Tests (Normal, API 36) | success |
| Job H — UI7 Release Gate | success |
| Quality Gate — Release Candidate Stability | success |

### 8.2 `feat/ui8-integration-2026-11-18`

Run `44624284390` sobre `9b88172`: **9/9 success** (Lint, Unit Normal/F-Droid, Room, Build Normal/F-Droid, Instrumented API 36, UI7, Quality Gate).

### 8.3 Head final de A71 (`6623fae5`)

Run `44641438284` sobre `6623fae5427078d23d5c9ad6ebf3424f4a765cfe`, **attempt 3**: **completed / success**, 9/9 (A Lint, B Unit Normal, C Unit F-Droid, D Room, E Build Normal, F Build F-Droid, G Instrumented API 36, H UI7 Release Gate, Quality Gate).

**Estabilidad de Job G — se declara, no se oculta.** El mismo SHA necesitó tres intentos:

| Intento | Resultado | Firma |
|---|---|---|
| 1 | 1 fallo | `GenreArtworkComposeRenderTest > everyPackagedCropPaintsInTheProductionPanel`: `NoSuchElementException: Key timba is missing in the map` |
| 2 | 2 fallos | el mismo test con `ComposeTimeoutException: Condition still not satisfied after 20000 ms`; y `GenreLibraryPanelComposeTest > detailPlaysOnlyAuthorizedTracksWithRealIndex`: `IllegalArgumentException: performMeasureAndLayout called during measure layout` desde `Espresso.onIdle` |
| 3 | **verde** | 9/9 |

Los dos tests que fallaron son de las vistas **Géneros (UI7)**, que ninguna de las dos ramas modifica. Evidencia de que no hay regresión de contrato introducida por este trabajo:

- `GenreArtworkComposeRenderTest#everyPackagedCropPaintsInTheProductionPanel` aislado: `OK (1 test)` en 3.9 s.
- Suite instrumentada **completa** en local sobre el mismo APK y el mismo orden que CI (primero `devices.aircat…`, después `ui.screens…`): **`OK (194 tests)`** en 263.7 s, con `GenreArtworkComposeRenderTest` (7) y `GenreLibraryPanelComposeTest` (14) verdes.
- Un probe temporal (ya eliminado, nunca commiteado) midió el estado de los assets inmediatamente después de las capturas de AirCat: `installed=667`, `manifestLines=667`, `approved=665`, `rejected=[]` — idéntico al estado en frío. El `AssetManager` no queda contaminado por las capturas.
- La tasa de fallo local del combo exacto que falló en CI fue **1 de 4** corridas, no determinista; tres repeticiones posteriores dieron `OK (4 tests)`.

Conclusión honesta: Job G es inestable en el emulador de CI bajo carga, con dos firmas distintas de timing (`waitUntil` de decode y `Espresso.onIdle` durante layout). No se relajó ni reescribió ningún test para lograr el verde; el verde del intento 3 es el mismo código que falló en los intentos 1 y 2.

### 8.4 Validación combinada de #23

El merge de `master` (con #22 dentro) sobre `feat/ui8-integration-2026-11-18` es el commit `42eaffa3`. El único conflicto fue `build/verification/michi-audio/skills-used.tsv` y se resolvió conservando **los dos bloques completos** (48 líneas base + 11 de UI8 + 10 de A71 = 69 líneas). `bash scripts/verify_michi_opencode_skills.sh --check` → `RECEIPT_FORMAT_PASS: nine Chris Banes skills declared with references`, exit 0.

Run `44650757775` sobre `b1375696e3bda8fb951bce2391c81f72613de200`: **attempt 1, completed / success, 9/9** (A Lint, B Unit Normal, C Unit F-Droid, D Room, E Build Normal, F Build F-Droid, G Instrumented API 36, H UI7 Release Gate, Quality Gate). Ese head contiene a la vez el trabajo de A71 (ya en `master` vía #22), la integración de UI8, la resolución del TSV y este informe. A diferencia de la validación de A71, aquí Job G pasó al primer intento.

## 9. Conflicto de merge y resolución

`build/verification/michi-audio/skills-used.tsv` fue el único conflicto al mergear `master` (con #22 ya dentro) hacia `feat/ui8-integration-2026-11-18`:

- `master`: 48 líneas.
- A71 agrega 10 líneas al final (comentario `# A71 (2026-11-18)` + filas `a71-bootstrap`, `a71-plan`, `a71-implement`, `a71-concurrency`, `a71-api`, `a71-ui-state`, `a71-ui-component`, `a71-ui-test`, `a71-gradle`).
- UI8 agrega 11 líneas al final (comentario `# UI8 (2026-11-18)` + `ui8-bootstrap`, `ui8-concurrency`, `ui8-api`, `ui8-ui-state`, `ui8-ui-component`, `ui8-ui-test`, `ui8-gradle`, `ui8-ui-animation`, `ui8-focus`, `ui8-performance`).

Ambos bloques se agregan en el mismo punto del archivo. La resolución conserva **los dos bloques completos**; descartar cualquiera de ellos borraría trazabilidad de skills que el gate exige.

## 10. Estado por área, con porcentajes separados

Se separan los porcentajes porque miden cosas distintas y promediarlos ocultaría el estado real.

| Área | Cobertura | Porcentaje | Estado |
|---|---|---|---|
| A71 S9.7 — identidad canónica, checkpoints, revisión previa | 3 de 3 ejes implementados y testeados | **100%** | PASS |
| A71 — certificación visual de superficies | 9 de 9 superficies × 16 configuraciones | **100%** | PASS |
| A71 — defectos visuales encontrados y corregidos | 5 de 5 con medición antes/después | **100%** | PASS |
| UI8 — integración (library, playlists, settings, search) | 4 commits, diffs completos | **100%** | PASS |
| UI8 — Settings dentro del límite arquitectónico | checker en verde | **100%** | PASS |
| Rendimiento del deck — recomposición | 1 hipótesis medida y descartada; guarda agregada | **100%** del análisis | PASS (sin defecto) |
| Rendimiento del deck — jank de gesto/animación en hardware | 3 corridas, 279/301/295 frames en 120 Hz | **100%** | PASS (p95 8 ms, 1 janky) |
| Rendimiento del deck — artwork real y arranque en frío | sin medir | **0%** | NOT_CERTIFIED |
| Interoperabilidad externa con un par real | no hay par Michi en el entorno | **0%** | BLOCKED_EXTERNAL |
| Fase D — merge de #22 | ejecutado sobre CI 9/9 | **100%** | MERGED (`ad9a5da9`) |
| Fase D — merge de #23 | CI combinada 9/9 en attempt 1 | **100%** | MERGED (`e492b6b1`) |

## 11. Bloqueantes y siguiente paso

**Bloqueantes y límites declarados:**

1. **Jank de gesto/animación del deck: MEDIDO en hardware** (§7.3): p50/p95 8 ms a 120 Hz, 1 janky en ~300 frames, tres corridas consistentes. Queda fuera de alcance, y así se declara, el artwork real y el arranque en frío (§7.4).
2. **EXTERNAL_INTEROP BLOCKED_EXTERNAL.** No hay un par Michi real; catalogue, review, transfer y result se capturan desde sus composables de producción con estado explícito, no desde una sesión viva.
3. **Una celda de la matriz visual comparte hash** (`trust` y `trust_confirm` a 360/2.0x) por la tarjeta inline bajo el pliegue, no por un defecto.
4. **`RELEASE = NO-GO`** por los puntos 1 y 2, que dependen de hardware y de un par externo, no por el estado de este trabajo.

**Fase D, completa:** CI verde en `6623fae5` (9/9, run `44641438284`, attempt 3) → `gh pr ready 22` y **merge commit normal `ad9a5da9`** en `master` → merge de `master` en `feat/ui8-integration-2026-11-18` (**`42eaffa3`**) resolviendo el TSV con ambos bloques → CI combinada de 9 jobs (**run `44650757775`, 9/9 en attempt 1**) → `gh pr ready 23` y **merge commit normal `e492b6b1`**. Sin force push, sin squash, sin reset destructivo, sin stagear `aircat-transfer/build/`.

**Lo que queda para levantar los bloqueantes:** levantar un par Michi real (Micro Server o Desktop Player) para mover `EXTERNAL_INTEROP` de `BLOCKED_EXTERNAL` a medido; y, si se quiere certificar rendimiento más allá del gesto, medir arranque en frío y drag con artwork real decodificándose.
