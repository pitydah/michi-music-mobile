# UI4 — Informe de QA física y certificación visual

## Alcance y estado del candidato UI4.11

- Rama: `feat/ui4-aero-motion` (PR #14, **draft**).
- Base UI3 certificada: `6bae6486cf57a542db5517235c716c3ad5f4d1c2`.
- Baseline UI4.11: `7120be603d97de5d31e250e478bcb75821f4b6b8`.
- SHA final: se registra en el PR y en la entrega porque este informe forma parte
  del commit que describe.
- Dispositivo autorizado previamente: Xiaomi 25010PN30G, Android 16/API 36,
  1440×3200, 600 dpi. El serial no se publica.
- Dispositivo durante UI4.11: **no conectado**; `adb devices -l` no enumeró targets.

Este documento separa la evidencia histórica del candidato anterior de la
evidencia todavía necesaria para UI4.11. No se usa un emulador como sustituto de
certificación física.

## Reconciliación de la evidencia anterior

La pasada física anterior sí sigue respaldando estos resultados del baseline:

- Biblioteca Lista/Mosaico/CoverFlow, selección y retorno.
- Dock de una fila y 2×2 con font scale alto.
- Corrección del crash de gradiente en alto contraste.
- Corrección de la cabecera de Biblioteca a 200 %.
- Repetición física del caso `SlotWriter`: 10/10 PASS.

Sin embargo, la conclusión anterior “MiniPlayer ↔ Now Playing sin superficie
duplicada” fue superada por un video posterior del operador. El retorno puede
separar la carátula y hacerla atravesar Inicio. También se informaron parpadeo de
metadatos del MiniPlayer y franja negra superior en Buscar/Dispositivos. Por ello,
la frase anterior “No quedan P0/P1 conocidos” ya no es válida para UI4.

## Defectos UI4.11 y corrección implementada

| Defecto informado | Corrección en el candidato | Evidencia disponible | Evidencia pendiente |
|---|---|---|---|
| P1: parpadeo de metadatos | Fila invariante; eliminado `onTextLayout` y el separador condicional | Tests Compose de ancho compacto, 200 %, endpoint y pista estable pasan | Video físico estable ≥60 s |
| P1: artwork flotante MiniPlayer ↔ Now Playing | Retirado shared element del player; fallback local reversible | Política JVM pasa; harness Android de 20 ciclos compila | Ejecución del test y ≥20 ciclos reales con logs/sesión |
| P1: franja negra en Buscar/Dispositivos | Backdrop full-screen; inset solo en contenido | Estructura igualada con Inicio/Biblioteca; lint/builds pasan | Capturas 100/150/200 % de las cuatro rutas |
| P0/P1: Dynamic Mesh inactivo | Gate falso retirado; build por defecto activo | Tests JVM pasan; instrumentación de paleta/movimiento compila; APK enabled creado | Video, artwork real, lifecycle y rendimiento físico A/B |

No se añadieron sesiones, comandos, autoridad de conexión ni autoridad de audio.
Las transiciones globales M52 siguen desactivadas y el shared artwork de álbum
permanece separado.

## Dynamic Mesh: condiciones del candidato

El fondo queda activo en el build por defecto. Solo usa artwork local permitido,
conserva la última paleta confirmada, cancela la resolución obsoleta y guarda
colores, no bitmaps. El movimiento se detiene en pausa, background, reduced
motion, alto contraste, Power Save y low-RAM. No usa red, sample rate, volumen,
acelerómetro, OpenGL ni tintado del artwork principal.

La instrumentación añadida usa PNG locales generados por el test y firmas de
píxeles para comprobar la paleta más reciente y que pausa/background no mueven
la malla. Solo se ha compilado: sin Android conectado, no existe resultado de
runtime que pueda elevarse a PASS.

## Preparación de la comparación física

Los dos APK A/B se generaron desde el mismo árbol y tienen igual tamaño:

| Configuración | SHA-256 | Tamaño |
|---|---|---:|
| Mesh desactivado | `9d99fc2f311efaf4a7c9c1af01a117192709c8e14e4d3b619c5e05f4e53511f1` | 103247499 bytes |
| Mesh activado | `7c3281fdcbafba3c4b571aca5c965c9fddf09206e7ea1ee4347d0f3aa2b0b168` | 103247499 bytes |

La ejecución pendiente debe usar cinco runs de 60 s por configuración, después
de un warm-up por lado, en orden `A B B A B A A B A B`. Debe conservar todos los
runs y medir framestats/jank, CPU, PSS, batería, temperatura, thermal y Logcat.
Un run sostenido enabled de 30 minutos se hará si el estado del dispositivo lo
permite. No se afirmará 60/90/120 Hz salvo modos realmente observados.

Umbrales acordados: sin crash/ANR/OOM, paleta obsoleta, ilegibilidad, movimiento
en background ni thermal severo; aumento mediano de jank ≤2 puntos porcentuales
y ≤25 % relativo, CPU ≤5 puntos, PSS ≤15 % y temperatura ≤3 °C frente al baseline
contrabalanceado. No hay resultados para estos umbrales todavía.

## Evidencia automatizada disponible

- Tests focales MiniPlayer/política player/Dynamic Mesh: PASS.
- Compilación AndroidTest Normal: PASS.
- Tests de módulos y app Normal/F-Droid: PASS.
- Lint y migración Room focal: PASS.
- Normal/F-Droid debug/release: PASS.
- Aceptación UI4: 47/47 PASS.
- Tests Python UI4: 8/8 PASS.
- Contrato modal: 16/16 PASS.
- Contrato release: 18/18 PASS estático.
- Launcher UI3: PASS.
- Runtime instrumentado: **BLOCKED_HARDWARE**.

## Evidencia física pendiente

1. MiniPlayer sin reflow durante al menos 60 s con pista estable, cambio de
   endpoint y font scale 200 %.
2. Veinte ciclos reales de abrir/volver, navegación rápida, cambio de pista,
   pausa, artwork ausente/asíncrono, background y reduced motion; sin artwork
   flotante, comando o sesión duplicada.
3. Inicio/Biblioteca/Buscar/Dispositivos a 100/150/200 %, con fondo continuo y
   cabeceras dentro de safe areas.
4. Dynamic Mesh con artwork vivo, oscuro, casi monocromático y ausente; cola,
   cambio rápido, pausa/play, apertura/cierre, background, reduced motion, alto
   contraste, Power Save y low-RAM cuando el equipo lo exponga.
5. Comparación A/B y observación térmica/batería sostenida.

## Evidencia histórica privada conservada

Los archivos con biblioteca privada permanecen fuera de Git. Sus hashes
históricos siguen siendo referencias del baseline, no prueba de UI4.11:

| Evidencia histórica | SHA-256 |
|---|---|
| Biblioteca/CoverFlow/álbum/player, 139.5 s | `8a562d9710b68e1f195ec79511ada928ba8d0cd4f317ec02326715e07cd442da` |
| MiniPlayer ↔ Now Playing anterior, 41.4 s | `2f96a24849c3808bfcc33c47356d9953bae3148147dc860f5978f840fa863ee6` |
| Movimiento reducido | `30f2fdfa9ffdca91dc1c407398b82cd315ba91ee56277e53a37c6b8f9b6fb6b5` |
| Alto contraste | `0964fcac09335549d53ba35eef5baa45c6980c560f9950201c84403d46097a5d` |
| Arranque corregido con alto contraste persistido | `892b05cd54c3fb2f5f256bcbd6db6737d420f521f22dc8ed7846fa6eff93a3a6` |
| Biblioteca tras restaurar preferencias | `4251894431cd8948e36aa7a96501f90d4b941e92b858568566ce53408b057a72` |

El video anterior de MiniPlayer no invalida el defecto posterior: solo demuestra
que el fallo no ocurrió en aquella secuencia concreta.

## Revisión y veredictos provisionales

La revisión independiente de solo lectura inspeccionó el diff integrado, tests,
informes, plan y receipt. Emitió `ship` para subir al PR draft y no encontró
defectos P0/P1. Señaló como riesgos no bloqueantes el campo de política hero no
consumido directamente, el posible flake de la instrumentación aún no ejecutada
y las referencias de skills a nivel de sesión. No pudo corroborar los logs
nativos de cada invocación, de modo que ese gate continúa abierto para
`RELEASE_GO`. También falta CI sobre el nuevo head exacto.

- `UI4_CODE_GO`: **GO — revisión independiente `ship`, sin P0/P1**.
- `UI4_AUTOMATED_GO`: **NO-GO — BLOCKED_HARDWARE**.
- `UI4_VISUAL_GO`: **NO-GO — BLOCKED_HARDWARE**.
- `UI4_PHYSICAL_GO`: **NO-GO — BLOCKED_HARDWARE**.
- `DYNAMIC_MESH_ACTIVE`: **GO en código/build; evidencia física bloqueada**.
- `DYNAMIC_MESH_PERFORMANCE_GO`: **NO-GO — BLOCKED_HARDWARE**.
- `UI4_MERGE_GO`: **NO-GO**, pendiente de revisión, QA física y CI exacta.

Estos veredictos no autorizan merge ni release. PR #14 debe permanecer draft.
