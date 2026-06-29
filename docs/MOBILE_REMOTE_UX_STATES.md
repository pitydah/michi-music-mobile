# Michi Mobile — Remote UX States

## Estados del control remoto

| Estado | Condición | UI |
|--------|-----------|-----|
| **LOCAL** | Sin servidor conectado | "Reproduciendo en este teléfono" + botón "Sync" |
| **CONNECTED** | Polling exitoso | Controles completos: play/pause, seek, volume, queue |
| **UNAUTHORIZED** | 401 / token expirado | "Sesión expirada. Reconecta desde Sync." + botón "Ir a Sync" |
| **FORBIDDEN** | 403 / token revocado | "Acceso denegado por el servidor." |
| **OFFLINE** | Network error / timeout | "Servidor fuera de línea." |
| **INCOMPATIBLE** | Version mismatch | "Versión incompatible del servidor." |

## Componentes de UI por estado

### LOCAL
```
┌──────────────────────────────┐
│  Modo actual                 │
│  Reproduciendo en este       │
│  teléfono               [Sync]│
└──────────────────────────────┘
         📱
  Reproduciendo en este teléfono
  Abre NowPlaying para controlar
```

### CONNECTED
```
┌──────────────────────────────┐
│  Controlando: Michi Player   │
│  Sonando en: Living Hi-Fi    │
└──────────────────────────────┘
        [carátula]
      Título de canción
        Artista

  ═══════●═══════════════
  1:23              4:34

     ⏮    ▶/⏸    ⏭

  Vol ─────●──────────

  Cola
  ┌────────────────────┐
  │▶ Canción actual    │
  │  Artista           │
  ├────────────────────┤
  │  Siguiente         │
  │  Artista           │
  └────────────────────┘
```

### UNAUTHORIZED
```
┌──────────────────────────────┐
│  Controlando: Michi Player   │
└──────────────────────────────┘
        ❌ Sesión expirada
  Reconecta desde Sync.
        [Ir a Sync]
```

### FORBIDDEN
```
┌──────────────────────────────┐
│  Controlando: Michi Player   │
└──────────────────────────────┘
        🚫 Acceso denegado
    por el servidor.
```

### OFFLINE
```
┌──────────────────────────────┐
│  Controlando: Michi Player   │
└──────────────────────────────┘
        🌐 Servidor fuera
          de línea.
```

### INCOMPATIBLE
```
┌──────────────────────────────┐
│  Controlando: Michi Player   │
└──────────────────────────────┘
        ⚠️ Versión incompatible
     del servidor.
```

## Casos borde

| Caso | Comportamiento | UI esperada |
|------|---------------|-------------|
| `current_track` null | effectiveTitle = "" | "Sin reproducción" |
| `queue` vacía | queue.tracks.isEmpty() | No muestra sección de cola |
| `duration` = 0 | effectiveDuration = 0, slider usa 1 | Slider en 0, sin seek |
| `volume` = 0 (default) | effectiveVolume = 0 | Slider de volumen oculto si servidor no reporta volumen |
| Polling falla | Error en polling, keep connected | Muestra error pero mantiene controles |
| Comando falla | handleCmdError() | Muestra error temporal |
| Sin events | Polling 2s por defecto | Funcional sin events |

## Polling

- Intervalo: 2 segundos
- Método: `GET /api/v1/playback/state`
- Alternativo: `GET /api/v1/queue`
- Eventos SSE: `GET /api/v1/events` (opcional, no implementado en Player)
- Si events no está disponible, polling es el mecanismo por defecto
