# MIM — Lote 01 de interoperabilidad R1.4

**Fecha:** 2026-11-27
**Baseline:** `a737eb9f5675e9cd3d4022446096ba240b0b4bfe`
**Contratos:** Michi Link `8dad0fb5`; Micro Server `b507b47` (PR #26)
**Naturaleza:** cambios locales sin commit; ninguna publicación.

## Qué se implementó

### 1. Identidad y criptografía Home

- `michi-link-client/.../home/HomeAuthCrypto.kt`
  - Base64URL estricto.
  - `michi_id = base64urlNoPad(BLAKE3-256(public_key))`.
  - Transcripts `michi-link-home-enrollment-pop-v1`, `michi-link-device-auth-v1`, `michi-link-server-auth-v1`, `michi-link-membership-v1`.
  - Firma/verificación Ed25519 con forma estricta.
  - `HomeMembershipVerifier` y `HomeServerSessionVerifier`.
- `MichiIdentity.signCanonicalPayload()` y `MichiSigner`.
- Golden vectors de Link: firmas byte a byte en `HomeAuthCryptoTest`.

### 2. Enrollment y auth mutua

- `app/.../devices/home/HomeEnrollmentCoordinator.kt`
  - challenge → PoP → request → pending cifrado.
  - status con `X-Michi-Enrollment-Claim`.
  - approved: verifica `home_root_public_key`, membership, identidad local; persiste.
  - auth: challenge → firma → session → verifica membership del servidor y `server_signature` antes de publicar token.
- `HomeMembershipStore` cifrado con Keystore, excluido de backup/transfer.
- Errores: `MEMBER_REVOKED`, `ROOT_KEY_CHANGED`, `INVALID_MEMBERSHIP`, `SERVER_AUTH_REJECTED`, `expired`.

### 3. Michi Control y cola

- `QueueDto` R1.4: `items[{id,track_id,position}]`, `queue_version`, `queue_degraded`, `item_ids`.
- Comandos canónicos:
  - `play` + `track_id` (en vez de `play_track`).
  - `add_to_queue` → `/queue/items`.
  - `play_next` → append + reorder por `item_ids` + verificación.
  - `clear_queue` → borrado de items upcoming por `queue_item_id`.
- `reorder` por lista completa de `item_ids`.
- `jump` resuelto por `queue_item_id` + fencing de `queue_version`.
- Errores tipados antes del HTTP status.

### 4. Capacidades y transporte

- `ServerIdentityPolicy`: `identity_scheme` canónico ⇒ BLAKE3(pubkey)==michi_id.
- `AirCatCapabilityPolicy`: cinco flags R1.4; peer parcial no cae a alias legacy.
- `LinkClient` Home: HTTPS + SPKI pin obligatorios; token `michi_home_*` sólo en RAM.

## Qué falta

- UI de enrollment Home (MIM-05.5/MIM-11.2).
- Sesión Home compartida en `PlaybackSessionManager`/`AirCatOperationCoordinator` (MIM-07/09/10.3).
- Reconciliación de `QUEUE_DEGRADED` desde UI.
- E2E red real y certificación física.

## Divergencias upstream

Ver `MS-001..004` y `ML-001..003` en el Escritorio. La más grave es MS-001: los documentos de Micro describen dominios/`\n`/`expires_at` que no coinciden con el código ni los vectores.

## Verificación

- `:michi-link-client:testDebugUnitTest` — 229 PASS.
- `:app:testNormalDebugUnitTest` — 2930 PASS.
- `:app:testFdroidDebugUnitTest` — 2930 PASS.
- `:app:lintNormalDebug` — PASS.
- `python3 tools/check_settings_architecture.py` — PASS.
- `ArtistEmbeddedTagsUiTest` y `K48R12SurfacePerformanceProbeTest` — PASS en emulador API 36.
- CI GitHub `46064991170` (intento 2) — SUCCESS, incluido Job G instrumentado.
- Android físico/red real — NOT_RUN.
