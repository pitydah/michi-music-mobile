# Backlog MIM

Pesos definidos antes de ejecutar el lote. Avance = suma de subtareas implementadas con diff + wiring. La verificación se mide aparte (JVM / Android / red / físico).

| Hito | Peso | Estado | Avance | Evidencia |
|---|---:|---|---:|---|
| MIM-01 Contratos y baseline | 8% | DONE | 8.0/8 | Matriz `04_MATRIZ_CONTRATOS.md`; baseline verificado |
| MIM-02 Identidad y criptografía | 8% | TESTED_JVM | 8.0/8 | `HomeAuthCryptoTest` golden vectors |
| MIM-03 Discovery y capacidades | 8% | TESTED_JVM | 7.0/8 | Estado de acceso explícito, dedup por michiId, auditoría Whisker/mDNS/Tailscale; falta mDNS/roster |
| MIM-04 Transporte y confianza | 10% | TESTED_JVM | 10.0/10 | `homeTransportRefusal`, SPKI pin, tests |
| MIM-05 Enrollment | 12% | TESTED_JVM + UI parcial | 10.0/12 | Coordinator + store + trust UI; faltan hosts/estados |
| MIM-06 Membership y auth mutua | 14% | TESTED_JVM | 13.0/14 | Verificadores + coordinator + autoridad de sesión; falta revocación E2E |
| MIM-07 Sesiones y reconexión | 10% | TESTED_JVM | 7.0/10 | Authority + reauth desde polling + SSE token provider; falta E2E real |
| MIM-08 Control y cola | 12% | TESTED_JVM | 11.5/12 | Item ids en jump/delete/reorder, lifecycle, CONFLICT/UNKNOWN; falta multi-select/NowPlaying completo |
| MIM-09 Handoff | 6% | TESTED_JVM | 5.5/6 | Confirmación exacta de track/índice/playing antes de pausar origen; falta E2E físico |
| MIM-10 AirCat Strict | 7% | TESTED_JVM | 6.5/7 | 5 flags + sesión Home resuelta antes de legacy; falta E2E real |
| MIM-11 UI y cierre | 5% | IMPLEMENTED_UNVERIFIED | 3.5/5 | Trust UI, badge Home, runbook; faltan estados AirCat/Sync/ConnectionManager |
| **Total** | **100%** | | **90.0%** | |

## Desglose de subtareas

### MIM-01 (8)
- 1.1 Inventario endpoint/auth/transporte/errores — 4 ✅
- 1.2 Baseline y pin Link — 1 ✅
- 1.3 Registro de divergencias con propietario — 3 ✅

### MIM-02 (8)
- 2.1 Base64URL estricto + dominios canónicos — 2 ✅
- 2.2 Membership (roles ordenados, serial, raíz) — 2 ✅
- 2.3 Server auth + `server_signature` antes de token — 2 ✅
- 2.4 Persistencia/recuperación de identidad — 2 ✅ (heredado verificado)

### MIM-03 (8)
- 3.1 `server/info` coherencia BLAKE3 — 2 ✅
- 3.2 5 flags AirCat + proyección `featuresMap` — 3 ✅
- 3.3 Gating por `HOME_MEMBERSHIP` activo — 2 🟡 (1.5/2; lease + access state, sin roster)
- 3.4 Source binding/dedup discovery — 1 ✅ (heredado + dedup por michiId)

### MIM-04 (10)
- 4.1 HTTPS + SPKI pin obligatorio — 3 ✅
- 4.2 Sin trust-all/redirect/downgrade — 2 ✅
- 4.3 Home calls sobre transporte pinneado — 2 ✅
- 4.4 Rotación de raíz/pin con QR nuevo — 2 ✅
- 4.5 Sin secretos en backup/logs — 1 ✅

### MIM-05 (12)
- 5.1 Challenge + PoP + request — 3 ✅
- 5.2 Persistencia pending + claim — 2 ✅
- 5.3 Polling acotado/cancelable — 2 ✅
- 5.4 Approved + verificación + persistencia — 2 ✅
- 5.5 UI QR → pending → approved — 3 🟡 (1.0/3; trust screen sí, hosts/estados no)

### MIM-06 (14)
- 6.1 Verificación membership contra raíz — 3 ✅
- 6.2 Challenge/client proof/session — 3 ✅
- 6.3 Server membership + firma antes de token — 3 ✅
- 6.4 Perfil `HOME_MEMBERSHIP` sin quitar gate — 2 🟡 (1/2; enum+gate, sin UI)
- 6.5 Permisos/revocación — 3 🟡 (2/3; mapeo y fail-closed, sin revocación real)

### MIM-07 (10)
- 7.1 Errores tipados 401/403/503 — 2 ✅
- 7.2 Token Home TTL/autoridad única — 2 🟡 (1/2)
- 7.3 Política de reauth/clear session — 2 ✅ (authority + polling/SSE)
- 7.4 Single-flight/stale fencing — 2 ✅
- 7.5 Backoff/cancelación — 2 ✅

### MIM-08 (12)
- 8.1 DTO items/version/degraded — 3 ✅
- 8.2 Comandos canónicos — 2 ✅
- 8.3 Reorder/delete por item id — 2 ✅
- 8.4 Jump por item + fencing — 2 ✅ (cliente)
- 8.5 Repeat/shuffle aliases — 1 ✅
- 8.6 UI degraded/duplicados — 2 🟡 (1.5/2; falta reconcile)

### MIM-09 (6)
- 9.1 Autoridad única conservada — 2 ✅
- 9.2 Item ids/posición en handoff — 2 🟡 (1.5/2; confirmación de destino, falta recuperación completa)
- 9.3 Sin doble reproducción/rollback — 2 ✅ (heredado)

### MIM-10 (7)
- 10.1 Grant/CA/SPKI — 2 ✅ (heredado)
- 10.2 5 flags — 2 ✅
- 10.3 Sesión Home compartida — 1 ✅ (AirCat; falta E2E)
- 10.4 Resume/Range/hash — 2 ✅ (heredado)

### MIM-11 (5)
- 11.1 UI cola degradada/duplicados — 2 ✅
- 11.2 UI enrollment Home — 1 🟡 (0.75/1; trust screen)
- 11.3 UX TLS/revocación — 1 🟡 (0.25)
- 11.4 Docs/runbook/F-Droid — 1 🟡 (0.75; runbook Home)

## Bloqueos upstream

- MS-001 P0: documentación de transcripts Home incorrecta.
- MS-002 P1: jump por item no soportado.
- MS-003 P1: `position: next` ignorado.
- MS-004 P1: campos de repeat/shuffle no conformes.
- MS-005 P1: unmute del servidor impone volumen 80 en vez de restaurar el previo.
- ML-001..ML-004: resueltos en Link `ea76ee0e` (R1.5); no adoptados hasta que Micro commitee y fije el pin.
- R1.5 en worktree Micro (24 entradas, incluidos docs, tests `interop_r1_5_*` y `vendor/michi-link` → `ea76ee0e`) sin commit; no es baseline verificable.
- mDNS, Tailscale auto-discovery y Home roster no están implementados/consumidos en Mobile; documentado en `docs/devices/DISCOVERY_AUTH_STATE_AUDIT.md`.

## Siguiente tarea exacta

MIM-10 E2E AirCat real (manifest/grant/download/revocación) y MIM-11 estados restantes; MIM-09 recuperación completa/rollback y MIM-03 mDNS/roster si se autoriza.
