# Backlog MIM

Pesos definidos antes de ejecutar el lote. Avance = suma de subtareas implementadas con diff + wiring. La verificación se mide aparte (JVM / Android / red / físico).

| Hito | Peso | Estado | Avance | Evidencia |
|---|---:|---|---:|---|
| MIM-01 Contratos y baseline | 8% | DONE | 8.0/8 | Matriz `04_MATRIZ_CONTRATOS.md`; baseline verificado |
| MIM-02 Identidad y criptografía | 8% | TESTED_JVM | 8.0/8 | `HomeAuthCryptoTest` golden vectors |
| MIM-03 Discovery y capacidades | 8% | TESTED_JVM | 6.0/8 | `ServerIdentityPolicy`, `AirCatCapabilityPolicy`; falta gating por sesión activa |
| MIM-04 Transporte y confianza | 10% | TESTED_JVM | 10.0/10 | `homeTransportRefusal`, SPKI pin, tests |
| MIM-05 Enrollment | 12% | TESTED_JVM | 9.0/12 | Coordinator + store; falta UI (3%) |
| MIM-06 Membership y auth mutua | 14% | TESTED_JVM | 12.0/14 | Verificadores + coordinator; falta sesión compartida (2%) |
| MIM-07 Sesiones y reconexión | 10% | IMPLEMENTED_UNVERIFIED | 5.0/10 | Errores tipados + TTL; falta reauth single-flight |
| MIM-08 Control y cola | 12% | TESTED_JVM | 11.5/12 | DTO R1.4, comandos canónicos, item ids, errores |
| MIM-09 Handoff | 6% | IMPLEMENTED_UNVERIFIED | 5.0/6 | Item ids disponibles; falta cablear handoff |
| MIM-10 AirCat Strict | 7% | TESTED_JVM | 6.0/7 | 5 flags + policy; falta sesión Home en producción |
| MIM-11 UI y cierre | 5% | IMPLEMENTED_UNVERIFIED | 2.75/5 | Banner degraded/duplicados; falta UI enrollment |
| **Total** | **100%** | | **83.25%** | |

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
- 3.3 Gating por `HOME_MEMBERSHIP` activo — 2 ⛔ (UI/sesión)
- 3.4 Source binding/dedup discovery — 1 ✅ (heredado)

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
- 5.5 UI QR → pending → approved — 3 ⛔

### MIM-06 (14)
- 6.1 Verificación membership contra raíz — 3 ✅
- 6.2 Challenge/client proof/session — 3 ✅
- 6.3 Server membership + firma antes de token — 3 ✅
- 6.4 Perfil `HOME_MEMBERSHIP` sin quitar gate — 2 🟡 (1/2; enum+gate, sin UI)
- 6.5 Permisos/revocación — 3 🟡 (2/3; mapeo y fail-closed, sin revocación real)

### MIM-07 (10)
- 7.1 Errores tipados 401/403/503 — 2 ✅
- 7.2 Token Home TTL/autoridad única — 2 🟡 (1/2)
- 7.3 Política de reauth/clear session — 2 🟡 (1/2)
- 7.4 Single-flight/stale fencing — 2 ⛔
- 7.5 Backoff/cancelación — 2 🟡 (1/2)

### MIM-08 (12)
- 8.1 DTO items/version/degraded — 3 ✅
- 8.2 Comandos canónicos — 2 ✅
- 8.3 Reorder/delete por item id — 2 ✅
- 8.4 Jump por item + fencing — 2 ✅ (cliente)
- 8.5 Repeat/shuffle aliases — 1 ✅
- 8.6 UI degraded/duplicados — 2 🟡 (1.5/2; falta reconcile)

### MIM-09 (6)
- 9.1 Autoridad única conservada — 2 ✅
- 9.2 Item ids/posición en handoff — 2 🟡 (1/2)
- 9.3 Sin doble reproducción/rollback — 2 ✅ (heredado)

### MIM-10 (7)
- 10.1 Grant/CA/SPKI — 2 ✅ (heredado)
- 10.2 5 flags — 2 ✅
- 10.3 Sesión Home compartida — 1 ⛔
- 10.4 Resume/Range/hash — 2 ✅ (heredado)

### MIM-11 (5)
- 11.1 UI cola degradada/duplicados — 2 ✅
- 11.2 UI enrollment Home — 1 ⛔
- 11.3 UX TLS/revocación — 1 🟡 (0.25)
- 11.4 Docs/runbook/F-Droid — 1 🟡 (0.5)

## Bloqueos upstream

- MS-001 P0: documentación de transcripts Home incorrecta.
- MS-002 P1: jump por item no soportado.
- MS-003 P1: `position: next` ignorado.
- MS-004 P1: campos de repeat/shuffle no conformes.
- ML-001..ML-003 P1/P2: roster DTO, `desktop`, decisión de contrato de cola.

## Siguiente tarea exacta

MIM-05.5 / MIM-11.2 — UI de enrollment Home; después MIM-07/09/10.3 — sesión Home compartida.
