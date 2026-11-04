# Michi Aero — AMR-09 / Certificación offline (cerrada)

## Estado real — aplicado y certificado (2026-11-04)

El procedimiento de este documento se ejecutó sobre el repositorio real, no sobre un clon, y el parche quedó integrado.

| Gate | Resultado |
|---|---|
| Parche | `MICHI_AERO_MATERIAL_REFINEMENT.patch`, SHA-256 `9ae5f79a427cd17cc2adf3904fee2bc0d41935c412f3064d7cbd8a957e254c71`, 58 rutas, base `09a4841` |
| Aplicación | `git apply` limpio, `git diff --cached --check` OK, commit `cc307a3` (60 archivos, +1981/−360), merge `--ff-only` y push |
| `scripts/verify_aero_release_contract.py` | **PASS 18/18** — los 3 `BLOCKED` del espejo parcial (navegación, toolchain, ventana Android) resolvieron con checkout completo |
| `scripts/verify_aero_modal_contract.py` | **PASS 16/16** |
| Gradle (vía `gradle-run`) | broad 13 tareas exit 0; **1908 tests JVM, 0 fallos** (app 708 ×2 flavors, core 34, data 159, player 169, michi-link-client 130) |
| Lint | **0 errores** (app 152 warnings, ninguno nuevo de código tras corregir `AeroDialogField`) |
| Instrumentadas | **12/12** en emulador Android 16 / API 36 |
| CI | run `41747960571` sobre `cc307a3`: completed / success, **8/8 jobs** (incluye Job G instrumentado) |

Defectos del paquete corregidos antes de publicar: `testTag` sin import en `SyncedTracksScreen`, imports inválidos de `assertExists` / `onNode` / `assertDoesNotExist` en 8 archivos de test, contrato `aeroM60StackSignal` desactualizado, cuatro consumidores de `MichiSurfaces` deprecado migrados a `aeroGradientStops`, `AeroDialogField` con `LocalWindowInfo`, y plurales concatenados migrados a `pluralStringResource`.

**Nota de estado:** `compileSdk` es 36 en todos los módulos (el paso 2 de abajo menciona `android-35`, vigente cuando se escribió este contrato); `targetSdk` sigue en 35 a propósito.

**Autoridad:** conservar el Aero original, especialmente `AeroSignalRouteStrip` ciruela compacto con pie centrado. El Android físico no es necesario para estos gates.

## Estado verificable

- El diff aplica y revierte en espejo temporal **solo si las comprobaciones de parche realmente pasan**.
- `scripts/verify_aero_release_contract.py` comprueba contratos estáticos; no compila Compose ni sustituye test UI.
- Para la declaración `OFFLINE_READY` deben aprobarse build Normal y F-Droid, lint, pruebas Kotlin/JVM/Robolectric, la matriz de 15 rutas y los 17 modales. Si no existe SDK/checkout integral, el estado es `BLOCKED_BUILD` y el avance de certificación no aumenta.
- El informe de cada prueba debe incluir SHA exacto, comando acotado, resultado, fecha, rutas impactadas y fallo primario (sin secretos ni logs completos).

## Ejecución para OpenCode en CachyOS / Fish

1. Verificar que existe JDK 21, Android SDK con `platforms/android-35/android.jar` y checkout íntegro de `pitydah/michi-music-mobile`; no usar este entorno si no dispone de esas dependencias.
2. Crear un clon **destruible** desde el repositorio local: `git clone --no-hardlinks /ruta/al/repo /tmp/michi-aero-check`; después `git -C /tmp/michi-aero-check checkout --detach 09a48411617daf81d2f93576fd27690da8c7962f`. En ningún momento editar el repositorio original.
3. Comprobar el SHA, aplicar `git apply --check --binary /ruta/MICHI_AERO_MATERIAL_REFINEMENT.patch` **dentro del clon**, luego `git apply --binary` **solo en el clon**. Verificar `git diff --check`, archivos y JSON emitido por `python3 scripts/verify_aero_release_contract.py`, junto con `python3 scripts/verify_aero_modal_contract.py`.
4. Cargar las skills oficiales de Chris Banes, en especial `using-chrisbanes-skills`, `compose-ui-testing-patterns`, `compose-component-design` y **`gradle-run`**. Confirmar invocaciones reales y evitar declarar skills usadas sin registro.
5. Ejecutar por el wrapper oficial `gradle-run` en invocaciones **independientes** (create/run/status/finish), en el clon. Focus tasks: `:app:compileNormalDebugKotlin`, `:app:compileFdroidDebugKotlin`, `:app:testNormalDebugUnitTest`, `:app:testFdroidDebugUnitTest`, `:app:lintNormalDebug`, `:app:lintFdroidDebug`, `:app:assembleNormalDebug`, `:app:assembleFdroidDebug`. Si el proyecto exige más gates, agregarlos. No sustituir pruebas por mera compilación.
6. Cualquier error de compilación, UI, lint o contrato impide `OFFLINE_READY`; corregir **en la copia del parche fuente**, regenerar **el mismo parche acumulativo** y repetir los gates sobre un clon nuevo. No hacer push ni merge.
7. Certificar matrices: cada ruta/moda debe tener `TESTED_OFFLINE` o `BLOCKED` justificado. No convertir `SOURCE_EDITED` en probado por herencia. Registrar si emulador o prueba gráfica no están disponibles como `NOT_AVAILABLE`.
8. Solo si todos los gates offline obligatorios son `PASS`, registrar `OFFLINE_READY / DELIVERED_NOT_APPLIED`, SHA-256 del `.patch` y reporte final; no aplicar a `master` sin autorización explícita posterior.

## Guardrails

- Sin DSD ni claims bit-perfect en Mobile, sin volumen en Now Playing/MiniPlayer, sin pastillas en controles icon-only.
- Sin creación de una estética Aero 2.0; `Plain/Satin/Anodized/Smoked/Inset` son acabados del mismo sistema.
- AirCat solo Devices; no se reintroduce pairing de botón físico en Michi Stream.
- Sin inferir métricas de rendimiento real a partir de tests de código.
- Dependencias de diseño nuevas: 0.

## Evidencia de origen

- Diseño AeroPlay: https://dribbble.com/shots/27588212-AeroPlay-Car-Media-Player
- Skills: https://github.com/chrisbanes/skills
- Compose: https://developer.android.com/develop/ui/compose
- Textura propia y registro de licencias: `docs/design/AERO_ASSET_PROVENANCE.json` y `docs/design/AERO_MATERIAL_REFERENCE_LIBRARY.md`.
