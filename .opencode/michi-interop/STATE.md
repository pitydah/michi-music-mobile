# Estado MIM

## Checkpoint actual — 2026-11-27

- **Estado global:** LOTE_01_COMPLETADO + CI GREEN en `972795f4` (MIM-01..MIM-08 con código y tests JVM; MIM-09..11 parciales)
- **Avance de implementación:** 83.25% (pesos en `BACKLOG.md`)
- **Verificación:** JVM PASS; CI GitHub run `46064991170` (intento 2) SUCCESS; Android físico/red real NOT_RUN
- **Operación pendiente:** ninguna a medias. El árbol compila y los tests pasan.
- **Siguiente tarea exacta:** MIM-05.5 / MIM-11.2 — UI de enrollment Home.
- **Después:** MIM-07 / MIM-09 / MIM-10.3 — cablear sesión Home compartida.
- **Repo de desarrollo:** `/home/cristian/michi-music-mobile`, `master`, SHA final `972795f4` (implementación `d4082d6f`, evidencia `c8036bc4`, fixes CI `af03ca41` y `972795f4`; baseline `a737eb9f`).
- **Referencias:** Micro `b507b47...` limpio; Link `8dad0fb5...` limpio; pin `8dad0fb5...` verificado.
- **CI:** https://github.com/pitydah/michi-music-mobile/actions/runs/46064991170 — Job A Lint, Job G Instrumented API 36 y Quality Gate verdes. El primer intento de Job F falló por HTTP 429 de Maven Central; el rerun pasó.

## Qué se completó en este lote

- MIM-01: matriz de contratos y 8 tickets upstream (MS-001..004, ML-001..003 + índice).
- MIM-02: criptografía Home canónica + golden vectors (firma byte a byte).
- MIM-03: identidad de servidor y 5 flags AirCat.
- MIM-04: transporte Home HTTPS+SPKI y pruebas de rechazo.
- MIM-05: state machine de enrollment + persistencia cifrada + polling acotado.
- MIM-06: verificación de membership y auth mutua antes de publicar token.
- MIM-08: comandos canónicos, identidad de cola, jump fencing, errores tipados y UI degradada.

## Qué falta (no repetir lo anterior)

1. **MIM-05.5 / MIM-11.2 (UI):** botón “Solicitar membresía” sobre un `AirCatHomeAnchor`; estados Pending/EnrolledOffline/Authenticated; polling; errores tipados. Punto de entrada: `MichiLinkTrustHost` / `AirCatExperienceHost`; coordinador ya en Koin.
2. **MIM-07 (sesión):** autoridad única por Home; single-flight reauth; invalidación/revocación; stale fencing; limpiar token en `MEMBER_REVOKED`; backoff.
3. **MIM-09/10.3:** usar `LinkClient.homeAuthenticatedGet` en `PlaybackSessionManager` y `AirCatOperationCoordinator`; no doble reproducción.
4. **MIM-11.3/11.4:** UX de TLS/revocación, runbook ZimaOS, docs y F-Droid.
5. **Certificación:** E2E red real + Android físico; no certificar sin evidencia.

## Bloqueos

- MS-001 (P0 documental) no bloquea a Mobile ya implementado, pero bloquea a otros consumidores.
- MS-002/MS-003 impiden jump/add-next atómicos; Mobile usa el camino seguro posible.
- MS-004 obliga a tolerar alias de respuesta.
- No hay servidor Micro autorizado vivo ni dispositivo Android conectado en este entorno.

## Reglas para el próximo ejecutor

- Leer `STATE.md`, `BACKLOG.md`, `BASELINE.md`, `DECISIONS.md`, `EVIDENCE.md` antes de editar.
- Verificar HEADs y `git status` de los tres repos; no asumir los SHAs de este checkpoint.
- No commit/push; no tocar Micro/Link; no Gradle directo.
- Actualizar los seis informes del Escritorio al cerrar el siguiente lote.
