# AGENTS.md — Michi Music Mobile

## Project Identity

Michi Music Mobile — Android companion app for [Michi Music Player](https://github.com/pitydah/michi-music-player) (Linux/KDE).

| Field | Value |
|-------|-------|
| License | GPL-3.0 |
| Repository | https://github.com/pitydah/michi-music-mobile |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Min SDK | 31 (Android 12) |
| Target SDK | 36 |
| Audio | AndroidX Media3 / ExoPlayer |
| DI | Koin |
| Sync | Ktor HTTP + UDP Multicast |
| Cache | Room (SQLite) |

## Setup (aarch64 / ARM64 Linux)

This project builds on aarch64 Linux using QEMU binfmt for x86_64 toolchain emulation:

```bash
# Required: x86_64 libraries for AAPT2 emulation
sudo apt-get install qemu-user
sudo mkdir -p /lib64
sudo ln -sf /path/to/amd64/libc6/usr/lib64/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2
sudo ln -sf /path/to/amd64/libc6/usr/lib/x86_64-linux-gnu /lib/x86_64-linux-gnu
```

On x86_64 Linux, no special setup is needed.

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

## Modules

| Module | Responsibility |
|--------|---------------|
| `:app` | UI, navigation, DI wiring |
| `:core` | Shared models (Track, Album, Sync DTOs) |
| `:data` | Room database, MediaStore reader, repositories |
| `:player` | Media3 ExoPlayer service, playback state |
| `:sync-client` | Ktor HTTP client, UDP discovery, transfer manager |
| `:remote` | Remote control client for KDE |

## Integration with Michi Music Player (KDE)

**CRITICAL RULE**: Before implementing or modifying any feature that touches sync, protocol, models, or remote control, ALWAYS consult the KDE repository first:

- **KDE repo**: `/home/raspi/proyectos/michi-music-player/`
- **Sync server**: `/home/raspi/proyectos/michi-music-player/sync/`
- **Protocol models**: `/home/raspi/proyectos/michi-music-player/sync/sync_protocol.py`
- **Discovery**: `/home/raspi/proyectos/michi-music-player/sync/sync_discovery.py`
- **HTTP server**: `/home/raspi/proyectos/michi-music-player/sync/sync_server.py`
- **AGENTS.md**: `/home/raspi/proyectos/michi-music-player/AGENTS.md` (architecture, patterns, conventions, codebase map)

This ensures:
- Models (field names, types, defaults) match exactly between Python and Kotlin
- Protocol behavior (headers, status codes, payload shapes) is coherent
- No drift between desktop and mobile implementations

The Python codebase IS the source of truth for protocol definitions. The Kotlin models must mirror them precisely.

## CoverFlow Design (KDE Alignment)

The CoverFlow carousel replicates `michi-music-player/library/coverflow.py` visual behavior:

| KDE constant | Android equivalent | Value |
|---|---|---|
| `max_rot = 60.0` | `MichiCoverTransformer.MAX_ROTATION` | 60 degrees |
| `center_scale = 1.05` | `CENTER_SCALE` | 1.05 |
| `far_scale = 0.52` | `MIN_SCALE` | 0.45 |
| `scale decay = 0.22` | `SCALE_DECAY` | 0.22 per unit |
| corner radius = 14px | `RoundedCornerShape(14.dp)` | 14dp |
| background `#090B11` | `SurfaceDark` | `Color(0xFF090B11)` |
| accent glow `rgba(110,90,200,14)` | `GlowPink` (pink) | `Color(0x33FF6B9D)` |

**Layout**: AlbumsScreen uses `MichiCoverFlowHost` (DiscreteScrollView via AndroidView) + GlassCard track list. PlaylistScreen uses LazyColumn with active-track highlight.

**Key files**:
- `library/coverflow/` — non-Compose View-layer items (ViewHolder, Adapter, Transformer)
- `ui/coverflow/MichiCoverFlowHost.kt` — Compose bridge wrapping DiscreteScrollView
- `ui/components/` — GlassCard, GlowPlayButton, TrackRow, MiniPlayer
- `ui/screens/AlbumsScreen.kt` — replaces old LibraryScreen
- `ui/screens/PlaylistScreen.kt` — accessible via route "playlist"

Sources: `/home/raspi/proyectos/michi-music-player/library/coverflow.py` (layout engine + paint effects)
Sources: `/home/raspi/proyectos/michi-music-player/ui/services/` (album metadata)

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full details.
