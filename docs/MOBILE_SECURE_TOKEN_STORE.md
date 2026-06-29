# Michi Mobile — Secure Token Store

## Migración

### De SharedPreferences a EncryptedSharedPreferences

**Antes:** Tokens almacenados en `SharedPreferences` plano en `michi_link_tokens`.
**Ahora:** Almacenados en `EncryptedSharedPreferences` en `michi_link_secure`.

### Proceso de migración

```kotlin
// TokenStore.migrateFromLegacyIfNeeded()
// 1. Verifica flag migrated_from_legacy en secure prefs
// 2. Si no migrado, lee legacy SharedPreferences
// 3. Si hay token legacy, copia todos los campos a secure
// 4. Limpia legacy SharedPreferences
// 5. Marca migrated = true
```

### Seguridad

| Aspecto | Detalle |
|---------|---------|
| Algoritmo | AES-256-GCM |
| Key scheme | MasterKey.KeyScheme.AES256_GCM |
| Key encryption | AES256-SIV |
| Value encryption | AES256-GCM |
| Keys almacenadas | device_token, refresh_token, server_id, etc. |
| No almacenado | Contraseña del servidor |

## Campos almacenados

| Campo | Tipo | Descripción |
|-------|------|-------------|
| server_id | String | ID del servidor |
| server_name | String | Nombre del servidor |
| service | String | Tipo de servicio (michi-music-player, michi-micro-server) |
| server_device_id | String | Device ID del servidor |
| server_alias | String | Alias del servidor |
| client_device_id | String | Device ID del cliente |
| device_token | String | Token de acceso principal |
| refresh_token | String | Token de refresco (si soportado) |
| permissions | String | Permisos otorgados (CSV) |
| paired_at | Long | Timestamp de emparejamiento |
| server_url | String | URL base del servidor |
| roles | String | Roles del servidor (CSV) |
| features | String | Features del servidor (CSV) |
| auth_strategy | String | Estrategia de autenticación |
| token_refresh_supported | Boolean | Si el servidor soporta refresco |

## Limpieza de tokens revocados

Cuando el servidor responde 403 (TOKEN_REVOKED):
1. RemoteViewModel detecta LinkException.Revoked
2. Muestra "Acceso denegado por el servidor"
3. Usuario presiona "Olvidar servidor"
4. SyncViewModel.forgetServer() → TokenStore.clear()
5. Todos los tokens y datos de sesión se borran
6. Usuario debe emparejar nuevamente
