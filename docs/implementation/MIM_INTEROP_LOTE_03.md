# MIM — LOTE 03: discovery/autorización, handoff y reauth

**Base publicada:** `64db675e`; implementación LOTE 02+03 en commit `49a0109c`.
**Contratos:** R1.4 `8dad0fb5`; R1.5 `ea76ee0e` no adoptado.

## Implementado
- `DeviceAccessState` y `UnifiedDevice.accessState`: availability, identity, Home, authenticated, capabilities y authorization separados.
- Proyección en `LinkDeviceRosterProjector`, `DeviceUiModels`, `DeviceDetailSheet` y gating AirCat.
- Dedup de discovery por `michiId` verificado; auditoría en `docs/devices/DISCOVERY_AUTH_STATE_AUDIT.md`.
- Handoff: confirma playing/track/índice en destino antes de pausar el origen; normaliza índice -1.
- Reauth Home desde polling; `EventClient` relee el bearer en cada reconnect.
- Runbook `docs/runbooks/HOME_ENROLLMENT.md`.

## Pendiente
MIM-10 E2E real, MIM-11 estados en AirCat/Sync/ConnectionManager, MIM-09 rollback completo, MIM-03 mDNS/Tailscale/roster.

## Evidencia
Workflow `4b4e7dba90c9bcaab4a40a308eef50e8`: 237 link + 1095 core + 414 aircat + 234 player + 2948 normal + 2948 fdroid; lint/arquitectura PASS.

## Publicación y CI (2026-11-28)
- Publicado `64db675e..1edbf73f` en `origin/master` sin force.
- Run `46099034554`: Job A/C/D/E/F/H PASS; Job B falló una vez y pasó en el rerun; Job G FAIL porque `PlaybackSessionManager` requería `HomeMembershipPersistence` sin definición Koin. Fue un fallo de arranque real.
- Corrección: binding `single<HomeMembershipPersistence> { get<HomeMembershipStore>() }` en `SyncModule`, sin tocar tests.
- Instrumentado normal local PASS: 232 tests, 0 fallos, 25 skipped (emulador API 36). El "track missing" del AVD era estado residual de MediaStore, no producto.
- Re-run de CI: run `46104446846` (head `6003e582`) **SUCCESS**, 9/9 jobs verdes incluido el instrumentado API 36 y el Quality Gate. Ningún test fue modificado.
