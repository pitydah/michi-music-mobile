# Architecture — Michi Music Mobile

## 1. Role in the Michi Ecosystem

Michi Music Mobile is the **portable node** of the [Michi Music Ecosystem](https://github.com/pitydah).

Its core responsibilities are:
- **PLAY**: Bit-accurate, high-fidelity local audio playback via AndroidX Media3 (ExoPlayer + PCM ReplayGain + Audio Effects HAL + USB DAC).
- **STREAM**: Decoded PCM audio streaming to physical Michi Stream receivers (ESP32-S3 + DAC) via standard RTP (RFC 3550) over UDP with dynamic audio negotiation.
- **ACCESS**: Continuous remote access, catalog search, and audio streaming from Michi Micro Server / Big Server.
- **CONTROL**: Remote playback and queue control of Desktop Player, Servers, and physical Michi Stream nodes.
- **CONTINUE**: Seamless handoff of logical playback sessions between devices (Mobile $\leftrightarrow$ Server $\leftrightarrow$ Michi Stream).
- **CARRY**: Resilient offline library storage and synchronization via Michi Sync.

---

## 2. Module Architecture

```
┌───────────────────────────────────────────────────────────┐
│                           :app                            │
│  Navigation (MichiNavHost) · DI (Koin) · ViewModels       │
│  PlaybackSessionManager (Authoritative Session Truth)     │
│  Screens: Home · Albums (CoverFlow) · Search · NowPlaying │
│  Settings · Remote · Sync · Devices · SyncedTracks        │
│  Background: SyncWorker (WorkManager)                     │
├───────────────────────────────────────────────────────────┤
│                          :player                          │
│  MichiPlaybackService (MediaLibraryService)               │
│  ExoPlayer + ReplayGainAudioProcessor (PCM ±dB)           │
│  RtpPcmAudioTap (AudioProcessor real-time PCM interceptor)│
│  MichiAudioEffects (Equalizer, BassBoost, Virtualizer)    │
│  UsbDacManager (USB Device / Headset Routing)             │
│  AudioController & PlayerDependencies                     │
├─────────────────────────────┬─────────────────────────────┤
│            :data            │     :michi-link-client      │
│  MediaQueryDispatcher       │  LinkClient (Ktor HTTP REST)│
│  Room (MichiDatabase)       │  EventClient (SSE Events)   │
│  LocalMediaRepository       │  LinkDiscovery (UDP 53318)  │
│  SyncedTrackRepository      │  ConnectionManager          │
│                             │  RtpAudioSender (RFC 3550)  │
│                             │  AudioProfileNegotiator     │
│                             │  TokenStore & DeviceRegistry│
│                             │  MichiIdentity (Ed25519)    │
├─────────────────────────────┴─────────────────────────────┤
│                           :core                           │
│  Canonical Domain Models: Track, Album, Artist, Playlist  │
│  DiscoveredPeer, SyncConnectionState, Enums               │
└───────────────────────────────────────────────────────────┘
```

---

## 3. Playback Architecture & RTP Pipeline (`:player` + `:app`)

### Single Session Authority: `PlaybackSessionManager`
`PlaybackSessionManager` is the single source of truth for playback session state in the application:
- Unifies local playback and remote targets into a single state stream (`sessionState`).
- Manages endpoint selection (`LOCAL_PHONE`, `DESKTOP_PLAYER`, `SERVER`, `STREAM_RECEIVER`, `ROOM`, `UNKNOWN`).
- Coordinates lossless session handoff:
  - **Local $\rightarrow$ Server / Player (`/queue/transfer`)**: Resolves track identity via canonical multi-criteria matcher (`title`, `artist`, `durationMs` $\pm 3\text{ s}$) and safely aborts if local tracks cannot be resolved in the server catalog.
  - **Local $\rightarrow$ Michi Stream (`ReceiverLite`)**: Negotiates audio profile, establishes session, intercepts decoded PCM from ExoPlayer via `RtpPcmAudioTap`, and sends continuous RFC 3550 RTP packets over UDP.

### Audio Pipeline & HAL
1. **Audio Attributes**: `AudioAttributes.DEFAULT` with automatic audio focus handling.
2. **ReplayGain**: PCM-level volume normalization via `ReplayGainAudioProcessor`, supporting negative attenuation and positive pre-amp gain without clipping.
3. **Hardware Effects**: `MichiAudioEffects` manages Android HAL `Equalizer`, `BassBoost`, and `Virtualizer` attached to the active `audioSessionId`.
4. **USB DAC Direct Output**: `UsbDacManager` monitors `AudioDeviceCallback` and routes playback through `AudioDeviceInfo.TYPE_USB_DEVICE` and `TYPE_USB_HEADSET`.
5. **RTP PCM Tap**: `RtpPcmAudioTap` intercepts decoded PCM chunks from ExoPlayer pipeline and delivers format-compliant bytes to `RtpAudioSender`. Supports pause/resume gating to prevent buffer overflows when receiver is paused.

---

## 4. Michi Link Client (`:michi-link-client`)

Michi Link is the official interoperability contract across all ecosystem components.

### 1. Unified Discovery & Network Ports
- **Discovery**: UDP multicast on `224.0.0.167:53318` (`LinkDiscovery`). Nodes announce themselves with alias, IP, port, device type, version, and auth requirement.
- **Default REST Port**: `53318` (or dynamic port broadcast during discovery).
- **RTP Audio Stream Ports**: Dynamic UDP port in the ephemeral range `49152..65535` negotiated via `POST /api/v1/receiver-lite/session`.

### 2. Cryptographic Identity & Pinning
- **Identity Scheme**: `ed25519-blake3-v1` managed by `MichiIdentity`.
- **Identity Pinning**: On reconnection, `ConnectionManager` verifies that the remote node's `michi_id`, `server_id`, and `public_key` strictly match the pinned credentials saved during pairing. If mismatch is detected, connection is rejected with `SyncConnectionState.UNAUTHORIZED`.
- **Header Isolation**: Requests send `X-Michi-Device-Id: <pairedClientDeviceId>` and `Authorization: Bearer <deviceToken>`. Connecting to Device A never leaks or reuses credentials of Device B.

### 3. Canonical Pairing Strategies
The client implements an exhaustive pairing state machine across 5 strategies:
1. `SERVER_CODE`: Server displays numeric PIN; mobile confirms with cryptographic signature + PIN.
2. `RECEIVER_BUTTON`: Physical button on Michi Stream initiates pairing; mobile confirms with challenge signature + PIN.
3. `ED25519_CHALLENGE`: Cryptographic challenge signature without manual PIN input.
4. `PLAYER_PASSWORD`: Direct username/password authentication for desktop players.
5. `LEGACY`: Fallback credentials for legacy servers.

### 4. Michi Stream Protocol (`ReceiverLite`)
Official lightweight protocol for streaming to embedded receivers (ESP32-S3 + DAC):
- **Audio Negotiation**: `AudioProfileNegotiator` reads `ServerInfo.audio` (`AudioCapabilitiesDto`) and selects optimal configuration (Standard 16-bit / 48 kHz vs Hi-Fi 24-bit / 96 kHz).
- **Session Lifecycle**:
  - `POST /api/v1/receiver-lite/session`: Creates session, returns `session_id`, `session_token`, `lease_seconds`, and `effective` audio parameters.
  - `POST /api/v1/receiver-lite/heartbeat`: Periodic heartbeat (`lease_seconds / 3`) sending `X-Michi-Session` header.
  - `PATCH /api/v1/receiver-lite/session`: Dynamic volume adjustment and pause/resume control.
  - `DELETE /api/v1/receiver-lite/session`: Graceful teardown. Switching between streams automatically tears down previous session before establishing a new one.
- **RTP Audio Transmission**: `RtpAudioSender` packages PCM chunks into RFC 3550 packets (Payload Type 97, 12-byte header, monotonically increasing sequence numbers and sample timestamps) and sends via UDP.

### 5. Real-time Events & Token Refresh
- **Server-Sent Events (`EventClient`)**: Connects to `/api/v1/events` for real-time playback state synchronization.
- **Automatic Token Refresh**: Automatically uses `POST /api/v1/auth/refresh` on HTTP 401 and updates persistent token registry.

---

## 5. Offline Synchronization (`:data` + `:app`)

- `SyncedTrackRepository`: Stores synchronized tracks and manifest metadata in SQLite via Room (`CachedTrack`).
- `SyncWorker`: Executes background transfers using `WorkManager` with network constraint policies and automatic resumption.
- `Paging 3`: Powers virtualized scrolling in large synced libraries with minimal memory footprint.

---

## 6. Design System: Digital Ethereal

- **Color Palette**: Obsidian Base (`#090B11`), Smoked Glass (`0x1FFFFFFF`), Emotional Pink (`#FF6B9D`), Cyan Feedback (`#4DEEEA`).
- **Typography & Geometry**: Clean sans-serif hierarchy, 14–26dp corner radii, responsive layout adaptation.
- **Truthful Affordances**: Zero placebo controls; all UI elements directly reflect real player/system states.
