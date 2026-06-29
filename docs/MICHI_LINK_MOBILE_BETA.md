# Michi Link Mobile — Beta Release

## Versión
v0.2.0-beta — Michi Link API v1.0.0-alpha

## Componentes

| Componente | Estado | Descripción |
|---|---|---|
| :michi-link-client | ✅ | Cliente HTTP único para Michi Link API v1 |
| :player | ✅ | Media3/ExoPlayer local |
| :data | ✅ | Room + MediaStore |
| :core | ✅ | Modelos de dominio |
| :app | ✅ | UI Compose + DI + Workers |

## Funcionalidades

### Reproducción offline
- Biblioteca local vía MediaStore
- Reproducción con ExoPlayer
- CoverFlow por álbum
- MiniPlayer flotante
- Cola de reproducción
- Seek, shuffle, repeat
- ReplayGain configurable
- Audio route detection

### Michi Link (sync/stream/remote)
- **Descubrimiento**: UDP multicast 224.0.0.167:53318
- **Server info**: `/api/v1/server/info` (tolerante a Player y Micro Server)
- **Pairing**: PLAYER_PASSWORD, SERVER_CODE, LEGACY (RECEIVER_BUTTON rechazado)
- **Token**: persistente en SharedPreferences, refresh soportado
- **Sync**: manifest + delta con cursor, descarga con checksum SHA-256
- **Streaming**: HTTP Range-Request, buffer 64KB
- **Control remoto**: polling 2s, play/pause/next/prev/seek/volume/queue
- **Cola remota**: tracks/current_index o items/currentIndex

### Pantallas

| Pantalla | Ruta | Offline | Online |
|----------|------|---------|--------|
| Home | `home` | ✅ | ✅ |
| Albums | `library` | ✅ | ✅ |
| NowPlaying | `nowplaying` | ✅ | ✅ |
| Playlist (Queue) | `playlist` | ✅ | ✅ |
| Remote Control | `remote` | ✅ | ✅ |
| Sync | `sync` | — | ✅ |
| Search | `search` | ✅ | ✅ |
| Settings | `settings` | ✅ | ✅ |
| Audio Route | `audio-route` | ✅ | ✅ |

### Endpoints Michi Link v1

| Endpoint | Método | Mobile | Player | Micro |
|----------|--------|--------|--------|-------|
| `GET /api/v1/status` | Health | ✅ | ✅ | ✅ |
| `GET /api/v1/server/info` | Info | ✅ | ✅ | ✅ |
| `POST /api/v1/pair/start` | Pair | ✅ | ✅ | ✅ |
| `POST /api/v1/pair/confirm` | Pair | ✅ | ✅ | ✅ |
| `POST /api/v1/token/refresh` | Token | ✅ | ❌(501) | ✅ |
| `GET /api/v1/tracks` | Library | ✅ | ✅ | ✅ |
| `GET /api/v1/library/stats` | Stats | ✅ | ✅ | ✅ |
| `GET /api/v1/search` | Search | ✅ | ✅ | ✅ |
| `GET /api/v1/stream/{id}` | Stream | ✅ | ✅ | ✅ |
| `GET /api/v1/artwork/{id}` | Artwork | ✅ | ✅ | ✅ |
| `GET /api/v1/sync/manifest` | Sync | ✅ | ✅ | ✅ |
| `GET /api/v1/sync/manifest/delta` | Delta | ✅ | ✅ | ✅ |
| `POST /api/v1/sync/state` | Sync | ✅ | ✅ | ✅ |
| `GET /api/v1/playback/state` | Remote | ✅ | ✅ | ✅ |
| `POST /api/v1/playback/control` | Remote | ✅ | ✅ | ✅ |
| `GET /api/v1/queue` | Queue | ✅ | ✅ | ✅ |
| `POST /api/v1/queue/jump` | Queue | ✅ | ✅ | ✅ |
| `POST /api/register` (legacy) | Legacy | ✅ | ✅ | ❌ |

## Cliente: Player

| Aspecto | Comportamiento |
|---|---|
| service | michi-music-player |
| auth.strategy | PLAYER_PASSWORD |
| auth.token_refresh | false |
| tokenRefreshSupported | false |
| Roles | desktop_player |
| Pairing | username + contraseña local del Player |
| Token | Se guarda en TokenStore |
| Refresh | No se intenta (flag false) |
| Sync | Manifest vía `/api/v1/sync/manifest` |
| Stream | `/api/v1/stream/{id}` |
| Control | polling `/api/v1/playback/state` + comandos |
| Queue | `GET /api/v1/queue` |

## Cliente: Micro Server

| Aspecto | Comportamiento |
|---|---|
| service | michi-micro-server |
| auth.strategy | SERVER_CODE |
| auth.token_refresh | true |
| tokenRefreshSupported | true |
| Roles | library_server, stream_server |
| Pairing | Código de emparejamiento (PIN) |
| Token | device_token + refresh_token |
| Refresh | Se intenta automáticamente si el token actual falla |
| Sync | Manifest + delta con cursor |
| Download | `/api/v1/stream/{id}` con HTTP Range |
| Control | polling `/api/v1/playback/state` + comandos |
| Queue | `GET /api/v1/queue` |

## Limitaciones actuales

- No hay soporte para Michi Stream como destino de audio (futura salida)
- No hay transcodificación (perfiles original, mobile_balanced, mobile_low_storage declarados pero no implementados)
- No hay importación USB/MTP (base preparada en LinkPackageImporter)
- No hay eventos SSE (`GET /api/v1/events` no implementado en Player)
- No hay rooms/zones
- TokenStore usa SharedPreferences (no EncryptedSharedPreferences — TODO pendiente)

## Dependencias externas

| Librería | Versión | Uso |
|---|---|---|
| Kotlin | 2.0.20 | Lenguaje |
| Jetpack Compose | BOM 2024.09 | UI |
| Ktor | 3.0.1 | HTTP client |
| Kotlinx Serialization | 1.7.3 | JSON |
| Room | 2.6.1 | DB local |
| Media3 / ExoPlayer | 1.4.1 | Audio |
| Koin | 4.0.0 | DI |
| Coil | 3.0.4 | Imágenes |
| WorkManager | 2.9.1 | Sync background |

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleNormalDebug
```

APK: `app/build/outputs/apk/normal/debug/app-normal-debug.apk`

## Próximos pasos (post-beta)

- EncryptedSharedPreferences para tokens
- Soporte de eventos SSE
- Implementación de perfiles de calidad
- UI de importación USB/MTP
- Soporte de rooms/zones
- Transcodificación del lado del servidor
