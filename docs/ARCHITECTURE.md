# Architecture — Michi Music Mobile

## 1. Role in the Michi Ecosystem

Michi Music Mobile is the **portable node** of the [Michi Music Ecosystem](https://github.com/pitydah).

Its core responsibilities are:
- **PLAY**: High-fidelity local audio playback via AndroidX Media3 (ExoPlayer + PCM16 ReplayGain + Audio Effects HAL + USB DAC). *(Implemented & Unit Tested)*
- **STREAM**: Decoded PCM audio streaming to physical Michi Stream receivers (ESP32-S3 + DAC) via standard RTP (RFC 3550) over UDP with dynamic 16-bit audio negotiation and runtime format renegotiation. *(Implemented & Unit Tested)*
- **ACCESS**: Continuous remote access, catalog search, and audio streaming from Michi Micro Server / Big Server. *(Implemented & Tested)*
- **CONTROL**: Remote playback and queue control of Desktop Player, Servers, and physical Michi Stream nodes. *(Implemented & Tested)*
- **CONTINUE**: Coordinated handoff of logical playback sessions between devices (Mobile $\leftrightarrow$ Server $\leftrightarrow$ Michi Stream). *(Implemented & Tested)*
- **CARRY**: Resilient offline library storage and synchronization via Michi Sync. *(Implemented & Tested)*

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
│  ExoPlayer + ReplayGainAudioProcessor (PCM16 ±dB)         │
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
│  AudioModels (PcmFormat), DiscoveredPeer, SyncStates      │
└───────────────────────────────────────────────────────────┘
```

---

## 3. Playback Architecture & RTP Pipeline (`:player` + `:app`)

### Single Session Authority: `PlaybackSessionManager`
`PlaybackSessionManager` is the single source of truth for playback session state in the application:
- Unifies local playback and remote targets into a single state stream (`sessionState`).
- Manages endpoint selection (`LOCAL_PHONE`, `DESKTOP_PLAYER`, `SERVER`, `STREAM_RECEIVER`, `ROOM`, `UNKNOWN`).
- Coordinates session handoff:
  - **Local $\rightarrow$ Server / Player (`/queue/transfer`)**: Resolves track identity via multi-criteria fallback resolver (`title`, `artist`, `durationMs` $\pm 3\text{ s}$) and safely aborts if local tracks cannot be resolved in the server catalog.
  - **Local $\rightarrow$ Michi Stream (`ReceiverLite`)**: Negotiates audio profile (Standard PCM16 / 48 kHz or 44.1 kHz), establishes session, verifies RTP socket activation before intercepting PCM, and sends continuous RFC 3550 RTP packets over UDP.
  - **Dynamic Format Renegotiation**: Listens to `PlayerDependencies.pcmFormatFlow`. When a song transition changes the underlying audio clock (e.g. 44.1 kHz $\leftrightarrow$ 48 kHz), it pauses PCM tap feeding, tears down the previous ReceiverLite session, renegotiates the new profile, starts the new RTP sender, and resumes streaming cleanly without pitch/speed drift.

### Audio Pipeline & HAL
1. **Audio Attributes**: `AudioAttributes.DEFAULT` with automatic audio focus handling.
2. **ReplayGain**: PCM-level volume normalization via `ReplayGainAudioProcessor` (16-bit PCM verified pipeline).
3. **Hardware Effects**: `MichiAudioEffects` manages Android HAL `Equalizer`, `BassBoost`, and `Virtualizer` attached to the active `audioSessionId`.
4. **USB DAC Direct Output**: `UsbDacManager` monitors `AudioDeviceCallback` and routes playback through `AudioDeviceInfo.TYPE_USB_DEVICE` and `TYPE_USB_HEADSET`.
5. **RTP PCM Tap & Speaker Muting**: `RtpPcmAudioTap` intercepts decoded PCM chunks from ExoPlayer pipeline and delivers format-compliant bytes to `RtpAudioSender`. While streaming to Michi Stream, phone speakers are muted (`muteLocalOutput = true`) while PCM continues flowing to the remote receiver.

---

## 4. Michi Link Client (`:michi-link-client`)

Michi Link is the official interoperability contract across all ecosystem components.

### 1. Unified Discovery & Network Ports
- **Discovery**: UDP multicast on `224.0.0.167:53318` (`LinkDiscovery`). Nodes announce themselves with alias, IP, port, device type, version, and auth requirement.
- **REST Port**: Explicit port from discovery announcement or manual entry (`8400` general default in Michi Link specification; `8500` for Micro Server).
- **RTP Audio Stream Ports**: Dynamic UDP port in the ephemeral range `49152..65535` negotiated via `POST /api/v1/receiver-lite/session`.

### 2. Cryptographic Identity & Pinning
- **Identity Scheme**: `ed25519-blake3-v1` managed by `MichiIdentity`.
- **Identity Pinning**: On reconnection, `ConnectionManager` verifies that the remote node's `michi_id`, `server_id`, and `public_key` strictly match the pinned credentials saved during pairing. If mismatch is detected, connection is rejected with `SyncConnectionState.UNAUTHORIZED`.
- **Header Isolation**: Requests send `X-Michi-Device-Id: <pairedClientDeviceId>` and `Authorization: Bearer <deviceToken>`. Connecting to Device A never leaks or reuses credentials of Device B.

### 3. Canonical Pairing Strategies & Legacy Profiles
The client implements an exhaustive pairing state machine:
- **Canonical v1 Cryptographic Handshake**:
  1. `ED25519_CHALLENGE`: Cryptographic challenge-response authorization without manual PIN (`pin = null`).
  2. `SERVER_CODE`: Server displays numeric PIN; mobile confirms with cryptographic signature + PIN string.
  3. `RECEIVER_BUTTON`: Physical button press on Michi Stream initiates pairing; mobile confirms with challenge signature + PIN.
- **Legacy Compatibility Profile**:
  4. `PLAYER_PASSWORD` / `LEGACY`: Explicit legacy compatibility profile for username/password authentication with desktop players, isolated from canonical v1 cryptographic pairing.

### 4. Michi Stream Protocol (`ReceiverLite`)
Official lightweight protocol for streaming to embedded receivers (ESP32-S3 + DAC):
- **Audio Negotiation**: `AudioProfileNegotiator` strictly intersects real local PCM format against receiver capabilities (`pcm_s16le`, 48 kHz / 44.1 kHz, 10ms packets), strictly returning `null` if unsupported (zero fictional fallbacks).
- **Session Lifecycle & Dynamic Format Transitions**:
  - `POST /api/v1/receiver-lite/session`: Creates session, returns `session_id`, `session_token`, `lease_seconds`, and `effective` audio parameters.
  - `POST /api/v1/receiver-lite/heartbeat`: Periodic heartbeat (`lease_seconds / 3`) sending `X-Michi-Session` header.
  - `PATCH /api/v1/receiver-lite/session`: Dynamic volume adjustment and pause/resume control.
  - `DELETE /api/v1/receiver-lite/session`: Graceful teardown. Switching between streams automatically tears down previous session before establishing a new one.
  - **Dynamic Renegotiation**: When track format changes (e.g. 44.1 kHz $\leftrightarrow$ 48 kHz), the audio pipeline enforces an atomic lock (`RtpPcmAudioTap.isRenegotiating`), pauses playback, tears down the old session, renegotiates the new profile with the receiver, starts the new RTP sender, and re-enables PCM playback. (Note: currently uses a safe pause/resume window; future phases can upgrade to a 250-500ms ring buffer for gapless playback).
- **RTP Audio Transmission**: `RtpAudioSender` packages PCM chunks into RFC 3550 packets (Payload Type 97, 12-byte header, monotonically increasing sequence numbers and sample timestamps) and sends via UDP with persistent chunk accumulation, session buffer isolation, and metrics tracking for sustained consecutive backpressure detection.

### 5. Real-time Events & Token Refresh
- **Server-Sent Events (`EventClient`)**: Connects to `/api/v1/events` for real-time playback state synchronization.
- **Automatic Token Refresh**: Automatically uses `POST /api/v1/auth/refresh` on HTTP 401 and updates persistent token registry.

---

## 5. Verification Status & Evidence Hierarchy

To maintain complete engineering truthfulness, features are tracked against four distinct evidence levels:
1. **Implemented**: Code and data contracts fully written.
2. **Unit Tested**: 100% covered by deterministic unit/mock tests.
3. **Network E2E**: Verified against real network protocols and simulated endpoints.
4. **Hardware E2E**: Physical verification on ESP32-S3 + DAC hardware over local LAN (Final gate for Phase 3).

---

## 6. Offline Synchronization (`:data` + `:app`)

- `SyncedTrackRepository`: Stores synchronized tracks and manifest metadata in SQLite via Room (`CachedTrack`).
- `SyncWorker`: Executes background transfers using `WorkManager` with network constraint policies and automatic resumption.
- `Paging 3`: Powers virtualized scrolling in large synced libraries with minimal memory footprint.

---

## 7. Design System: Digital Ethereal

- **Color Palette**: Obsidian Base (`#090B11`), Smoked Glass (`0x1FFFFFFF`), Emotional Pink (`#FF6B9D`), Cyan Feedback (`#4DEEEA`).
- **Typography & Geometry**: Clean sans-serif hierarchy, 14–26dp corner radii, responsive layout adaptation.
- **Truthful Affordances**: Zero placebo controls; all UI elements directly reflect real player/system states.
