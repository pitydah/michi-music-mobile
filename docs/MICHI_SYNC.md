# Michi Sync — Protocol & Client Design

## Overview

Michi Sync enables wireless music transfer between Michi Music Player (desktop/KDE) and Michi Music Mobile (Android). The protocol is inspired by [LocalSend](https://github.com/localsend/localsend).

## Protocol Stack

```
┌─────────────────────────────────────┐
│         Application Layer           │
│  Register / Library / Stream / Sync │
├─────────────────────────────────────┤
│         Transport: HTTP/1.1         │
├─────────────────────────────────────┤
│       Discovery: UDP Multicast      │
│          224.0.0.167:53318          │
└─────────────────────────────────────┘
```

## Discovery (UDP Multicast)

- Android joins multicast group `224.0.0.167:53318`
- KDE announces every 5 seconds with `AnnounceMessage`
- Android receives announcements → shows available devices
- Announcements include: alias, device type, port, version

## Registration

Once a KDE server is discovered:

```
POST /api/register
Content-Type: application/json

{
  "alias": "MyPhone",
  "device": "android",
  "device_model": "Pixel 8",
  "client_device_id": "uuid-of-phone"
}

Response 200:
{
  "session_token": "hex-token-64-chars",
  "server_device_id": "uuid-of-desktop",
  "client_device_id": "uuid-of-phone",
  "library_size": 1234,
  "version": "1.0"
}
```

All subsequent requests include: `Authorization: Bearer <session_token>`

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/ping` | Health check |
| GET | `/api/library` | Full library (tracks, artists, albums) |
| GET | `/api/stream/{id}` | Audio file streaming (Range-Request) |
| GET | `/api/cover/{id}` | Album art image |
| POST | `/api/sync/state` | Send play counts & favorites |
| GET | `/api/search?q=X` | Search library |
| GET | `/api/sync/manifest?device_id=X` | Sync manifest for differential sync |

## Streaming Protocol (Range-Request)

```
GET /api/stream/{track_hash}
Range: bytes=0-65535

Response 206:
Content-Type: audio/flac
Content-Range: bytes 0-65535/12345678
Content-Length: 65536

[bytes 0-65535]
```

Android downloads in chunks for progressive playback and resume support.

## Sync Flow (Android → KDE)

```
POST /api/sync/state
Authorization: Bearer <token>
Content-Type: application/json

{
  "session_token": "...",
  "tracks": [
    { "track_id": "abc", "play_count": 42, "favorite": true },
    { "track_id": "def", "play_count": 7, "favorite": false }
  ]
}
```

## Remote Control Endpoints

Future endpoints on KDE for Android remote control:

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/status` | Current playback state |
| POST | `/api/play` | Play |
| POST | `/api/pause` | Pause |
| POST | `/api/next` | Next track |
| POST | `/api/prev` | Previous track |
| POST | `/api/seek?pos=X` | Seek to position (seconds) |
| POST | `/api/volume?level=X` | Set volume (0-100) |
| GET | `/api/queue` | Current queue |

## Android Implementation

### DiscoveryClient
- Uses `MulticastSocket` to join group and listen for announcements
- Runs on `Dispatchers.IO` via coroutines
- Emits discovered/lost devices via `SharedFlow`

### MichiSyncClient
- Ktor `HttpClient` with `ContentNegotiation` + `kotlinx.serialization`
- Handles register, library fetch, streaming with Range-Request
- Session token stored in memory + encrypted SharedPreferences

### SyncTransferManager
- Manages download queue
- Tracks downloaded files by hash
- Supports pause/resume of transfers
- Uses `CoroutineScope` with `Dispatchers.IO`
