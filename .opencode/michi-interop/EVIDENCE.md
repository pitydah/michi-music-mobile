# Evidencia

## Lote 2026-11-27 — MIM-01..08 (parcial 09..11)

- **Commits publicados:** `d4082d6f` (implementación MIM), `c8036bc4` (evidencia), `af03ca41` (fix checker D57), `972795f4` (fix probes D57).
- **Baseline anterior:** `a737eb9f`.
- **SHA de código+CI verificado:** `f8a37cf3`.
- **CI:** GitHub Actions run `46068074299` (head `f8a37cf3`), intento 3 **SUCCESS** (Job A Lint, Job B/C unit, Job D migraciones, Job E/F builds, Job G instrumentado API 36, Job H release gate, Quality Gate). Los intentos 1 y 2 de Job F fallaron por HTTP 429 de Maven Central y pasaron al tercer intento; no fue un fallo de código. Run previo `46064991170` (`972795f4`) también verde.

### Baseline

- Mobile `a737eb9f5675e9cd3d4022446096ba240b0b4bfe` (`master`).
- Micro `b507b47ebd9dec17740ca26d9da8ddb8b045735b` (`refactor/killcritic-interop-r1`), limpio.
- Link `8dad0fb51128df98422bd74968e75f0ae8f85ccf` (`main`), limpio.
- Pin `vendor/michi-link` = `8dad0fb5` (coincide).
- Sin drift al cierre.

### Tooling

- Skills OpenCode usados: `using-chrisbanes-skills`, `to-plan`, `gradle-run`, `kotlin-api-design`, `kotlin-concurrency-and-flow`, `tdd`.
- Todos los comandos Gradle se ejecutaron con `gradle-run` (workflow `6ee55d197bec82d07c4be05028a3dd15`). Cero `./gradlew` directo.
- `git diff --check` sin errores.

### Comandos y resultados

| Comando | Resultado |
|---|---|
| `:michi-link-client:testDebugUnitTest` | PASS — 229 tests, 0 fallos |
| `:app:testNormalDebugUnitTest` | PASS — 2930 tests, 0 fallos |
| `:app:testFdroidDebugUnitTest` | PASS — 2930 tests, 0 fallos |
| `:app:lintNormalDebug` | PASS — BUILD SUCCESSFUL |
| `test` (repo-wide) | PASS — todas las unidades de `:core`, `:data`, `:player`, `:michi-link-client`, `:aircat-transfer`, `:app` |
| `python3 tools/check_settings_architecture.py` | PASS — gate D57 corregido (comentarios ignorados) |
| `:app:connectedNormalDebugAndroidTest` filtrado `ArtistEmbeddedTagsUiTest` | PASS en emulador API 36 |
| `:app:connectedNormalDebugAndroidTest` filtrado `K48R12SurfacePerformanceProbeTest` | PASS (2 tests) en emulador API 36 |
| CI run `46068074299` intento 3 | PASS — todos los jobs verdes |
| `:core:testDebugUnitTest --tests AirCatCapabilityPolicyTest` | PASS |
| `--tests HomeAuthCryptoTest` | PASS — 10 tests |
| `--tests HomeQueueContractTest` | PASS — 14 tests |
| `--tests HomeEnrollmentCoordinatorTest` | PASS — 7 tests |

Conteos obtenidos de `build/test-results/*.xml` (no estimados).

### Archivos nuevos (Mobile)

- `michi-link-client/src/main/java/org/michimusic/link/home/HomeAuthCrypto.kt`
- `michi-link-client/src/main/java/org/michimusic/link/home/HomeAuthDto.kt`
- `michi-link-client/src/main/java/org/michimusic/link/identity/MichiSigner.kt`
- `michi-link-client/src/main/java/org/michimusic/link/identity/ServerIdentityPolicy.kt`
- `michi-link-client/src/test/java/org/michimusic/link/home/HomeAuthCryptoTest.kt`
- `michi-link-client/src/test/java/org/michimusic/link/HomeQueueContractTest.kt`
- `app/src/main/java/org/michimusic/mobile/devices/home/HomeEnrollmentCoordinator.kt`
- `app/src/main/java/org/michimusic/mobile/devices/home/HomeMembershipStore.kt`
- `app/src/main/java/org/michimusic/mobile/devices/home/HomeMembershipPersistence.kt`
- `app/src/test/java/org/michimusic/mobile/devices/home/HomeEnrollmentCoordinatorTest.kt`

### Archivos modificados (Mobile, sin commit)

28 archivos versionados, +940/−230; además archivos nuevos sin seguimiento listados abajo (ver `git diff --stat` al cierre). Incluye `LinkClient.kt`, `QueueDto.kt`, `PlaybackSessionManager.kt`, `PlaybackSessionState.kt`, `QueueScreen.kt`, `SyncModule.kt`, `AirCatCapabilityPolicy.kt`, reglas de backup y tests.

### Evidencia criptográfica

Firmas comparadas byte a byte con `michi-link/tests/vectors/interop/micro-mobile-interop-vectors.json`:

- enrollment: `aPjc7KhYzS5WiexMph2QLGAtZkGEmZDw62i1Xip203XgSYlZpaFcsHxkZ_5iBp25yatLRlQ4T0ptaguS925FDQ`
- device auth: `BJtkvwhY-jRK_uM1wKGOSWZuG7ocJ9eukBdrkHYS5vSlTWCQEkG_1qNvv9Ni8dq03mWQmcrlukbmONrQ7oUjAg`
- server confirm: `9dc8fk0sVb3TdZdqElA72asiPDhAW8tDcz7HbnlnLtxoV4-K_SUvEp6GCca5yotTK7TewjYeP_0FBb9W2wLjAw`

### NO-RUN

- Android instrumentado: sin emulador/dispositivo.
- E2E red real/ZimaOS: sin servidor autorizado.
- TLS real, AirCat real, handoff físico, multicast real: NOT_RUN.

### Referencias de solo lectura

- Micro y Link permanecen limpios y en el mismo HEAD.
- No se ejecutó build, fetch, checkout ni escritura en ellos.

## LOTE 02 — 2026-11-27 (working tree, sin commit)

- **Base publicada:** `64db675e` (CI `46071123540` verde). LOTE 02 no se ha commiteado.
- **Skills cargados en esta sesión:** `using-chrisbanes-skills`, `to-plan`, `deliver-spec`, `kotlin-concurrency-and-flow`, `kotlin-api-design`, `kotlin-control-flow`, `compose-state-and-effects`, `compose-component-design`, `compose-ui-testing-patterns`, `gradle-run`, `tdd`, `systematic-debugging`, `code-review`.
- **Workflow gradle-run:** `730b654f01c862f8e98ad6face0b80c4`; cero `./gradlew` directo.

### Comandos y resultados

| Comando | Resultado |
|---|---|
| `:michi-link-client:testDebugUnitTest` | PASS — 237 tests |
| `:core:testDebugUnitTest` | PASS — 1095 tests |
| `:aircat-transfer:testDebugUnitTest` | PASS — 414 tests |
| `:player:testDebugUnitTest` | PASS — 234 tests |
| `:app:testNormalDebugUnitTest` | PASS — 2942 tests |
| `:app:testFdroidDebugUnitTest` | PASS — 2942 tests |
| `:app:lintNormalDebug` | PASS |
| `python3 tools/check_settings_architecture.py` | PASS |
| `git diff --check` | PASS |
| Instrumentado LOTE 02 | **NOT_RUN** (emulador disponible, pero no se ejecutó la suite conectada completa en este lote) |

### Evidencia de MIM-07

- `HomeSessionAuthorityTest`: single-flight (dos llamadas concurrentes, un challenge), TTL/lease, `MEMBER_REVOKED` limpia membership y no reintenta, `REVOCATION_STORE_UNAVAILABLE` entra en BACKOFF y no genera tormenta, `invalidate` permite reauth con nueva generación.
- `LinkClient.acceptVerifiedHomeSession` sólo acepta `VerifiedHomeSession` producido por `HomeServerSessionVerifier`; ya no hay instalación por prefijo. `isHomeSessionValid` exige contexto Home y TTL.
- `EventClient`: replay 0 y `CancellationException` rethrow.

### Evidencia de MIM-05.5

- `HomeEnrollmentService` desacopla el controlador de la implementación; `MichiLinkTrustControllerTest` prueba request explícito, pending, approved→ENROLLED_OFFLINE (no conectado) y ausencia de coordinador.
- `MichiLinkTrustHost`/`DevicesScreen` reciben coordinador y autoridad por Koin; el QR existente sigue siendo el ancla.

### Evidencia de MIM-03/MIM-08/MIM-10

- `ServerFeatureTruthTest`: ausente ≠ `{}` ≠ `false`; `mergeServerFeatures` conserva histórico sólo si falta el campo.
- `PlaybackStateTruthTest`: lifecycle `stopped`/`ended`/`failed` no se convierte en `paused`; `generation_id` modelado.
- `RemoteCommandAuthorityTest`: delete/jump/reorder por `queue_item_id` con pistas duplicadas; postcondición exacta; `CONFLICT` cuando la instancia cambió.
- `AirCatOperationCoordinator` resuelve la sesión Home antes de `deviceToken`; falla cerrado sin lease.
- `HomeQueueContractTest`: `addQueueItems` conserva duplicados intencionales.

### NO-RUN / límites

- Suite instrumentada completa y pruebas de UI nuevas en emulador: **NOT_RUN** en este lote.
- E2E red real Micro Server/ZimaOS, TLS real, AirCat real, revocación real y handoff físico: **NOT_RUN**.
- Micro/Link no se modificaron; siguen limpios.


### DRIFT externo al cierre de LOTE 02

- Link local `main` pasó de `8dad0fb51128df98422bd74968e75f0ae8f85ccf` a `ea76ee0e3ffabf23c921db71454cd6294b7ae960` (R1.5, commits `7060b61` + `ea76ee0`). Árbol limpio.
- Micro HEAD sigue `b507b47ebd9dec17740ca26d9da8ddb8b045735b`, pero al cierre su working tree tiene 24 entradas R1.5 (docs/handoff, tests `interop_r1_5_*` y `vendor/michi-link` → `ea76ee0e`). No fue escrito por MIM.
- R1.5 schemas incluyen `queue_item_id`, `expected_queue_version`, `server_boot_id`, `position: next/after_item_id`, `desktop` en membership, roster completo, `lifecycle`/`generation_id`, `repeat_mode`/`shuffle_enabled`, `previous_volume` y `unmute` con volumen previo.
- **No se adopta R1.5 en Mobile** hasta que Micro commitee esos cambios y fije el pin; LOTE 02 sigue certificado contra R1.4 `8dad0fb5`.

## LOTE 03 — 2026-11-27 (commit `49a0109c`)

- **Workflow gradle-run:** `4b4e7dba90c9bcaab4a40a308eef50e8`; cero `./gradlew` directo.
- **Skills cargados:** `using-chrisbanes-skills`, `to-plan`, `deliver-spec`, `kotlin-concurrency-and-flow`, `kotlin-api-design`, `kotlin-control-flow`, `compose-state-and-effects`, `compose-component-design`, `compose-ui-testing-patterns`, `gradle-run`, `tdd`, `systematic-debugging`, `code-review`.

### Comandos y resultados

| Comando | Resultado |
|---|---|
| `:michi-link-client:testDebugUnitTest` | PASS — 237 tests |
| `:core:testDebugUnitTest` | PASS — 1095 |
| `:aircat-transfer:testDebugUnitTest` | PASS — 414 |
| `:player:testDebugUnitTest` | PASS — 234 |
| `:app:testNormalDebugUnitTest` | PASS — 2948 |
| `:app:testFdroidDebugUnitTest` | PASS — 2948 |
| `:app:lintNormalDebug` | PASS |
| `python3 tools/check_settings_architecture.py` | PASS |
| `git diff --check` | PASS |
| Instrumentado LOTE 03 | **NOT_RUN** |

### Evidencia MIM-03

- `DeviceAccessState` en `core/.../DeviceAccessState.kt`; `UnifiedDevice.accessState`.
- `LinkDeviceRosterProjectorTest`: legacy reachable ≠ Home authorized, Home enrolled sin lease ≠ authenticated, Home authenticated, discovery verified only identity, unsigned none, revoked terminal.
- `AirCatEligibilityPolicy` y `DeviceActionResolver` bloquean AirCat si `homeAuthorized && !authenticated`.
- `DeviceUiModels` distingue `HOME_ENROLLED`/`HOME_AUTHENTICATED`; `DeviceDetailSheet` muestra badge.
- `LinkDiscovery` deduplica por `michiId` verificado; auditoría en `docs/devices/DISCOVERY_AUTH_STATE_AUDIT.md` (mDNS/Tailscale no implementados).

### Evidencia MIM-09/MIM-07/MIM-11

- Handoff `PlaybackSessionManager`: confirma `playing`, track e índice observados antes de pausar el origen; normaliza índice -1; el audio local sigue si no confirma.
- Reauth Home desde el polling (`refreshRemotePlayback`/`refreshRemoteQueue`) y `EventClient` con `tokenProvider` que relee el bearer en cada reconnect.
- Runbook `docs/runbooks/HOME_ENROLLMENT.md`; badges Home en UI.

### DRIFT

- Link `main` `ea76ee0e` (R1.5, limpio); Micro HEAD `b507b47` con 12 archivos R1.5 y vendor a `ea76ee0e` (sin commit). No adoptado; Mobile sigue R1.4 `8dad0fb5`.

## CI remoto y corrección — 2026-11-28

- **Push:** `64db675e..1edbf73f` a `origin/master`, sin force; no hubo rechazo de protección.
- **Run `46099034554` (head `1edbf73f`):**
  - Attempt 1: PASS en Job A (lint), C (fdroid), D (Room), E (build normal), F (build fdroid) y H (UI7 gate). FAIL en Job B (un test de `PlaybackSessionManagerTest` que pasa en aislamiento y pasó en el rerun) y en Job G.
  - Attempt 2 (`--failed`): Job B PASS. Job G FAIL de forma determinista en `MetadataEditorLibraryReopenInstrumentedTest`.
  - Quality Gate FAIL por Job G.
- **Causa raíz (Job G):** `NoDefinitionFoundException: No definition found for type 'HomeMembershipPersistence'` al crear `PlaybackSessionManager` desde `SyncModule.kt:65`. `HomeEnrollmentCoordinator` y `HomeSessionAuthority` ya dependían de la interfaz, pero el módulo sólo registraba el `HomeMembershipStore` concreto. Era un fallo de arranque real, no un test defectuoso.
- **Fix:** `single<HomeMembershipPersistence> { get<HomeMembershipStore>() }` en `SyncModule`; una sola instancia del store. Ningún test fue modificado, debilitado ni eliminado.
- **Validación local (mediaStore limpio, emulador API 36):**
  - `:app:connectedNormalDebugAndroidTest` PASS — 232 tests, 0 fallos, 25 skipped, 4m45s.
  - El test original de Metadata Editor pasa en 15 s ejecutado solo.
  - El "track missing" observado en el AVD era estado residual: 31 filas de audio de ejecuciones previas en `MediaStore`. Al vaciarlas, el mismo test y toda la suite pasan; no es una regresión de producto.
- **Re-run de CI:** pendiente sobre el SHA de esta corrección.
