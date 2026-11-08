# UI4 — Informe de aceptación técnica

## Candidato UI4.11

- Rama: `feat/ui4-aero-motion`.
- PR: #14, se mantiene en **draft**.
- Base UI3 certificada: `master@6bae6486cf57a542db5517235c716c3ad5f4d1c2`.
- Baseline UI4.11: `7120be603d97de5d31e250e478bcb75821f4b6b8`.
- Contrato original: `/home/cristian/Descargas/plan(1).md` (UI4.10.0).
- Parche original: `/home/cristian/Descargas/ui4.patch`, SHA-256
  `f52920825032213a9526f2874a9565d72ac522a9b2f4558558ce8fc243f7fb15`.
- SHA final: el commit que contiene este informe; el head exacto y sus checks se
  registran en el PR y en la entrega porque el commit no puede contener su propio SHA.

UI4.11 corrige tres defectos informados después de la aceptación anterior. La
afirmación previa de que MiniPlayer ↔ Now Playing no presentaba ghosting quedó
invalidada por el video posterior del operador. Este informe no conserva aquella
conclusión como evidencia vigente.

## Integración y alcance de las 57 rutas

`git apply --numstat /home/cristian/Descargas/ui4.patch` enumera exactamente
**57 rutas**: 39 de producción, 14 tests JVM, 1 test Android, 2 herramientas y
1 test de herramienta. Las 57 están representadas en el commit de integración
`236ba8f`. Ese commit tiene 60 rutas porque añadió tres artefactos de integración
que no pertenecían al parche: `.gitignore`, este informe y
`tools/tests/test_ui4_acceptance.py`.

La presencia de una ruta no se usa como prueba de funcionamiento. La clasificación
por entrega es la siguiente:

| Entrega | Estado UI4.11 | Evidencia o límite |
|---|---|---|
| UI4.1 — microfeedback/tokens | Integrada, activa y ejercitada | Tests de política y suite JVM |
| UI4.2a — glyphs/listas/estado | Integrada, activa y ejercitada | Suite JVM, lint y builds |
| UI4.2b — mosaico/detalle | Integrada, activa y ejercitada | Tests UI3/UI4 y regresión de builds |
| UI4.2c — shared artwork de álbum | Integrada y activa | Scopes limitados a Library/Album; transición física histórica conservada |
| UI4.3 — MiniPlayer/Now Playing | Defecto corregido en código; validación física pendiente | Se retiró el shared element del player y se usa fallback local; test de 20 ciclos compila, pero no se ejecutó sin dispositivo |
| UI4.4 — artistas | Parcial y diferida | Entrada/reordenación integradas; shared-bounds de retrato sigue diferido por identidad visual no demostrada |
| UI4.5 — Remote/AirCat/Link/Stream | Parcial y ejercitada sobre estados observados | No acredita handshake, provisión ni sesión E2E |
| UI4.6 — Signal Truth | Parcial y ejercitada | Anima evidencia observada; no acredita formato físico ni bit-perfect |
| UI4.7 — Dynamic Mesh | Integrada y **activa por defecto en build** | Paleta local, caché, cancelación y lifecycle cubiertos en código/tests; ejecución Android y rendimiento físico pendientes |
| UI4.8 — calidad/rendimiento | Parcial | JVM, lint, compilación AndroidTest y builds pasan; runtime Android y A/B físico bloqueados |
| UI4.9/UI4.10 — consolidación/rebase | Integrada | Son revisiones acumulativas del contrato/parche, no una función independiente que pueda declararse activa |

No se declara completado el shared-bounds de artistas ni el E2E del ecosistema.
Implementarlos aquí ampliaría identidad o protocolos fuera del alcance correctivo.

## Correcciones UI4.11

### MiniPlayer: metadatos estables

Se eliminó la realimentación `onTextLayout` → `artistTruncated` → separador
condicional. La fila conserva siempre los slots de artista, indicador y endpoint;
artista y endpoint usan ellipsis. Se preservan la semántica española combinada
del target de apertura y los controles de transporte independientes de 48 dp.

Los tests Compose cubren artista largo en 280 dp, font scale 2.0, cambio de
endpoint con pista estable y cambio de progreso/play sin cambio de identidad.

### MiniPlayer ↔ Now Playing: fallback local

El artwork del player ya no usa `sharedElement`. El MiniPlayer conserva una
entrada/salida local finita; el artwork solo hace crossfade cuando cambia la
identidad de pista; Now Playing conserva su hero local. Las transiciones globales
M52 siguen en `None` y el shared artwork de álbum permanece intacto.

La política pura fija `shareArtwork=false`. El nuevo test Android ejecuta en su
harness 20 ciclos de apertura/retorno y comprueba que navegar no dispara Play o
Next. Su fuente compila; la ejecución sobre Android y la prueba de sesión real
siguen bloqueadas por ausencia de dispositivo.

### Buscar y Dispositivos: área segura

`statusBarsPadding()` pasó del Box que contiene el backdrop a la Column de
contenido, igual que en Inicio y Biblioteca. El fondo ocupa el área de status bar
sin añadir espaciado fijo ni un segundo inset de Scaffold.

### Aero Dynamic Mesh: activación real en build

Se retiró `AeroDynamicMeshPerformanceCertified=false`. El valor generado
`BuildConfig.AERO_DYNAMIC_MESH_ENABLED` toma por defecto
`michi.dynamicMeshEnabled=true`. El valor `false` existe solo para construir el
baseline A/B desde el mismo árbol; no es una preferencia de usuario.

Se conservan las fuentes locales, la paleta oscura, el LRU de colores, la
cancelación de solicitudes obsoletas, pausa/background, reduced motion, alto
contraste, Power Save y low-RAM. No se añadió red, análisis de audio, OpenGL ni
otra superficie. La instrumentación nueva genera artwork local y comprueba por
capturas de píxeles la paleta más reciente y la inmovilidad en pausa/background;
la fuente compila, pero no se ejecutó por falta de dispositivo.

## Evidencia automatizada local

Todas las tareas Gradle iniciadas por el agente usaron `gradle-run`. Un intento
agregado fue interrumpido externamente a los 120 s sin fingerprint de fallo; la
matriz se dividió y cada parte terminó correctamente.

| Gate | Resultado |
|---|---|
| Tests focales MiniPlayer/política player/Dynamic Mesh | PASS |
| Compilación `NormalDebugAndroidTestKotlin` | PASS |
| Tests `buildSrc` | PASS |
| Tests de `:core`, `:data`, `:player`, `:michi-link-client`, app Normal/F-Droid + compilación/assembly AndroidTest | PASS |
| `lint` + `Migration3To4Test` | PASS |
| Normal/F-Droid debug y release | PASS, 4 APK |
| `tools/ui4_acceptance.py` | **47/47 PASS** |
| `tools/tests/test_ui4_*.py` | **8/8 PASS** |
| Contrato modal Aero | **16/16 PASS** |
| Contrato release Aero | **18/18 PASS** estático; Android probado separadamente |
| Verificador launcher UI3 | PASS |
| Receipt de skills | Formato PASS; contenido pendiente de corroboración independiente |
| Ejecución Android instrumentada | **BLOCKED_HARDWARE** |

La suite instrumentada no se declara aprobada por compilar. El CI del nuevo head
también permanece pendiente hasta crear y subir el commit final.

## Artefactos A/B preparados

Se construyeron dos APK Normal debug desde el mismo árbol, cambiando únicamente
la propiedad de Dynamic Mesh. Esto demuestra que el seam de build funciona; no
demuestra rendimiento.

| Configuración | SHA-256 | Tamaño |
|---|---|---:|
| `michi.dynamicMeshEnabled=false` | `9d99fc2f311efaf4a7c9c1af01a117192709c8e14e4d3b619c5e05f4e53511f1` | 103247499 bytes |
| `michi.dynamicMeshEnabled=true` | `7c3281fdcbafba3c4b571aca5c965c9fddf09206e7ea1ee4347d0f3aa2b0b168` | 103247499 bytes |

Si cambia código después de la revisión independiente, estos artefactos se
invalidan y deben regenerarse antes de una comparación física.

## Límites actuales

- No hay dispositivo en `adb devices -l`.
- No se ejecutaron los tests Android nuevos ni la suite conectada.
- No se observaron físicamente los tres defectos corregidos en este candidato.
- No se realizó la comparación A/B de jank, CPU, PSS, temperatura o batería.
- No se afirma cobertura 60/90/120 Hz; el hardware anterior solo observó 120 Hz.
- Revisión independiente y CI del nuevo head: pendientes.

## Veredictos provisionales

- `UI4_CODE_GO`: **GO local**, pendiente de revisión independiente.
- `UI4_AUTOMATED_GO`: **NO-GO — BLOCKED_HARDWARE**; los gates locales disponibles pasan, pero falta runtime Android.
- `UI4_VISUAL_GO`: **NO-GO — BLOCKED_HARDWARE**.
- `UI4_PHYSICAL_GO`: **NO-GO — BLOCKED_HARDWARE**.
- `DYNAMIC_MESH_ACTIVE`: **GO en código/build por defecto; validación física bloqueada**.
- `DYNAMIC_MESH_PERFORMANCE_GO`: **NO-GO — BLOCKED_HARDWARE**.
- `UI4_MERGE_GO`: **NO-GO**; faltan revisión independiente, evidencia física y CI exacta del nuevo head.

Estos estados no autorizan merge ni release. PR #14 debe permanecer draft.
