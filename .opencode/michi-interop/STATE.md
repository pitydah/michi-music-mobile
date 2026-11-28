# Estado MIM

## Checkpoint actual — LOTE 03 publicado (2026-11-28)

- **Estado global:** LOTE_02+03 en `49a0109c`, evidencia en `1edbf73f`, corrección de CI en `6003e582`; los tres publicados en `origin/master` con run `46104446846` verde.
- **Avance de implementación:** 90.0% (ver `BACKLOG.md`); no es certificación.
- **Verificación:** JVM de todos los módulos PASS (237 link + 1095 core + 414 aircat + 234 player + 2948 normal + 2948 fdroid); lint PASS; arquitectura PASS; instrumentado normal **PASS en local y en CI** (Job G, 232 tests, 0 fallos, 25 skipped, API 36); red real/físico **NOT_RUN**.
- **Operación pendiente:** ninguna a medias; el árbol compila, las suites JVM pasan, lint/arquitectura pasan y el instrumentado normal pasa en local y en CI. Red real/físico NO-RUN.
- **Siguiente tarea exacta:** MIM-10 — E2E AirCat real (manifest/grant/download, revocación durante transferencia) si hay servidor autorizado; en su defecto MIM-11 estados restantes y MIM-09 recuperación/rollback.
- **Después:** MIM-09 recuperación completa/rollback y MIM-03 mDNS/roster si se autoriza.
- **Repo:** `/home/cristian/michi-music-mobile`, `master`, `6003e582` publicado (implementación `49a0109c` + evidencia `1edbf73f` + fix CI).
- **Referencias:** Micro HEAD `b507b47...` pero working tree con 24 entradas R1.5, incluidos docs y tests nuevos (no tocado); Link `main` avanzó a `ea76ee0e` (R1.5) y está limpio. DRIFT registrado; el código sigue contra el pin R1.4 `8dad0fb5`. No adoptar R1.5 hasta commit/pin upstream.
- **CI:** run `46099034554` (head `1edbf73f`) **FAIL** por el binding Koin ausente; run `46104446846` (head `6003e582`, fix) **SUCCESS** con los 9 jobs verdes (A lint, B unit normal, C unit fdroid, D Room, E build normal, F build fdroid, G instrumentado API 36, H UI7 gate, Quality Gate).

## Corrección post-publicación (2026-11-28)

- **Causa raíz:** `HomeEnrollmentCoordinator` y `HomeSessionAuthority` (LOTE 02/03) resuelven `HomeMembershipPersistence`, pero `SyncModule` sólo registraba `HomeMembershipStore`. La app y los tests instrumentados fallaban al crear `PlaybackSessionManager` con `NoDefinitionFoundException`.
- **Fix:** binding `single<HomeMembershipPersistence> { get<HomeMembershipStore>() }`; una sola instancia del store.
- **Validación local:** `:app:connectedNormalDebugAndroidTest` PASS — 232 tests, 0 fallos, 25 skipped. El "track missing" que apareció en el AVD local era estado residual (31 filas viejas en MediaStore); con MediaStore limpio el test original pasa en 15 s y no se modificó ningún test.
- **CI final:** run `46104446846` (head `6003e582`) **SUCCESS** — 9/9 jobs verdes, incluido Job G (instrumentado normal API 36) y el Quality Gate. Este commit de evidencia sólo documenta ese resultado; no cambia código.

## Qué se completó en LOTE 03

- MIM-03: `DeviceAccessState` (availability/identity/Home/authenticated/capabilities/authorization), proyección en roster/UI, gating AirCat, dedup por `michiId`, auditoría Whisker/mDNS/Tailscale.
- MIM-09: handoff local→remote confirma track/índice/playing observados antes de pausar el origen; normaliza índice -1; no pausa si el destino no confirma.
- MIM-07: reauth Home desde el polling y proveedor de token SSE que relee el bearer; no reutiliza token viejo.
- MIM-11: badge Home enrolled/authenticated y runbook `docs/runbooks/HOME_ENROLLMENT.md`.

## Qué se completó en LOTE 02

- MIM-07: `HomeSessionAuthority` única (single-flight, TTL monotónico, generación, revocación, backoff con jitter, roles), instalación de token sólo mediante prueba criptográfica `VerifiedHomeSession`.
- MIM-05.5: enrollment real en `MichiLinkTrustHost`/`DevicesScreen`: QR → request → pending → refresh/poll → approved → auth, estados honestos y cancelación.
- MIM-03: distinción ausente/explícito-vacío/false en `server/info.features`; cache histórica sólo si el campo falta.
- MIM-08: operaciones por instancia (`queue_item_id`) en jump/delete/reorder, postcondiciones exactas, estados CONFLICT/UNKNOWN_OUTCOME, lifecycle real y arreglo de unmute.
- MIM-10.3: `AirCatOperationCoordinator` resuelve la sesión Home antes de credenciales legacy; sin fallback a SERVER_CODE.
- EventClient: sin replay de eventos viejos y cancelación no tragada.

## Qué falta (no repetir)

1. **MIM-10:** E2E AirCat con manifest/grant/download reales y revocación durante transferencia.
2. **MIM-11:** estados de enrollment en AirCatExperienceHost/SyncViewModel/ConnectionManager y diagnóstico de discovery.
3. **MIM-09:** recuperación/rollback completo ante fallo de destino y pruebas de tracks faltantes/duplicados.
4. **MIM-03:** mDNS (`_michi-link._tcp`) y Home roster no implementados; no adoptar R1.5 sin commit/pin.

## Bloqueos

- MS-001 (P0 documental) sigue abierto; Mobile ya usa vectores reales.
- MS-002/MS-003 impiden jump/add-next atómicos.
- MS-004 obliga a tolerar alias de repeat/shuffle.
- MS-005 (nuevo): `unmute` del servidor impone volumen 80 en vez de restaurar el previo.
- Sin servidor Micro autorizado vivo ni dispositivo físico; el emulador existe para instrumentación local.
