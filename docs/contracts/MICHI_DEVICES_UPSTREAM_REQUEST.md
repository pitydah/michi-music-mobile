# Michi Devices Upstream Request & Interoperability Specification

**Sender Repository:** `michi-music-mobile`  
**Target Upstream Repository:** `michi-devices` / `michi-desktop`  
**Protocol:** AirCat v1  

---

## 1. Contexto y Objetivos

Para que la aplicación móvil de Michi Music pueda interoperar con la interfaz de administración de dispositivos (Michi Devices) y los clientes de escritorio, se formalizan los siguientes requerimientos de contrato upstream para el protocolo de sincronización **AirCat**:

1. **No asumir APIs no publicadas:** La app móvil NO implementará endpoints inventados que fuercen al ecosistema a adaptarse a posteriori.
2. **Desacoplamiento de Transporte:** La capa de red debe soportar tanto HTTP sobre LAN como túneles locales sobre USB (cuando el protocolo de accesorios o port forwarding esté disponible).
3. **Identidad Canónica:** Se debe reutilizar la identidad Ed25519 de Michi Link (`michiId`) para firmar y validar tokens de sesión AirCat.

---

## 2. Requerimientos de Endpoints Upstream

### 2.1 Capability Discovery
```http
GET /api/v1/aircat/capabilities
Headers:
  Authorization: Bearer <michi-link-token>
  X-Michi-Device-Id: <client-michi-id>
```
Respuesta esperada:
- Versionado semántico (`1.0.0`).
- Soporte explícito de `Range` HTTP (código 206) para reanudación.
- Soporte para cabeceras `ETag` e `If-Range`.
- Algoritmo de hash obligatorio: `SHA-256`.

### 2.2 Manifest Retrieval & Negotiation
```http
POST /api/v1/aircat/sessions
Content-Type: application/json

{
  "transferIntentId": "xfer_uuid",
  "clientDeviceId": "michi_mobile_client",
  "clientCapabilities": { ... }
}
```
Respuesta esperada:
- Lista de pistas con `size` exacto, `format` (flac, mp3, aac, etc.) y `sha256`.
- `transferToken` con scope restringido a transferencias de archivos.

### 2.3 Streaming con Soporte de Rango (Byte-Range Streaming)
```http
GET /api/v1/aircat/stream/{trackId}
Range: bytes={offset}-
If-Range: "{etag}"
```
Comportamiento requerido:
- Si el offset es válido y el ETag coincide: responder `206 Partial Content` con cabecera `Content-Range: bytes {offset}-{total-1}/{total}`.
- Si el rango es insatisfactorio o no soportado: responder `200 OK` con el archivo completo desde 0, o `416 Range Not Satisfiable`.

---

## 3. Manejo de Revocación y Expiración

- Si un dispositivo móvil o emisor es revocado en el Home Trust, cualquier sesión AirCat activa debe retornar `401 Unauthorized` o `403 Forbidden` inmediatamente.
- La aplicación móvil cancelará de inmediato la transferencia sin eliminar los archivos `.part` que hayan sido verificados hasta el último bloque coherente.
