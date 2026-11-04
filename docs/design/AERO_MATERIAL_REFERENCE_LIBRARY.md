# Michi Aero · Biblioteca de referencias y materiales

**Versión:** AMR-06/07 · **autoridad:** Michi Aero existente, contrato R16.4 y capturas entregadas por el usuario.
**Objetivo:** decisiones reproducibles para Home, Biblioteca, Buscar, Devices, Remote y sus componentes compartidos. **No es un tema nuevo**.
**Estado:** documentación incluida en el parche parcial; no aplicar en destino hasta `OFFLINE_READY`.

## 1. Autoridad visual y límites

1. **Primaria:** captura de Now Playing anterior entregada por el usuario (banda ciruela compacta) y su corrección centrada AMR-COMPACT-02. Captura posterior = evidencia de regresión, no inspiración. Las imágenes de álbum pertenecen a terceros y **no se copian** al repositorio ni a este paquete.
2. **Primaria:** `docs/contracts/R16_AERO_IMPLEMENTATION_CONTRACT.md`, `ui/theme/Color.kt`, `AeroMaterials.kt`, `AeroSurface.kt`, `AeroM60Editorial.kt` del repositorio Michi.
3. **Referencia de composición original:** [AeroPlay – Car Media Player, George Gokieli](https://dribbble.com/shots/27588212-AeroPlay-Car-Media-Player) — *inspiración referencial*, no licencia de distribución de sus gráficos, ni autorización para copiar layouts o iconos.
4. **Catálogo de iconos que ya usa Michi:** [Phosphor Icons Core](https://github.com/phosphor-icons/core), [MIT](https://github.com/phosphor-icons/core/blob/main/LICENSE). Conservar iconos vectores existentes; no descargar nuevas variantes si no cambian una función real. El proyecto incorpora el conjunto de iconos previamente seleccionado; este corte no copia SVG nuevos.

## 2. Fuentes técnicas verificadas (exactamente qué adoptar y qué no)

| Fuente y licencia | Qué sirve | Aplicación exacta en Michi Aero | Uso en AMR-06/07 |
|---|---|---|---|
| [Chris Banes — skills](https://github.com/chrisbanes/skills/tree/main/skills) | `compose-component-design`, `compose-ui-testing-patterns` y `gradle-run` | API `modifier` sobre raíz, composables simples, tests de callback sin DI y validación Gradle cuando exista SDK | **Referencias metodológicas**. La ejecución de las skills dentro de OpenCode debe quedar registrada separadamente |
| [Android Graphics Modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) | `drawWithCache` almacena brochas/shaders dependientes del tamaño | `AeroFinishDrawing.kt`: luz estática y grano en cache; **no** ruido random por frame | **Técnica empleada** en implementación original propia |
| [Paper Shaders Android](https://github.com/AndreFrelicot/paper-shaders-android) · [Apache 2.0](https://github.com/AndreFrelicot/paper-shaders-android/blob/main/LICENSE) | `grain-gradient`, `paper-texture` para estudiar frecuencia del ruido y rugosidad | **No** enlazar AGSL. El recurso que ya integra Aero es PNG RGBA propio de 128px (`aero_graphite_grain.png`) con generador determinista | **Inspiración técnica**, sin código ni shaders copiados. RuntimeShader exige API 33+ y Michi minSdk=31 |
| [Haze](https://github.com/chrisbanes/haze) · [Apache 2.0](https://github.com/chrisbanes/haze/blob/main/LICENSE) | Referencia de vidrio y refracción óptica | MiniPlayer/dock/overlay comparten el acabado opaco `Smoked` actual. No ejecutar blur en producción por el contrato R16 y costo no medido | **Referencia de material**, sin dependencia incorporada |
| [Cloudy](https://github.com/skydoves/Cloudy) · Apache 2.0 | Comparación alternativa de vidrio | No apilar con Haze, ni usar como efecto adicional sobre cards | **Alternativa descartada** en este corte |
| [Compose Unstyled](https://github.com/composablehorizons/compose-unstyled) · [MIT](https://github.com/composablehorizons/compose-unstyled/blob/main/LICENSE) | Comportamiento accesible sin decoración Material forzada | Conservar `MichiIconAction`, `AeroSegmentedChoice`, `MichiFineSlider` existentes; consultar patrones solo si se detecta una limitación funcional real | **Referencia de diseño de controles**, sin nueva dependencia |
| [kmpalette](https://github.com/jordond/kmpalette) · MIT / módulo androidx-palette Apache 2.0 | Extracción cromática de carátulas | Posible implementación futura **solo si se conserva paleta Michi**; prohibido derivar color de texto y afirmar que viene de píxeles. Requiere caché/off-main y compatibilidad de versión | **Backlog**, no se incorpora ahora |

**Regla de licencias:** se han registrado URLs y tecnologías públicas, sin redistribuir capturas, fuentes, SVG, AGSL, efectos o código de terceros. La textura empacada es original y reproducible; no requiere incorporar avisos de tercero. Si se copian componentes en el futuro, incluir `LICENSE` y atribución **antes** de añadir sus bytes.

## 3. Recetas ópticas concretas del corte

| Elemento real | Rol | Acabado | Límite y razón |
|---|---|---|---|
| Hero de `AeroLibraryFeature` | Hero existente + overlay | `Anodized`: luz α=.038; grano α=.022; borde original único | Una placa focal, no contenedores anidados. Conservar tres acciones y artwork real |
| `ContinueListeningCard` | `Raised` | `Anodized`, acento de conexión en stroke | Host remoto y teléfono no se confunden. 0 shaders nuevos |
| Estado vacío de Home | `Content` | `Satin`, luz α=.020; grano α=.016 | No simular una portada ni mostrar transportes inertes |
| Playlist tile Home | `Raised` | `Plain` | La tinta del pictograma aporta la jerarquía, no una textura repetida |
| Filas álbum/artista/playlist | `Raised` | `Plain` | Evita grano y elevación por cada celda de `LazyColumn` |
| Campo Buscar | `Content` | `Inset`, shade α=.120 y bordes de foco | Mantener IME, cursor, accesibilidad y botón de limpiar |
| Resultados artista/álbum | `Raised` | `Plain` | Sin duplicación de `.clickable` / `onClick`; track activo conserva borde semántico existente |
| Equipo local Devices | `Raised` | `Anodized` | Un dispositivo principal; no darle color de conectado sin evidencia |
| Peer LAN | `Raised` | `Anodized` solo si `connected`, en otro caso `Plain` | Discovery ≠ conectado. Cian no puede fingir validación de hardware |
| Panel arte no disponible Remote | `Raised` | `Anodized` | Una escultura neutra sin imagen falsa; conservar texto de carátula no disponible |
| Salida seleccionada Remote | `Content` | `Satin` | Menor jerarquía que el dispositivo principal; sin segundo marco |
| Barra técnica Now Playing | Fondo ciruela `#211A30` actual | `Satin`, sin grano | **Mantener compacta** con dos columnas y pie central, no sacrificar portada |

### Tokens de pigmentación vigentes

`#090C12` obsidiana; `#1B2029` grafito; `#232A34` grafito elevado; `#FF5167` coral; `#7DF4FF` cian técnico; `#BEC7D2` texto secundario. El ciruela `#211A30` solo sirve para la banda técnica existente; no convertir toda la app en violeta.

### Prohibiciones de implementación

No `AGSL` activo; no `blur` por item; no shadow per-frame; no pills alrededor de icon-only; no brillo en listas de 100+ pistas; no fondo diferente para cada card; no añadir archivos de terceros sin licencia. Mantener `minSdk=31`, Kotlin y Compose del proyecto hasta una decisión aparte. En alto contraste `aeroResolvedFinish` devuelve `Plain`; conservar el contorno legible.

## 4. Recursos disponibles y cómo regenerarlos

- **Recurso ya incluido en el `.patch`:** `app/src/main/res/drawable-nodpi/aero_graphite_grain.png` (128×128 RGBA). Generado por `scripts/generate_aero_grain.py` y utilizado por `AeroFinishDrawing.kt`. **Original y reproducible**; no se descargó de un repositorio ajeno.
- **Reproducción:** `python scripts/generate_aero_grain.py` en un árbol temporal. Comparar SHA-256 con la manifestación de activos. No sobrescribir el recurso visual en el repositorio final hasta aplicar el parche completo.
- **Atlas de comparación** `AERO_MATERIALS_ATLAS.png` se distribuye solo dentro del paquete ZIP como guía editorial, **no** se instala en `drawable/` ni consume GPU en tiempo de ejecución.
- **Referencias externas:** enlaces de esta tabla. No se descargaron archivos de Haze/Paper Shaders/Cloudy/Compose Unstyled porque no se necesitan bytes ejecutables de terceros para este corte y aumentaría el riesgo de licencia, tamaño y compatibilidad.

## 5. Gates de ingeniería y alcance

- Comprobar con `git diff --check` y `git apply --check` frente a SHA indicado en el README. Ejecutar solo en mirror aislado, **no en `master`**.
- Al disponer de Android SDK / OpenCode con skill `gradle-run`, ejecutar compilaciones Normal/F-Droid, tests JVM/Robolectric, lint y tests de navegación sobre el diff íntegro. Registrar el estado real, sin inferir PASS.
- No atribuir capturas de dispositivos, mediciones de energía o jank: **NOT_AVAILABLE / NOT_MEASURED**.
- No marcar el plan terminado mientras existan rutas/modal sin especificar o validar. Esta biblioteca de referencias cubre las nuevas decisiones AMR-06/07, **no reemplaza** el inventario 15/15.

## 6. Decisiones AMR-08 — Settings, PEQ y rutas secundarias (2026-11-03)

Este corte **no instala dependencias**. Conserva `AeroMaterialStyle` y usa los acabados existentes del patch para distinguir **foco, contenido frecuente, valor técnico y estado observado**. El material no sustituye el dato real ni altera la acción.

| Pantalla / contrato real | Referencia y técnica | Material integrado | Invariante |
|---|---|---|---|
| `SettingsScreen.kt::SettingsAudioQuickActionCard` | [Compose component API](https://github.com/chrisbanes/skills/blob/main/skills/compose-component-design/SKILL.md) | `AeroSurface(Raised,Satin)`, root clickable único | Sin doble Ripple ni cambios en EQ/ruta |
| `SettingsScreen.kt::SettingsSectionCard` | [Material semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics) | `Raised + Anodized`; un borde y micrograno global | Switches mantienen estado de preferencias |
| `screens/settings/SettingsComponents.kt` | [Compose testing](https://github.com/chrisbanes/skills/blob/main/skills/compose-ui-testing-patterns/SKILL.md) | `AeroCard(Plain)` para controles repetidos | Sin textura por cada switch/radio |
| `HeadphoneStudioSection.kt` | [M3 text-field customization](https://developer.android.com/develop/ui/compose/text/user-input) | Campo `OutlinedTextField` con contorno Aero y foco cian | El cian representa *foco*, no medición de DAC |
| `PeqResponseCurve.kt` | [drawWithCache](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) | `Inset`: canal de instrumentos, sin shader vivo | Curva **calculada**, no medición ni DSP confirmado |
| `PlaylistScreen.kt`, `PlaylistDetailScreen.kt` | [Compose component design](https://github.com/chrisbanes/skills/blob/main/skills/compose-component-design/SKILL.md) | `AeroCard(Plain)` en filas densas | No falsas carátulas, no bloquear icon-only |
| `QueueScreen.kt`, `DialogComponents.kt::QueueDialog` | [Compose accessibility semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics) | Resumen `Content+Satin`; fila seleccionada `Content+Satin`, otras `Plain` | Actualidad se basa en índice, no en ID duplicado |
| `AudioRouteScreen.kt` | [Android AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo) | Ruta observada `Raised+Anodized`, lista anunciada `Content+Plain` | Anunciado ≠ observado ≠ bit-perfect |
| `DiagnosticsScreen.kt` | [Compose rendering](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) | Telemetría `Content+Plain` y resaltados de resultado preexistentes | URL/secretos y reportes expurgados no se modifican |
| `SyncScreen.kt` | [Compose component design](https://github.com/chrisbanes/skills/blob/main/skills/compose-component-design/SKILL.md) | Búsqueda `Content+Satin`, progreso `Raised+Anodized` | Buscando ≠ conectado; desconocido ≠ progreso inventado |

**Artefactos de referencia**: `AERO_MATERIALS_ATLAS.png` (ZIP, ilustración original), `aero_graphite_grain.png` (PNG original de 128px en el `.patch`). Este corte no incorpora SVG externos, tipografías de terceros, capturas ni shaders AGSL. Las licencias externas de las tecnologías consultadas están enlazadas en §2, pero **no son licencias de nuevos assets incorporados**. La fuente Inter utilizada por Michi permanece en el repositorio original.

**Chris Banes**: `compose-component-design` para APIs/modifier; `compose-ui-testing-patterns` para pruebas de callback sin DI; `gradle-run` solo al existir un checkout completo con toolchain Android. Estas referencias no prueban que OpenCode las haya invocado en el equipo del usuario.


## 7. Aplicación AMR-80 · Flujos de confianza, sincronización y modal (2026-11-03)

| Componente | Referencia verificable | Técnica adoptada / límites |
|---|---|---|
| `SyncComponents.kt` | [Chris Banes Compose component design](https://github.com/chrisbanes/skills/blob/main/skills/compose-component-design/SKILL.md) | Un único `AeroSurface` por sesión/peer; separación visual entre descubierto y autenticado; estados de producto intactos |
| `SyncWizardScreen.kt` | [Android Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility/semantics) | Contorno de selección del perfil sin crear segundo botón; preflight en canal Inset, no apariencia de descarga terminada |
| `StorageBreakdownBar.kt` | [Compose draw modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) | Canal rebajado Inset, pintura semántica de cinco segmentos conservada |
| `DeviceDetailSheet.kt` | [Compose UI testing patterns](https://github.com/chrisbanes/skills/blob/main/skills/compose-ui-testing-patterns/SKILL.md) | Un target expandible y contenido técnico separado del estado de confianza |
| `TrackActionMenu.kt` | [Chris Banes modifier/layout](https://github.com/chrisbanes/skills/blob/main/skills/compose-component-design/references/modifier-layout.md) | Dialog sobre `Floating+Smoked`, filas Plain con onClick único y operaciones de playlist intactas |
| `ArtistEnrichmentPanel.kt` | [Material accessible surfaces](https://developer.android.com/develop/ui/compose/accessibility) | Candidatos satín sin textura por ítem, URL fuente, licencias CC y MBID sin alteraciones |
| `DialogComponents.kt` | [Compose TextField](https://developer.android.com/develop/ui/compose/text/user-input) | Radio de campo 12dp sobre el popup existente; sin nuevos colores semánticos, sin exponer secretos |

**Recursos externos:** no se descargan imágenes, fuentes, shaders AGSL ni código de terceros. La única textura distribuida sigue siendo la del proyecto, `aero_graphite_grain.png`, original y reproducible. Los enlaces son **documentación de técnica**, no dependencias nuevas. Las skills oficiales no sustituyen la revisión y compilación local en OpenCode.


## AMR-90 · Formularios y modales, referencias originales

- **Oficial Android Material3**: [OutlinedTextFieldDefaults](https://developer.android.com/reference/kotlin/androidx/compose/material3/OutlinedTextFieldDefaults), fuente de `colors` por estado (foco/error/deshabilitado), utilizada en `AeroDialogField.kt`. Se adopta la API, **sin copiar activos**.
- **Oficial Android Jetpack Compose**: [Configurar campos de texto](https://developer.android.com/develop/ui/compose/text/user-input?hl=es), usado para teclado tipo contraseña y manejo de edición.
- **Chris Banes**: [compose-component-design](https://github.com/chrisbanes/skills/blob/main/skills/compose-component-design/SKILL.md) y [compose-ui-testing-patterns](https://github.com/chrisbanes/skills/blob/main/skills/compose-ui-testing-patterns/SKILL.md). Aplicación: helper que no posee estado, `Modifier` opt-in de layout, pruebas de callback con estado controlado. **No son recursos gráficos incluidos**.
- Contrato y matriz: [`AERO_MODAL_FIELD_CONTRACT.md`](AERO_MODAL_FIELD_CONTRACT.md), [`MODAL_COVERAGE_MATRIX.md`](MODAL_COVERAGE_MATRIX.md).
