# Architecture — Michi Music Mobile

## Overview

Michi Music Mobile is an Android companion app for Michi Music Player (KDE/Linux).
It syncs music wirelessly from a KDE desktop to an Android device over the local network.

```
┌─────────────────────────────────────────────────┐
│                  Presentation                    │
│   SyncScreen  ·  LibraryScreen  ·  NowPlaying   │
│   (Compose + Material 3)                        │
├─────────────────────────────────────────────────┤
│                  Application                    │
│   Navigation  ·  DI (Koin)  ·  ViewModels       │
├─────────────────────────────────────────────────┤
│               Sync Client                       │
│   MichiSyncApiClient (OkHttp)                   │
│   DiscoveryClient (UDP multicast)               │
│   SyncDtos (kotlinx.serialization)              │
├─────────────────────────────────────────────────┤
│                Data Layer                       │
│   Room Database  ·  TrackDao  ·  Repositories   │
├─────────────────────────────────────────────────┤
│                Player (future)                  │
│   Media3 / ExoPlayer  ·  MediaSessionService    │
├─────────────────────────────────────────────────┤
│                   Core                          │
│   Domain models  ·  DTOs  ·  Utilities          │
└─────────────────────────────────────────────────┘
```

## Modules

| Module | Responsibility | Depends on |
|--------|---------------|------------|
| `:app` | UI, navigation, DI wiring, sync screen | `:core`, `:data`, `:sync-client`, `:player`, `:remote` |
| `:core` | Domain models (`Track`, `Album`, `Artist`, DTOs) | — |
| `:data` | Room database, DAOs, repositories | `:core` |
| `:sync-client` | OkHttp/Ktor HTTP client, UDP discovery, transfer manager | `:core`, `:data` |
| `:player` | Media3 ExoPlayer service, playback state | `:core` |
| `:remote` | Remote control client for KDE (future) | `:core` |

## Sync Architecture

### Registration Flow
1. Android sends `POST /api/register` with device info
2. KDE returns a `session_token` (64-char hex Bearer token)
3. Android stores token in memory + SharedPreferences
4. All subsequent requests include `Authorization: Bearer <token>`

### Library Sync Flow
1. Android calls `GET /api/library` with Bearer token
2. KDE returns full library as JSON (TrackDto array)
3. Android saves to Room database (`cached_tracks` table)
4. Optional: fetch sync manifest via `GET /api/sync/manifest?device_id=X`
5. Download tracks via `GET /api/stream/{track_id}` with Range-Request support

### Streaming Protocol
- Android uses Range-Request headers for progressive download / resume
- Default chunk size: 65 KB
- Server responds with `206 Partial Content` + `Content-Range`
- MIME types: `audio/flac`, `audio/mpeg`, `audio/ogg`, `audio/wav`, `audio/mp4`, etc.

### State Sync (bidirectional)
- Android sends `POST /api/sync/state` with play counts and favorites
- KDE updates its database and returns `{"synced": N}`

## Key Design Decisions

### OkHttp over platform HTTP
- Explicit connection timeouts (10s connect, 30s read/write)
- Manual Range-Request handling for streaming
- Compatible with coroutines via `withContext(Dispatchers.IO)`

### Room for cache
- Synced library stored in `michi-sync.db`
- Track metadata + download status
- Album art cached separately on filesystem

### UDP Discovery (future)
- Multicast group `224.0.0.167:53318`
- MulticastLock via WifiManager to prevent packet loss in sleep
- 15-second peer timeout before marking as lost

## Related Documentation

- [MICHI_SYNC_PROTOCOL.md](MICHI_SYNC_PROTOCOL.md) — Wire protocol specification
- [KDE_INTEGRATION.md](KDE_INTEGRATION.md) — Integration details with KDE codebase
- [AUDIO_ROUTE_LDAC.md](AUDIO_ROUTE_LDAC.md) — Bluetooth Hi-Res output design
