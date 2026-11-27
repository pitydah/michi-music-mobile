# Decisiones

## D001 — Solo Mobile recibe cambios de código
Micro Server y Link son referencias de solo lectura. Sus hallazgos se entregan como Markdown en `~/Escritorio/MICHI_INTEROP_MOBILE/`.

## D002 — La norma criptográfica son el código y los vectores de Link, no la prosa del handoff
Los documentos de Micro R1.4 usan dominios `michi-home-*`, `\n` y `expires_at`; el código y los vectores usan `michi-link-*`, concatenación directa y `serial`. Mobile se implementó contra los vectores. Ticket MS-001.

## D003 — BLAKE3 directo, sin reflexión
`MichiIdentity` y el nuevo módulo Home usan `org.bouncycastle.crypto.digests.Blake3Digest` (BC 1.77 ya presente). Se elimina la dependencia de reflexión para la derivación de `michi_id`.

## D004 — Token Home separado del token legacy
`michi_home_*` vive en `LinkClient` (RAM + TTL) y nunca se escribe en `TokenStore`/`PairedDeviceRegistry`. `SERVER_CODE` sigue segregado. `LinkAuthStrategyGate` sigue rechazando `HOME_MEMBERSHIP` hasta que la UI/sesión estén cableadas.

## D005 — Cola: identidad de instancia, no de pista
`QueueDto` expone `items[{id,track_id,position}]` y `item_ids`. Delete/reorder/jump usan `queue_item_id`. Donde el productor sólo acepta índice (jump), Mobile resuelve item→index sobre snapshot fresco y exige `queue_version`; el gap se reporta en MS-002.

## D006 — Add-next sin primitiva atómica
El productor ignora `position: next` (MS-003). Mobile hace append + reorder por lista completa de `item_ids` y sólo confirma si observa la pista en la posición siguiente; si no, reporta `ACCEPTED`. No se inventa un endpoint.

## D007 — Errores tipados antes que HTTP status
`MEMBER_REVOKED`, `HOME_SESSION_REQUIRED`, `TLS_REQUIRED`, `REVOCATION_STORE_UNAVAILABLE` y `QUEUE_DEGRADED` se leen del body. Un 403 genérico ya no se etiqueta como “revocado”.

## D008 — AirCat: cinco flags canónicos con fallback legacy explícito
Un peer que anuncia cualquier flag canónico debe tener los cinco; un peer parcial no cae al alias `aircat`. Un peer claramente legacy conserva el alias anterior. La autorización real sigue siendo Home membership + grant; una feature no autoriza.

## D009 — Enrollment probado en JVM, UI pendiente
`HomeEnrollmentCoordinator` implementa challenge/PoP/request/pending/status/approved/auth con persistencia cifrada. No se conectó aún a la pantalla de Devices; se registra como MIM-05.5/MIM-11.2 pendiente. No se declara GO de enrollment físico.

## D010 — Sin commit/push
Todos los cambios quedan en el working tree de Mobile. El operador decide la publicación. No se crearon ramas.
