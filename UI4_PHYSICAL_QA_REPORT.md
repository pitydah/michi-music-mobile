# UI4 — Informe de QA física y certificación visual

## Alcance y candidato

- Rama: `feat/ui4-aero-motion` (PR #14, **draft**).
- Base UI3 certificada: `6bae6486cf57a542db5517235c716c3ad5f4d1c2`.
- SHA inicial UI4: `236ba8fbcf4e529dfb5fef802e97d02b2ca18547`.
- SHA final: el commit que contiene este informe; el head exacto y sus checks se
  registran en el PR porque un commit no puede contener su propio SHA.
- Dispositivo: `device-59b81bcc87c6`, Xiaomi 25010PN30G, Android 16/API 36,
  1440×3200, 600 dpi. El serial ADB no se conserva en este informe.
- APK observado: `org.michimusic.mobile.debug`, versionCode 451,
  versionName `0.1.0-alpha`.

El objetivo fue comprobar UI4 sobre hardware físico, proteger los contratos UI3
y corregir únicamente defectos demostrados. No se desinstaló la aplicación, no
se borraron datos y el emulador conectado no se usó como sustituto del teléfono.
La instrumentación física se dirigió explícitamente a un único dispositivo.

## Resultado visual y funcional

| Escenario | Resultado observado |
|---|---|
| Biblioteca: Lista, Mosaico y CoverFlow | PASS; los tres modos permanecen disponibles |
| CoverFlow lateral → selección → álbum → atrás | PASS; identidad y selección conservadas |
| Mosaico → álbum → atrás | PASS; identidad y posición conservadas |
| Reproducción y MiniPlayer ↔ Now Playing | PASS; sin superficie duplicada ni cierre |
| Artwork real y fallback ausente | PASS |
| Menús y sheets | PASS; sin capa o rectángulo fantasma observado |
| Dock a 100 %, 150 % y 200 % de fuente | PASS; una fila a 100/150 %, 2×2 a 200 % |
| Cabecera Biblioteca a 200 % | PASS tras corrección; título, conteo y acciones completos |
| Movimiento reducido | PASS en apertura/cierre de álbum y Now Playing |
| Alto contraste | PASS tras corrección; arranque persistido y rutas sin cierre |
| Cambio de canción en Now Playing | NO EJERCITADO; el álbum disponible tenía una sola pista |

La navegación física no mostró pérdida de identidad, reproducción involuntaria,
doble artwork, doble player, ghost layers ni regresión de scroll en las rutas
inspeccionadas. Los videos y capturas incluyen biblioteca privada y permanecen
fuera de Git; este informe publica solo resultados agregados y hashes.

## Investigación `SlotWriter`

El test existente
`ArtistEnrichmentPanelTest#ambiguousHistoricalAssociationsExposeRealChoicesAndPersistTheExplicitSelection`
se ejecutó diez veces en el teléfono: **10/10 PASS**, cada repetición con
`OK (1 test)` y exit code 0. No apareció `SlotWriter`, `SlotTable`, fallo de
instrumentación ni crash. El manifiesto local de repeticiones tiene SHA-256
`db8562a0058795bac72abdae1a30bb93e2b44518574ce98037b42a298bf3afc9`.

Clasificación: inestabilidad intermitente de teardown no reproducida. No existe
evidencia para atribuirla a UI4 ni para afirmar una causa raíz; no se cambió el
código por este evento.

## Defectos demostrados y correcciones

### P0 — crash con alto contraste

Se reprodujo repetidamente un cierre en foreground:

`IllegalArgumentException: colors must have length of at least 2 if colorStops is omitted`

La política semántica de alto contraste elimina correctamente la decoración y
devuelve un único color opaco. Dos consumidores lo entregaban directamente a
`Brush.verticalGradient`, que exige al menos dos colores. El buffer privado del
crash tiene SHA-256
`6e6e8e62cce6665df55bfec29f051e4eed6f3e86e6f388c172bc0afa5cfdf8ea`.

Corrección acotada: `aeroVerticalGradientBrush` devuelve `SolidColor` para el
singleton de alto contraste y conserva `Brush.verticalGradient` para los stops
normales. Home y el panel de error de búsqueda usan esa política compartida.
Una prueba RED falló antes de existir la función y pasó después.

Verificación física exacta: se instaló el APK corregido sobre los datos
existentes, conservando la preferencia persistida que causaba el loop. El cold
start terminó en 508 ms, Home quedó renderizado, el proceso siguió vivo y el log
`AndroidRuntime` quedó vacío. Después el operador desactivó alto contraste y
movimiento reducido; Biblioteca permaneció estable.

### P1 — cabecera truncada a 200 %

La cabecera horizontal de Biblioteca dejaba ancho insuficiente al título y al
conteo cuando la fuente era 200 %. `LibraryHeaderLayout` conserva el diseño
horizontal normal y apila las acciones debajo desde font scale 1.5. Pruebas de
política/layout y verificación física a 200 % confirmaron texto completo y Dock
2×2 intacto. La captura privada corregida tiene SHA-256
`5192fe03cfc598aefea2eb5049c8e44e81852eb61a9bbb9c0bbd340c556c3acf`.

No quedan P0/P1 conocidos dentro de las rutas ejercitadas.

## Evidencia privada referenciada por hash

| Evidencia | SHA-256 |
|---|---|
| Navegación Biblioteca/CoverFlow/álbum/player, 139.5 s | `8a562d9710b68e1f195ec79511ada928ba8d0cd4f317ec02326715e07cd442da` |
| MiniPlayer ↔ Now Playing, 41.4 s | `2f96a24849c3808bfcc33c47356d9953bae3148147dc860f5978f840fa863ee6` |
| Movimiento reducido confirmado, 29.2 s | `30f2fdfa9ffdca91dc1c407398b82cd315ba91ee56277e53a37c6b8f9b6fb6b5` |
| Alto contraste, 27.1 s | `0964fcac09335549d53ba35eef5baa45c6980c560f9950201c84403d46097a5d` |
| Arranque corregido con alto contraste persistido | `892b05cd54c3fb2f5f256bcbd6db6737d420f521f22dc8ed7846fa6eff93a3a6` |
| Biblioteca tras restaurar preferencias | `4251894431cd8948e36aa7a96501f90d4b941e92b858568566ce53408b057a72` |

Los logs `AndroidRuntime` asociados a las secuencias corregidas/restauradas
quedaron vacíos (SHA-256 del archivo vacío: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`).

## Automatización y builds

- Gradle run 0009: `AeroContrastAndGradientTest` — PASS.
- Gradle run 0010: política de gradiente, M60, Home y búsqueda — PASS.
- Gradle run 0013: `LibraryHeaderLayoutTest` + lint F-Droid — PASS.
- Gradle run 0014: `test lint assembleNormalDebug assembleFdroidDebug
  assembleNormalRelease assembleFdroidRelease` — PASS (473.19 s).
- `tools/ui4_acceptance.py` — **42/42 PASS**.
- `tools/tests/test_ui4_acceptance.py` y `test_ui4_preflight.py` — **7/7 PASS**.
- Contrato modal Aero — **16/16 PASS**.
- Contrato release Aero — **18/18 PASS**; el build Android está probado
  separadamente por Gradle run 0014.
- Verificador launcher UI3 — PASS.

## Rendimiento y límites de hardware

La evidencia acotada a 120 Hz mostró, según la ruta, entre 0.00 % y 0.41 % de
frames clasificados como janky; no es una certificación sostenida. Se intentó
seleccionar 60 Hz de forma no privilegiada, pero aunque el setting reflejó 60,
`dumpsys display` mantuvo el modo físico activo en 120 Hz. El comando oficial
para fijar el modo fue rechazado por `WRITE_SECURE_SETTINGS`. No se usó root ni
se eludió la seguridad, y el valor se restauró a 120.

- Comparativa física 60/90/120 Hz: **BLOCKED_HARDWARE**.
- Sesión sostenida térmica/batería: **BLOCKED_HARDWARE**.
- Dynamic Mesh: **DISABLED**;
  `AeroDynamicMeshPerformanceCertified=false` permanece sin cambios.
- Teclado/D-pad físico: **BLOCKED_HARDWARE** (sin periférico disponible).
- TalkBack con operador: **BLOCKED_HARDWARE**.

## Restauración

- Font scale final: `0.9`.
- Peak refresh setting final: `120`.
- Alto contraste: OFF, confirmado por operador.
- Movimiento reducido: OFF, confirmado por operador.
- Proceso de la app vivo y Biblioteca estable tras la restauración.
- No se habilitó Dynamic Mesh.

## Revisión y veredictos

Se realizó self-review del diff y no se observaron cambios de autoridad de
playback, identidad, navegación, menús o protocolos. La revisión independiente
de los logs originales y del nuevo head del PR sigue pendiente; el manifest de
skills también se limita a `RECORDED_NOT_VERIFIED` hasta esa revisión.

- `UI4_VISUAL_GO`: **GO**, limitado a las rutas visuales físicas enumeradas.
- `UI4_PHYSICAL_GO`: **NO-GO — BLOCKED_HARDWARE**, por ausencia de
  60/90/120 controlado, sesión sostenida, teclado/D-pad y TalkBack físico.
- `UI4_MERGE_GO`: **NO-GO**, pendiente de revisión independiente y CI verde
  sobre el head exacto que contiene las correcciones y este informe.

Estos veredictos **no autorizan merge ni release**. PR #14 debe permanecer draft.
