# Discovery & authorization audit — MIM-03

**Fecha:** 2026-11-27
**Baseline Mobile:** `64db675e` + LOTE 02/03 en working tree
**Referencias:** Link R1.4 `8dad0fb5` (código), Micro `b507b47` (R1.4). R1.5 `ea76ee0e` presente pero **no adoptado**.

## 1. Matriz de transporte

| Transporte | Estado Mobile | Evidencia / límite |
|---|---|---|
| Whisker UDP `224.0.0.167:53318` | IMPLEMENTADO | `LinkDiscovery`: MulticastLock, joinGroup, socket timeout 1 s, TTL 90 s, máximo 128 peers, goodbye, replay cache, firma Ed25519 + BLAKE3, source binding. |
| mDNS `_michi-link._tcp.local.` | **NO IMPLEMENTADO** | No hay `NsdManager`/JmDNS en Mobile. El handoff R1.4 lo menciona; Mobile sólo consume Whisker. Es un GAP de descubrimiento, no de autorización. |
| IP manual / QR | IMPLEMENTADO | El pairing/QR fija endpoint; no se acepta un IP nuevo desde un anuncio. |
| DHCP / cambio de IP | PROTEGIDO | `LinkAnnounceSourceBindingPolicy`: un paquete firmado desde otra IP no reescribe el endpoint fijado; sólo una identidad Ed25519 idéntica y host coherente puede migrar. |
| WiFi/LAN | IMPLEMENTADO | MulticastLock se adquiere/libera con lifecycle; stop cierra socket/lock. |
| Tailscale | **NO AUTO-DESCUBIERTO** | No hay código Tailscale. Un endpoint Tailscale puede usarse si el operador lo fija por pairing/QR; no se descubre por mDNS/UDP. |
| Micro Server `/server/info` | VERIFICADO | Identidad canónica BLAKE3; features ausente/vacío/false distinguidos. |
| Home roster | **NO CONSUMIDO** | Mobile no reclama rol admin; la autorización Home se deriva del QR+membership local, no del roster. |

## 2. Disponibilidad vs autorización

`UnifiedDevice.accessState` (`DeviceAccessState`) separa:

- `availability`: `UNKNOWN`, `DISCOVERED`, `REACHABLE`, `OFFLINE`.
- `identityVerified`: firma Ed25519 y `michi_id` coherente, o identidad fijada en el registro.
- `homeAuthorized`: existe un ancla Home para el `sourceMichiId`.
- `authenticated`: lease Home vigente o conexión legacy CONNECTED; **no** se deduce de disponibilidad.
- `capabilitiesVerified`: el servidor envió un mapa de features; un descubrimiento no asigna capabilities.
- `authorization`: `NONE`, `DISCOVERED_IDENTITY`, `LEGACY_PAIRED`, `HOME_ENROLLED`, `HOME_AUTHENTICATED`, `REVOKED`.

Un peer `HOME_ENROLLED` sin lease no habilita AirCat (`AirCatEligibilityPolicy` y `DeviceActionResolver` lo bloquean). Un peer descubierto y firmado no es Home-authorized.

## 3. Deduplicación e identidad

- Los peers se guardan por `deviceId` (o IP:puerto si falta).
- `LinkDiscovery` elimina observaciones previas con el mismo `michiId` verificado cuando cambia el `deviceId`, evitando dos filas para el mismo dispositivo tras una migración de firmware.
- `LinkDeviceRosterProjector` deduplica el roster contra el `michiId` fijado en el registro; un `deviceId` legacy distinto no crea un peer nuevo si la identidad coincide.
- Un anuncio unsigned nunca reemplaza un peer verificado ni cambia su IP/alias.
- `MAX_OBSERVED_PEERS=128` y TTL 90 s acotan memoria y presencia.

## 4. Límites y NO-RUN

- No se probó multicast/mDNS/DHCP reales en LAN física ni Tailscale.
- No se consumió `/api/v1/home/roster` (requiere admin).
- R1.5 añade `server_boot_id` y precondiciones de cola; no se adoptan hasta commit/pin de Micro.
- La UI muestra `HOME_ENROLLED`/`HOME_AUTHENTICATED` en el badge; no se añadió un panel de diagnóstico de discovery.
