# Evidencia

## Lote 2026-11-27 — MIM-01..08 (parcial 09..11)

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
