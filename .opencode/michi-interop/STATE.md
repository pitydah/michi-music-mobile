# Estado MIM

## Checkpoint actual — LOTE 03 publicado (2026-11-27)

- **Estado global:** LOTE_02+03 en commit local `49a0109c` sobre `64db675e`; CI pendiente de publicar.
- **Avance de implementación:** 90.0% (ver `BACKLOG.md`); no es certificación.
- **Verificación:** JVM de todos los módulos PASS (237 link + 1095 core + 414 aircat + 234 player + 2948 normal + 2948 fdroid); lint PASS; arquitectura PASS; instrumentado de LOTE 03 **NOT_RUN**; red real/físico **NOT_RUN**.
- **Operación pendiente:** ninguna a medias; el árbol compila, las suites JVM pasan y lint/arquitectura pasan. Instrumentado/red/físico NO-RUN.
- **Siguiente tarea exacta:** MIM-10 — E2E AirCat real (manifest/grant/download, revocación durante transferencia) si hay servidor autorizado; en su defecto MIM-11 estados restantes y MIM-09 recuperación/rollback.
- **Después:** MIM-09 recuperación completa/rollback y MIM-03 mDNS/roster si se autoriza.
- **Repo:** `/home/cristian/michi-music-mobile`, `master`, commit de implementación `49a0109c` (base `64db675e`); la actualización de esta evidencia se commiteará aparte.
- **Referencias:** Micro HEAD `b507b47...` pero working tree con 24 entradas R1.5, incluidos docs y tests nuevos (no tocado); Link `main` avanzó a `ea76ee0e` (R1.5) y está limpio. DRIFT registrado; el código sigue contra el pin R1.4 `8dad0fb5`. No adoptar R1.5 hasta commit/pin upstream.
- **CI histórico:** run `46071123540` (head `64db675e`) **SUCCESS** con Job A/G.

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
