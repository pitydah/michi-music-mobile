# Michi Mobile — Diagnostics

## Pantalla de diagnóstico

Settings → Diagnóstico Michi Link

Ejecuta las siguientes pruebas contra un servidor conectado:

1. **ping** — GET /api/v1/status (fallback /api/ping)
2. **server/info** — GET /api/v1/server/info
3. **token_persistente** — Verifica que TokenStore tiene token
4. **tracks** — GET /api/v1/tracks
5. **stream/{id}** — GET /api/v1/stream/{id}
6. **artwork/{id}** — GET /api/v1/artwork/{id}
7. **playback/state** — GET /api/v1/playback/state
8. **playback/control (play)** — POST /api/v1/playback/control
9. **playback/control (pause)** — POST /api/v1/playback/control
10. **queue** — GET /api/v1/queue
11. **sync/manifest** — GET /api/v1/sync/manifest

## Reporte JSON

```json
{
  "device": "Google sdk_gphone64_x86_64 (API 36)",
  "server": "Michi Music Player",
  "baseUrl": "http://192.168.1.100:53318",
  "authStrategy": "PLAYER_PASSWORD",
  "tests": [
    {"name": "ping", "passed": true, "durationMs": 45},
    {"name": "server/info", "passed": true, "durationMs": 120},
    {"name": "token_persistente", "passed": true, "durationMs": 0},
    {"name": "tracks", "passed": true, "durationMs": 340},
    {"name": "stream/abc123", "passed": true, "durationMs": 1500},
    {"name": "artwork/def456", "passed": true, "durationMs": 200},
    {"name": "playback/state", "passed": true, "durationMs": 80},
    {"name": "playback/control (play)", "passed": true, "durationMs": 95},
    {"name": "playback/control (pause)", "passed": true, "durationMs": 90},
    {"name": "queue", "passed": true, "durationMs": 75},
    {"name": "sync/manifest", "passed": true, "durationMs": 500}
  ],
  "errors": [],
  "recommendations": [],
  "timestamp": 1712345678901
}
```

## Interpretación

- **Todos passed**: Servidor responde correctamente
- **ping falla**: Servidor no alcanzable en la red
- **server/info falla**: Servidor no implementa Michi Link API v1
- **token_persistente falla**: No hay emparejamiento activo
- **tracks falla**: Biblioteca no disponible o sin permisos
- **stream falla**: Verificar que el track existe y el servidor tiene acceso al archivo
- **artwork falla**: Verificar que la carátula existe
- **playback/state falla**: El servidor no expone estado de reproducción
- **playback/control falla**: El servidor no acepta comandos remotos
- **queue falla**: Cola no disponible
- **sync/manifest falla**: Sync no disponible o deviceId incorrecto
