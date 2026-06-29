# Michi Mobile — Pairing UX

## Estrategias de emparejamiento

### PLAYER_PASSWORD

**Cuándo se usa:** Cuando `server/info` responde con `auth.strategy = "PLAYER_PASSWORD"`.

```
SyncScreen → seleccionar servidor
→ detecta auth.strategy = PLAYER_PASSWORD
→ muestra formulario con campo "Usuario" + "Contraseña"
→ user ingresa credenciales locales del Player
→ pair/start → pair/confirm
→ token guardado
→ estado: "Emparejado y autorizado"
```

**UX:**
- Mostrar nombre del servidor en el formulario
- Botón "Emparejar" habilitado solo si ambos campos no están vacíos
- Botón "Cancelar" para volver atrás
- Error: "Credenciales incorrectas" si 401
- El formulario permanece abierto para reintento

### SERVER_CODE

**Cuándo se usa:** Cuando `server/info` responde con `auth.strategy = "SERVER_CODE"`.

```
SyncScreen → seleccionar servidor
→ detecta auth.strategy = SERVER_CODE
→ muestra formulario con campo "Código de emparejamiento"
→ user ingresa código PIN mostrado por el servidor
→ pair/start → pair/confirm(pin=code)
→ token guardado + refresh_token guardado
→ estado: "Emparejado y autorizado"
```

**UX:**
- No mostrar campo de usuario/contraseña
- Texto: "Ingresa el código de emparejamiento del servidor"
- Botón "Emparejar" habilitado solo si código no está vacío
- Timeout: si no se confirma en 30s, mostrar botón "Reintentar"
- Error: "Código incorrecto" si 401

### RECEIVER_BUTTON

**Cuándo se usa:** Cuando `server/info` responde con `auth.strategy = "RECEIVER_BUTTON"`.

```
SyncScreen → seleccionar servidor
→ detecta auth.strategy = RECEIVER_BUTTON
→ no permite emparejar
→ mensaje: "Este dispositivo no es una fuente controlable directa"
→ servidor no aparece como opción de pairing
```

### LEGACY

**Cuándo se usa:** Cuando no hay `auth.strategy` o servidor antiguo.

```
SyncScreen → seleccionar servidor
→ strategy = LEGACY
→ muestra PairingForm (username/password)
→ llama a /api/register (endpoint legacy)
→ token guardado
```

## Estados de conexión

```
DISCONNECTED → user presiona "Buscar servidores"
  → DISCOVERING → encontró servidor
    → PAIRING_REQUIRED → user ingresa credenciales
      → PAIRING → esperando confirmación
        → PAIRED → token guardado, listo
          → CONNECTED → puede sincronizar
```

## Flujo de errores

| Error | Causa | UX |
|-------|-------|-----|
| InvalidCredentials | Contraseña incorrecta | "Credenciales incorrectas" - formulario se queda abierto |
| Revoked | Token revocado | "Acceso denegado" - borrar token |
| Unauthorized | Token expirado | "Sesión expirada" - pedir emparejamiento de nuevo |
| TokenExpired | Refresh falló | "Token expirado" - pedir emparejamiento de nuevo |
| NotImplemented | Servidor no soporta | "No implementado" - ocultar feature |
| NetworkError | Sin conexión | "Servidor fuera de línea" |
