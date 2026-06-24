# KDE Integration — Michi Music Mobile ↔ Michi Music Player

## Architecture

```
┌────────────────────────┐       HTTP (port 53318)       ┌──────────────────────┐
│   Michi Music Mobile   │ ◄──────────────────────────►  │ Michi Music Player   │
│   (Android)            │      Bearer token auth         │ (KDE / Linux)        │
│                        │                                │                      │
│  ┌──────────────────┐  │  POST /api/register           │  ┌────────────────┐  │
│  │ MichiSyncClient  │──┼──────────────────────────────►│  │ SyncServer     │  │
│  │ (OkHttp)         │◄─┼───────────────────────────────│  │ (Python httpd) │  │
│  │                  │  │  GET /api/library              │  │ port 53318     │  │
│  │  /api/register   │──┼──────────────────────────────►│  │                │  │
│  │  /api/library    │◄─┼───────────────────────────────│  │  /register     │  │
│  │  /api/stream     │  │  GET /api/stream/{id}         │  │  /library      │  │
│  │  /api/cover      │──┼──────────────────────────────►│  │  /stream       │  │
│  │  /api/sync/manifest│◄┼──────────────────────────────│  │  /cover        │  │
│  │  /api/sync/state │  │  POST /api/sync/state         │  │  /sync/manifest│  │
│  └──────────────────┘  │◄─┼──────────────────────────────│  /sync/state    │  │
│                        │  │                             │  └────────────────┘  │
│  ┌──────────────────┐  │  │  UDP multicast              │  ┌────────────────┐  │
│  │ DiscoveryClient  │──┼──┼──────────────────────────►  │  │ DiscoveryServer│  │
│  │ (UDP multicast)  │◄─┼──┼─────────────────────────────│  │ (224.0.0.167)  │  │
│  └──────────────────┘  │  │                             │  └────────────────┘  │
│                        │  │                             │                      │
│  ┌──────────────────┐  │  │                             │  ┌────────────────┐  │
│  │ Room Database    │  │  │                             │  │ DeviceRegistry │  │
│  │ (cached_tracks)  │  │  │                             │  │ (paired devices)│  │
│  └──────────────────┘  │  │                             │  └────────────────┘  │
│                        │  │                             │                      │
│  ┌──────────────────┐  │  │                             │  ┌────────────────┐  │
│  │ Media3 Service   │  │  │                             │  │ LibraryDB      │  │
│  │ (future)         │  │  │                             │  │ (SQLite FTS5)  │  │
│  └──────────────────┘  │  │                             │  └────────────────┘  │
└────────────────────────┘  │                             └──────────────────────┘
```

## KDE Source Files

The integration points in `michi-music-player`:

| KDE File | Role | Android Counterpart |
|----------|------|---------------------|
| `sync/sync_protocol.py` | DTO definitions, track ID hashing | `sync/data/SyncDtos.kt` |
| `sync/sync_server.py` | HTTP server (register, library, stream, cover, sync) | `sync/data/MichiSyncApiClient.kt` |
| `sync/sync_discovery.py` | UDP multicast discovery | (future `DiscoveryClient.kt`) |
| `ui/services/device_sync_controller.py` | Device pairing, manifest generation | (future `SyncViewModel.kt`) |
| `ui/services/sync_manifest_builder.py` | Manifest creation from library/favorites/playlist | (consumes via `/api/sync/manifest`) |
| `ui/services/transfer_backends.py` | Transfer backend abstraction (wireless, MTP, filesystem) | (wireless via OkHttp) |
| `ui/services/device_registry.py` | Paired device storage | `sync/data/DeviceIdentity.kt` |
| `ui/devices_page.py` | KDE Devices UI: pair, sync, manage | `sync/presentation/SyncScreen.kt` |

## Protocol Compatibility

Both sides use the same protocol defined in `sync/sync_protocol.py`:

- JSON payloads with snake_case field names
- Bearer token authentication (64-char hex, sessions expire after 1 hour)
- SHA-256 for track IDs (first 16 hex chars)
- Port 53318

## Match Android → KDE

### Register Request
```json
{"alias":"Pixel 8","device":"android","device_model":"Pixel 8","client_device_id":"a1b2c3d4"}
```
KDE responds with `session_token`, `server_device_id`, `client_device_id`, `library_size`.

### Library Response
```json
{"tracks":[{"id":"a1b2...","title":"...","artist":"...","album":"...","duration":240,"size":12345678,
  "format":"FLAC","bitrate":1411000,"sample_rate":44100,"channels":2,"cover_id":"md5hash",
  "track_number":3,"year":2020}],"total":1234,"artists":50,"albums":80}
```

### Sync Manifest
```json
{"manifest_id":"abc123","device_id":"sync_phone","created_at":"2025-01-15T10:30:00",
 "total_tracks":50,"total_size":524288000,
 "tracks":[{"track_id":"...","title":"...","artist":"...","album":"...","size":...,
   "format":"FLAC","duration":240,"year":2020,"cover_id":"...","checksum":"sha256...",
   "download_path":"/api/stream/..."}]}
```

## Track ID Generation

Both sides must generate the same track ID for the same file:

```python
# KDE (Python)
def make_track_id(filepath: str) -> str:
    return hashlib.sha256(filepath.encode()).hexdigest()[:16]
```

```kotlin
// Android (Kotlin)
fun makeTrackId(filepath: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(filepath.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}
```

## Discovery (Future)

UDP multicast on `224.0.0.167:53318`. KDE announces every 5 seconds with:
```json
{"type":"announce","alias":"MichiMusicPlayer","device":"desktop","port":53318,
 "version":"1.0","device_model":"","device_id":""}
```

Android listens, shows available servers, user taps to connect.
