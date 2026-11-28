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

## D010 — Publicación autorizada sólo en un lote anterior
El operador autorizó commit/push/CI del LOTE 01 a `origin/master` (commits `d4082d6f`..`64db675e`, CI `46071123540` verde). LOTE 02 vuelve a la regla por defecto: cambios en working tree, sin commit/push hasta nueva autorización.

## D011 — HomeSessionAuthority única y prueba de token no falsificable
`HomeSessionAuthority` serializa la autenticación con `Mutex`, usa TTL monotónico, generación de sesión, backoff con jitter y revocación terminal. `LinkClient.acceptVerifiedHomeSession` sólo acepta `VerifiedHomeSession`, una prueba de módulo que produce `HomeServerSessionVerifier` tras verificar membership y firma del servidor. Un prefijo no instala nada.

## D012 — Enrollment UI en trust screen, no auto-aprobación
`MichiLinkTrustHost` expone solicitud/pending/refresco/poll/auth por origen. El operador aprueba en WebUI; la app nunca se auto-aprueba ni convierte `/server/info` en conexión.

## D013 — Identidad de instancia en cola
Jump, delete y reorder aceptan `queue_item_id` capturado en el gesto y confirman la postcondición exacta (instancia ausente, secuencia exacta, instancia esperada). `removeFromQueue(index)` queda como compatibilidad y delega al item id si existe.

## D014 — Features: ausente ≠ vacío ≠ false
`ServerInfoDto.verifiedFeatures` devuelve `null` si el campo falta (conservar cache histórica) y una lista (posiblemente vacía) si el servidor lo envió. `ConnectionManager` ya no usa `ifEmpty` para borrar o conservar por accidente.

## D015 — Lifecycle de playback manda sobre `state`
El servidor envía `state=playing|paused` y `lifecycle`; `effectiveState` prioriza lifecycle, de modo que `stopped`/`ended`/`failed` no se convierten en `paused`. `generation_id` se conserva en el estado de sesión.

## D016 — AirCat Home antes que legacy
`AirCatOperationCoordinator` resuelve `HomeSessionAuthority` para un origen Home y falla cerrado si no hay lease; sólo usa deviceToken legacy cuando no existe ancla Home.

## D017 — Estado de acceso ortogonal (MIM-03)
`DeviceAccessState` separa availability, identityVerified, homeAuthorized, authenticated, capabilitiesVerified y authorization. Un peer REACHABLE o con identidad firmada no es Home-authorized; un HOME_ENROLLED sin lease no es authenticated y no habilita AirCat. Un descubrimiento nunca asigna capabilities.

## D018 — Dedup por identidad criptográfica
`LinkDiscovery` elimina observaciones previas con el mismo `michiId` verificado cuando cambia el `deviceId`; `LinkDeviceRosterProjector` deduplica contra la identidad fijada. Un `deviceId` legacy no crea un peer nuevo.

## D019 — Handoff confirmado en destino (MIM-09)
`handoffTo` no pausa el origen ni declara éxito tras un 200 de `transferQueue`. Lee `getPlaybackState`/`getQueue` y exige `playing` + track/índice esperados; si no confirma, deja el audio local activo y reporta el fallo. El índice -1 se normaliza a 0 en la petición y en la postcondición.

## D020 — Reauth de polling y SSE (MIM-07)
`refreshRemotePlayback`/`refreshRemoteQueue` renuevan la sesión Home con `HomeSessionAuthority` antes de leer; `EventClient` recibe un `tokenProvider` que relee el bearer en cada reconnect para no usar un token viejo tras reauth.

## D021 — Gaps de discovery declarados
mDNS `_michi-link._tcp` y Tailscale no tienen auto-discovery en Mobile; el roster Home no se consume (requiere admin). Quedan documentados como GAP, no se presentan como implementados. `LinkAuthStrategyGate` sigue rechazando `HOME_MEMBERSHIP` en el pairing legacy; el enrollment real vive en el trust screen.
