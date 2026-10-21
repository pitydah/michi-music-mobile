# AirCat Mobile Consumer Contract

**Version:** 1.0.0-draft  
**Domain Owner:** Michi Devices / Mobile  
**Ecosystem:** Michi Music / AirCat  

---

## 1. Alcance y Principios Arquitectónicos

1. **AirCat** es un protocolo especializado de transferencia directa entre pares (Peer-to-Peer) perteneciente exclusivamente al dominio **Michi Devices / Mobile**. No es un reemplazo ni una variante del protocolo base transversal de control y streaming de **Michi Link**.
2. **Confianza e Identidad:** AirCat no implementa un sistema de autenticación separado. Utiliza las identidades canónicas criptográficas Ed25519 de **Michi Link** y los memberships establecidos en el **Home Trust Registry** como prerrequisito de autorización mutua.
3. **Rol del Cliente Móvil:** La aplicación móvil actúa fundamentalmente como **consumidor/receptor (Receiver)** de lotes autorizados de transferencia y como supervisor de estado, informando capacidades de almacenamiento, estado de red y progreso en bytes reales.

---

## 2. Negociación de Capacidades (Capability Negotiation)

Antes de iniciar una sesión de transferencia, el peer emisor (p. ej. Desktop Player o Michi Node) y el peer receptor móvil deben negociar capacidades:

```json
{
  "protocol": "AirCat",
  "version": "1.0.0",
  "supportedTransports": ["LAN_HTTP", "USB_ACCESSORY"],
  "chunkSize": 65536,
  "supportedChecksums": ["SHA-256"],
  "supportsResume": true,
  "maxConcurrentTransfers": 4,
  "availableStorageBytes": 10737418240
}
```

- **Rechazo por versión:** Si la versión mayor (`MAJOR`) de emisor y receptor difiere, la negociación falla inmediatamente con error `INCOMPATIBLE_PROTOCOL_VERSION`.
- **Límites de almacenamiento:** El receptor móvil rechaza el inicio de transferencia (`INSUFFICIENT_STORAGE`) si el tamaño total del manifiesto excede el espacio libre de disco con un margen de seguridad de al menos 100 MB.

---

## 3. Contrato de TransferIntent y Sesión Autorizada

### 3.1 TransferIntent (Autorización de Emisión)
Emitido por la UI del origen tras verificar permisos en el ecosistema:

```json
{
  "transferId": "xfer_9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "sourceMichiId": "michi_node_master_01",
  "destinationMichiId": "michi_mobile_client_02",
  "sourceLibraryId": "lib_studio_master",
  "destinationLibraryId": "lib_synced_michi_node_master_01",
  "selectionScope": "ALBUMS",
  "manifestVersion": 1,
  "requiredScopes": ["content:read", "content:transfer"],
  "expiresAt": 1735689600000
}
```

### 3.2 Manifest
El manifiesto detalla cada pista a transferir con su hash esperado y tamaño:

```json
{
  "manifestVersion": 1,
  "transferId": "xfer_9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "sourceDeviceId": "michi_node_master_01",
  "totalTracks": 2,
  "totalBytes": 78643200,
  "items": [
    {
      "logicalTrackId": "trk_flac_96k_001",
      "remoteTrackId": "node_trk_8831",
      "title": "Starlight Echoes",
      "artist": "Michi Ensemble",
      "album": "Acoustic Journey",
      "format": "flac",
      "size": 41943040,
      "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
  ]
}
```

---

## 4. Garantías de Transferencia y Resiliencia (Acoplamiento M8)

1. **Reanudación Transaccional:** Cada archivo se descarga a una ruta temporal `.part`. Si la transferencia se interrumpe, se conserva el checkpoint y se reanuda enviando `Range: bytes=offset-` verificado.
2. **Reinicio Honesto:** Si el emisor no soporta rango o cambia el ETag, la transferencia se reinicia honestamente desde cero (`RESTARTED_FROM_ZERO`) sin corromper el buffer.
3. **Verificación Estricta:** No se promueve ningún archivo `.part` a archivo final sin coincidencia exacta de longitud y verificación de hash **SHA-256**.
4. **Promoción Atómica:** Si ya existe una versión previa en disco, se conserva en `.bak` hasta confirmar el éxito del nuevo reemplazo.

---

## 5. Códigos de Error Normalizados

| Código | Significado | Recuperabilidad |
|---|---|---|
| `UNAUTHORIZED_PEER` | El emisor no posee token válido o identidad reconocida en Home Trust | Fatal |
| `REVOKED_MEMBERSHIP` | El dispositivo emisor fue revocado del Home Trust durante la transferencia | Fatal |
| `INSUFFICIENT_STORAGE` | Espacio insuficiente en el almacenamiento de destino | Reintentable tras liberar espacio |
| `MANIFEST_VERSION_MISMATCH` | Versión de manifiesto incompatible o corrupta | Fatal |
| `CHECKSUM_MISMATCH` | El hash calculado en destino no coincide con el declarado | Reintentable desde cero |
| `DUPLICATED_TRANSFER_ID` | Se intentó registrar una transferencia con un ID ya procesado | Idempotente / Ignorar |
| `ETAG_CHANGED` | El contenido en el servidor fue modificado a mitad de transferencia | Reintentar desde 0 |
