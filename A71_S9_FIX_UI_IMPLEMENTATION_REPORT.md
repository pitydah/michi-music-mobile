# A71-S9 · FIX_UI-05 — Informe de implementación

**Repositorio:** `pitydah/michi-music-mobile`
**Rama:** `feat/aircat-a71-experience`
**Base:** `2085f7ef1de9b1887aec6824bb9d0aa7f06c56d4`
**Fecha:** 2026-11-19
**Estado:** integrado y compilado; certificación Android parcial. `RELEASE = NO-GO`.

## 1. Fuentes y aplicación

- `fix_ui.md` (97.508 B, SHA-256 `40ba0d6078e996fa8c8471dff0faffc18b3228dc7b168fa27ab6c644615bcd14`).
- `fix_ui.patch` (94.209 B, SHA-256 `c7e8109cd31d612bcba2abffb8eb53fc268f3eda413daab8472ea8f59e65f7bd`).
- `fix_ui_prepare.py` (8.128 B, SHA-256 `19dc7a4973b4d905d18a996554dd93ffcd926e07a0726c18c42b00c3c0890126`).
- Dry-run del preparador: `PASS: frozen HEAD, tracked clean tree, exact hunks, standard 3-context patch`; 16 archivos; SHA-256 contextual `16fb642641613b927b9491424671e24c1aa5ac07dea07e7b48516197c02a7dae`.
- `--apply` una sola vez: PASS; `git diff --check` exit 0. Sin `--reject`, `--3way`, `--unidiff-zero`, reset destructivo ni force push.

## 2. Archivos integrados

Parche (16): `AirCatStorageRequirement.kt`, `AirCatStorageRequirementTest.kt`, `AirCatTransferClient.kt`, `AirCatOperationCoordinator.kt`, `AirCatAeroChrome.kt`, `AirCatCheckpointTruth.kt`, `AirCatCheckpointTruthTest.kt`, `AirCatExperienceContent.kt`, `AirCatExperienceController.kt`, `AirCatExperienceHost.kt`, `AirCatExperienceModels.kt`, `AirCatOperationEngine.kt`, `SyncViewModel.kt`, `DialogComponents.kt`, `DevicesScreen.kt`, `AirCatExperienceControllerTest.kt`.

Ajustes de verificación: `AirCatExperienceOnDeviceCaptureTest.kt`, `AirCatExperienceCatalogueScaleTest.kt`, `AirCatExperienceContentTest.kt`, y los tres tests de búsqueda ya corregidos en la base.

## 3. Correcciones P0/P1

- **Propiedad y estado:** `OpenActiveTransfer` reabre TRANSFER también cuando `starting = true` (Confirm deja `starting` antes del primer evento del motor). Cerrar detalles ya no pierde la observación.
- **Progreso correlacionado:** solo se aceptan eventos con `transferId == expectedTransferId` y la misma generación; un evento global ajeno no se adopta.
- **COMPLETED verificable:** exige totales de pistas y bytes coherentes, índice final, `checksumVerified` y `libraryCommitted`. Un callback `ok` sin comprobante produce `UNCERTIFIED`, nunca éxito.
- **Checkpoints:** `AirCatCheckpointTruth` establece la proyección canónica de origen; `AirCatStorageRequirement` centraliza la reserva (incluidos los 100 MiB) y la usan cliente y coordinador.
- **Navegación única:** todas las entradas abren el mismo Hub; cero equipos muestra el estado vacío del Hub, no un diálogo «sin destinos».
- **Home/QR:** el escáner usa `QrScannerPurpose.HOME_ADMIN` y solo acepta `michi.aircat.home.enrollment.v1`; el pairing genérico no se desvía a Home.
- **UI Aero:** hub con hero, CTA «Recibir música» y estado vacío accionable; origen, catálogo, revisión, transferencia, resultado y confianza con superficies Aero y tokens existentes.

## 4. Defectos adicionales encontrados y corregidos

1. Tres tests del parche estaban desactualizados (copy del catálogo vacío, título de confianza y política de consulta vacía); se alinearon con el contrato real, sin debilitar assertions.
2. Catálogo a 360 dp/2.0x: el footer compuesto recibía 0 dp y el botón «Continuar» quedaba invisible e intocable. Se refundió el catálogo en una sola `LazyColumn` con cabecera, lista y footer desplazables.
3. «Limpiar» se recortaba a 2.0x: la fila de acciones del catálogo pasó a `FlowRow`.
4. «Explorar música» se truncaba con elipsis a 2.0x: el título del header Aero admite 2 líneas.
5. Fixtures de captura en bytes mostraban «0 MB»: se elevaron a 120 MB/240 MB y 4 GiB de espacio para evidencia representativa.
6. `Icons.Filled.ArrowBack` deprecado → `Icons.AutoMirrored.Filled.ArrowBack`; opt-in de coroutines en el test del controlador.

## 5. Pruebas

| Tarea | Resultado |
|---|---|
| `:app:testNormalDebugUnitTest` | 2272 tests, 0 fallos, 0 errores, 0 omitidos |
| `:app:testFdroidDebugUnitTest` | 2272 tests, 0 fallos, 0 errores, 0 omitidos |
| `:aircat-transfer:testDebugUnitTest` | 414 tests, 0 fallos |
| `:michi-link-client:testDebugUnitTest` | 205 tests, 0 fallos |
| `:app:compileNormalDebugKotlin`, `:app:compileNormalDebugUnitTestKotlin`, `:app:compileFdroidDebugAndroidTestKotlin`, `:app:compileNormalDebugAndroidTestKotlin` | exit 0 |
| `:app:assembleNormalDebug`, `:app:assembleNormalDebugAndroidTest` | exit 0 |

Gradle mediante `gradle-run` (workflow `5efe4703911239119ce50eb69b9a45fd`, runs 0001–0012).

Instrumentadas: `AirCatExperienceOnDeviceCaptureTest` en API 36 (`emulator-5554`), `OK (1 test)`, 128 capturas. La suite instrumentada completa en esta rama y el lint de esta rama quedan `NOT_RUN`.

## 6. Capturas

- 128 PNG: 8 superficies × 4 anchos (360/393/430/600 dp) × 4 escalas (1.0/1.3/1.6/2.0).
- Ruta local: `/tmp/opencode/a71s9/shots5/a71-s7/` (128 PNG + `manifest.tsv`).
- Inspeccionadas visualmente: `hub_w393_fs1.0`, `catalogue_w360_fs2.0`, `transfer_w393_fs1.0`, `trust_w393_fs1.6`.
- La pantalla de QR administrativo no se capturó: es un diálogo con cámara en ventana aparte; queda pendiente.

## 7. Gates

- `S9_ANDROID_CODE_INTEGRATED`: 16 rutas del parche + ajustes de test/harness integrados.
- `S9_JVM_TESTS = PASS`.
- `S9_ANDROID_INSTRUMENTED = PARTIAL` (capturas OK; suite completa `NOT_RUN`).
- `S9_VISUAL_CERT = 4/9` (4 de 8 superficies capturadas inspeccionadas; QR 0/1).
- `S9_NEW_CI = NOT_RUN` al redactar este informe.
- `EXTERNAL_INTEROP = BLOCKED_EXTERNAL`.
- `PHYSICAL_E2E = NOT_RUN`.
- `RELEASE = NO-GO`.

Estimación de código integrado por sprint (no certificación): S9.1 70 %, S9.2 80 %, S9.3 65 %, S9.4 70 %, S9.5 65 %, S9.6 35 %.

## 8. Bloqueantes restantes

- Suite instrumentada completa en API 36 sobre esta rama.
- Captura e inspección del QR administrativo y de las superficies restantes.
- Pruebas físicas end-to-end con un par Michi real.
- Nueva CI y aceptación visual del usuario.

## 9. Confirmaciones

- No se modificaron Media3, DSP, ReplayGain, motores de audio ni las vistas/identidades de Géneros UI7.
- No se alteraron los maestros de Google Drive.
- No hay secretos ni artefactos `build/` en el cambio.
- El PR #22 permanece sin merge.
