# MIM — LOTE 02: sesión Home, enrollment UI y cola por instancia

**Base publicada:** `64db675e` (CI `46071123540` verde)
**Estado:** commit `49a0109c` (LOTE 02+03).

## Implementado

### MIM-07 — HomeSessionAuthority
- `HomeSessionAuthority`: un solo vuelo de autenticación, TTL monotónico, generación, revocación terminal, backoff con jitter y roles.
- `LinkClient`: `VerifiedHomeSession` no falsificable; `acceptVerifiedHomeSession` ya no acepta un String por prefijo. Contexto Home + TTL obligatorios.
- `MEMBER_REVOKED` limpia token, membership y bloquea reauth; `TLS_REQUIRED`/`FORBIDDEN`/`REVOCATION_STORE_UNAVAILABLE` no se confunden con revocación.
- `EventClient`: sin replay de eventos viejos y cancelación propagada.

### MIM-05.5 — Enrollment en UI
- `MichiLinkTrustHost`/`DevicesScreen`: solicitar membresía, pending, refrescar, polling acotado, autenticar, cancelar y estados REJECTED/REVOKED/ERROR.
- `HomeEnrollmentService` permite probar el controlador sin red/Keystore.

### MIM-03/MIM-08/MIM-10
- `ServerInfoDto.verifiedFeatures` distingue ausente / vacío / false.
- Jump/delete/reorder por `queue_item_id` con postcondición exacta; `CONFLICT`/`UNKNOWN_OUTCOME`.
- `PlaybackStateDto.lifecycle` manda sobre `state`; `generation_id` modelado.
- `RemoteViewModel.unmute` restaura el último volumen audible, no 70/80.
- AirCat resuelve la sesión Home antes de `deviceToken`.

## Pendiente
MIM-03 estados de dispositivo; MIM-09 handoff; reauth periódica en `PlaybackSessionManager`; E2E AirCat/red/físico.

## Evidencia
Workflow `730b654f01c862f8e98ad6face0b80c4`; 237 link + 1095 core + 414 aircat + 234 player + 2942 normal + 2942 fdroid PASS; lint y arquitectura PASS.
