# Architecture — Michi Music Mobile

## 1. Role in the Michi Ecosystem

Michi Music Mobile is the **portable node** of the [Michi Music Ecosystem](https://github.com/pitydah).

Its core responsibilities are:
- **PLAY**: Bit-accurate, high-fidelity local audio playback via AndroidX Media3 (ExoPlayer + PCM ReplayGain + Audio Effects HAL + USB DAC).
- **ACCESS**: Continuous remote access and streaming from Michi Micro Server / Big Server.
- **CONTROL**: Remote playback and queue control of Desktop Player, Servers, and physical Michi Stream receivers.
- **CONTINUE**: Seamless handoff of logical playback sessions between devices.
- **CARRY**: Resilient offline library storage and synchronization via Michi Sync.

---

## 2. Module Architecture

```
┌───────────────────────────────────────────────────────────┐
│                           :app                            │
│  Navigation (MichiNavHost) · DI (Koin) · ViewModels       │
│  Screens: Home · Albums (CoverFlow) · Search · NowPlaying │
│  Settings · Remote · Sync · SyncedTracks · Diagnostics    │
│  Background: SyncWorker (WorkManager)                     │
├───────────────────────────────────────────────────────────┤
│                          :player                          │
│  MichiPlaybackService (MediaLibraryService)               │
│  ExoPlayer + ReplayGainAudioProcessor (PCM ±dB)           │
│  MichiAudioEffects (Equalizer, BassBoost, Virtualizer)    │
│  UsbDacManager (USB Device / Headset Routing)             │
│  AudioController & PlaybackStateStore                     │
├─────────────────────────────┬─────────────────────────────┤
│            :data            │     :michi-link-client      │
│  MediaQueryDispatcher       │  LinkClient (OkHttp REST)   │
│  Room (MichiDatabase)       │  EventClient (SSE Events)   │
│  LocalMediaRepository       │  LinkDiscovery (UDP 21120)  │
│  SyncedTrackRepository      │  TokenStore (AES Encrypted) │
├─────────────────────────────┴─────────────────────────────┤
│                           :core                           │
│  Canonical Domain Models: Track, Album, Artist, Playlist   │
│  DiscoveredPeer, SyncConnectionState, Enums               │
└───────────────────────────────────────────────────────────┘
```

---

## 3. Playback Architecture (`:player`)

### Service & Session Management
- `MichiPlaybackService` extends `MediaLibraryService` and creates a single instance of `ExoPlayer`.
- `MichiMediaLibrarySessionCallback` handles `onConnect`, `onCustomCommand`, and browsable media item trees for Android Auto and system integrations.

### Audio Pipeline & HAL
1. **Audio Attributes**: `AudioAttributes.DEFAULT` with automatic audio focus handling.
2. **ReplayGain**: PCM-level volume normalization via `ReplayGainAudioProcessor`, supporting negative attenuation and positive pre-amp gain without clipping.
3. **Hardware Effects**: `MichiAudioEffects` manages Android HAL `Equalizer`, `BassBoost`, and `Virtualizer` attached to the active `audioSessionId`.
4. **USB DAC Direct Output**: `UsbDacManager` monitors `AudioDeviceCallback` and routes playback through `AudioDeviceInfo.TYPE_USB_DEVICE` and `TYPE_USB_HEADSET`.

---

## 4. Michi Link Client (`:michi-link-client`)

Michi Link is the official interoperability contract across all ecosystem components.

### 1. Discovery
`LinkDiscovery` sends UDP broadcast queries (`MICHI_DISCOVER`) on port `21120` to discover active Michi nodes (Player, Micro Server, Stream).

### 2. Pairing & Identity
- `/api/v1/pair/start`: Requests PIN or QR pairing with client identification.
- `/api/v1/pair/confirm`: Completes handshake and exchanges authentication token.
- `TokenStore`: Persists session tokens using AES-256 GCM in `EncryptedSharedPreferences`.

### 3. Playback & Remote Control
`LinkClient` provides standard REST endpoints (`/api/v1/playback/state`, `/api/v1/playback/command`, `/api/v1/queue`, `/api/v1/stream/{id}`).

### 4. Real-time Events
`EventClient` connects to `/api/v1/events` (Server-Sent Events) with exponential backoff reconnection to maintain synchronized playback state.

---

## 5. Offline Synchronization (`:data` + `:app`)

- `SyncedTrackRepository`: Stores synchronized tracks in SQLite via Room (`CachedTrack`).
- `SyncWorker`: Executes background transfers using `WorkManager` with network constraint policies and automatic resumption.
- `Paging 3`: Powers virtualized scrolling in large synced libraries with minimal memory footprint.

---

## 6. Design System: Digital Ethereal

- **Color Palette**: Obsidian Base (`#090B11`), Smoked Glass (`0x1FFFFFFF`), Emotional Pink (`#FF6B9D`), Cyan Feedback (`#4DEEEA`).
- **Typography & Geometry**: Clean sans-serif hierarchy, 14–26dp corner radii, responsive layout adaptation.
- **Truthful Affordances**: Zero placebo controls; all UI elements directly reflect real player/system states.
