# Remote Control Flow

## Arquitectura

```
┌─────────────────────────────────────────────────────┐
│                    RemoteScreen                       │
│  (Compose UI)                                        │
│  - ModeSelector (LOCAL/REMOTE)                       │
│  - PlaybackControls (play/pause/next/prev/seek/vol)  │
│  - QueueList (tracks + currentIndex)                 │
│  - ErrorDisplay                                      │
└──────────────────────┬──────────────────────────────┘
                       │ collectAsStateWithLifecycle()
                       ▼
┌─────────────────────────────────────────────────────┐
│                RemoteViewModel                        │
│  - connectIfNeeded()                                 │
│  - startPolling() cada 2s                            │
│  - sendCommand() / sendSeek() / sendSetVolume()      │
│  - queueJump()                                       │
│  - disconnect() / retry()                            │
└──────────────────────┬──────────────────────────────┘
                       │ LinkClient calls
                       ▼
┌─────────────────────────────────────────────────────┐
│                    LinkClient                         │
│  - getPlaybackState() → GET /api/v1/playback/state  │
│  - sendPlaybackCommand() → POST /api/v1/playback    │
│  - sendSeek() → POST /api/v1/playback/control       │
│  - sendSetVolume() → POST /api/v1/playback/control  │
│  - getQueue() → GET /api/v1/queue                   │
│  - queueJump() → POST /api/v1/queue/jump            │
└─────────────────────────────────────────────────────┘
```

## Estados del control remoto

### LOCAL
```
Modo actual: Reproduciendo en este teléfono
[ Botón: Sync ]
```
- No se necesita servidor
- Controles locales vía AudioController
- RemoteScreen muestra mensaje y redirige a NowPlaying

### REMOTE - Conectado
```
Controlando: <nombre del servidor>
Sonando en: <salida>

[carátula]
<título>
<artista>

[progreso: ═══●═══════════════]
[⏮] [▶/⏸] [⏭]

[Vol: ───●───────────]

[Cola]
┌────────────────────────────┐
│ ▶ Canción actual           │ ← highlight
│    Artista                 │
├────────────────────────────┤
│    Siguiente canción       │
│    Artista                 │
└────────────────────────────┘
```

### REMOTE - Sin emparejar
```
Controlando: <servidor>
Error: Se requiere emparejamiento
[ Botón: Ir a Sync ]
```

### REMOTE - Sin permisos
```
Controlando: <servidor>
Error: Acceso denegado por el servidor
[ Botón: Olvidar servidor ]
```

### REMOTE - Token vencido
```
Controlando: <servidor>
Error: Sesión expirada. Reconecta desde Sync.
[ Botón: Ir a Sync ]
```

### REMOTE - Servidor fuera de línea
```
Controlando: <servidor>
Error: Network error
[ Polling se detiene ]
```

## Flujo de comandos

### Play/Pause
```
Usuario → RemoteScreen → click ▶/⏸
→ RemoteViewModel.togglePlayPause()
→ LinkClient.sendPlaybackCommand("play" | "pause")
→ POST /api/v1/playback/control { "command": "play|pause" }
→ Servidor responde 200
→ RemoteViewModel actualiza estado via polling
```

### Seek
```
Usuario → RemoteScreen → drag slider
→ onValueChangeFinished
→ RemoteViewModel.seek(positionMs)
→ LinkClient.sendSeek(positionMs)
→ POST /api/v1/playback/control { "command": "seek", "position_ms": 90000 }
→ Servidor responde 200
→ RemoteViewModel actualiza progreso via polling
```

### Volumen
```
Usuario → RemoteScreen → drag volume slider
→ onValueChange
→ RemoteViewModel.setVolume(70)
→ LinkClient.sendSetVolume(70)
→ POST /api/v1/playback/control { "command": "set_volume", "volume": 70 }
→ Servidor responde 200
→ RemoteViewModel actualiza volumen localmente
```

### Queue Jump
```
Usuario → RemoteScreen → click item en cola
→ RemoteViewModel.queueJump(index)
→ LinkClient.queueJump(index)
→ POST /api/v1/queue/jump { "index": 2 }
→ Servidor cambia a canción en índice 2
→ RemoteViewModel actualiza estado via polling
```

## Polling

- Intervalo: 2 segundos
- Método: `GET /api/v1/playback/state`
- Alternativo: `GET /api/v1/queue`
- Se detiene si:
  - 401 → sesión expirada, se pide pairing
  - Error de red → servidor fuera de línea
  - `disconnect()` llamado

## Seguridad

- Todas las llamadas protegidas envían:
  - `Authorization: Bearer <deviceToken>`
  - `X-Michi-Device-Id: <clientDeviceId>`
- 401 → LinkException.Unauthorized → pedir pairing
- 403 → LinkException.Revoked → borrar token

## Comandos soportados

| Comando | Método | Payload |
|---------|--------|---------|
| play | sendPlaybackCommand | `{"command":"play"}` |
| pause | sendPlaybackCommand | `{"command":"pause"}` |
| toggle | sendPlaybackCommand | `{"command":"toggle"}` |
| next | sendPlaybackCommand | `{"command":"next"}` |
| previous | sendPlaybackCommand | `{"command":"previous"}` |
| stop | sendPlaybackCommand | `{"command":"stop"}` |
| seek | sendSeek | `{"command":"seek","position_ms":90000}` |
| set_volume | sendSetVolume | `{"command":"set_volume","volume":70}` |
| mute | sendPlaybackCommand | `{"command":"mute"}` |
| unmute | sendPlaybackCommand | `{"command":"unmute"}` |
| jump | queueJump | `{"index":2}` |
