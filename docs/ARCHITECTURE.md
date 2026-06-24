# Architecture — Michi Music Mobile

## Design Philosophy

Michi Music Mobile is not a clone of any existing player. Its identity comes from:
- **CoverFlow** carousel (matching KDE `coverflow.py`)
- **Glassmorphism** dark theme (`#090B11`, 14dp radius, accent pink)
- **Michi Sync** as an overlay layer on top of local playback
- **Local-first**: MediaStore is primary, sync is additive

The architecture below synthesizes **the best patterns** from 6 reference Android music player projects:

| Project | Pattern to adopt |
|---------|-----------------|
| **Auxio** | ReplayGain via `AudioProcessor` (PCM-level, handles +dB) |
| **BoomingMusic** | `MediaLibraryService`, `MediaQueryDispatcher`, `AdvancedForwardingPlayer`, Coil custom fetchers |
| **Metro** | CardBlur glassmorphism, theme system |
| **Vinyl** | Android Auto browsable tree, `DynamicsProcessing` ReplayGain fallback |
| **Vanilla** | Simplicity: `SongTimeline` as `ArrayList<Song>`, state bitfield |
| **Media3** | Canonical `MediaLibrarySession.Callback`, `onPlaybackResumption`, DataStore state |

---

## Module Architecture

```
┌───────────────────────────────────────────────────────────┐
│                        :app                               │
│  Navigation (Compose)  ·  DI (Koin)  ·  ViewModels        │
│  Screens: Albums  ·  Playlist  ·  Home  ·  NowPlaying     │
├───────────────────────────────────────────────────────────┤
│                       :player                              │
│  MediaLibraryService  ·  ReplayGainAudioProcessor          │
│  MediaLibrarySession.Callback  ·  LibraryProvider          │
│  ExoPlayer + AdvancedForwardingPlayer                      │
├──────────────────────┬────────────────────────────────────┤
│      :data           │          :sync-client               │
│  MediaQueryDispatcher│  MichiSyncApiClient (Ktor)          │
│  Room (metadata)     │  DiscoveryClient (UDP)              │
│  LocalMediaRepository│  TransferManager                    │
├───────────┬──────────┴────────────────┬───────────────────┤
│   :core   │        :remote            │                    │
│  Models   │  RemoteApiClient (KDE)    │                    │
│  DTOs     │  HTTP API endpoints       │                    │
└───────────┴───────────────────────────┴────────────────────┘
```

---

## Playback Architecture (from Media3 + BoomingMusic)

### Service

Replace `MichiPlaybackService extends Service` with a `MediaLibraryService`:

```kotlin
// :player/src/.../MichiPlaybackService.kt
class MichiPlaybackService : MediaLibraryService() {

  private lateinit var mediaLibrarySession: MediaLibrarySession

  override fun onCreate() {
    super.onCreate()
    initializeSessionAndPlayer()
    setListener(MediaSessionServiceListener())
  }

  override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
    return mediaLibrarySession
  }

  private fun initializeSessionAndPlayer() {
    val player = ExoPlayer.Builder(this)
      .setAudioAttributes(AudioAttributes.DEFAULT, handleAudioFocus = true)
      .build()

    // Attach ReplayGain audio processor
    player.addAudioProcessor(replayGainAudioProcessor)

    mediaLibrarySession = MediaLibrarySession.Builder(
      this, player, MichiMediaLibrarySessionCallback(this)
    ).build()
  }
}
```

### Session Callback (from Media3 demos)

```kotlin
class MichiMediaLibrarySessionCallback(
  private val service: MichiPlaybackService
) : MediaLibrarySession.Callback {

  override fun onConnect(session, controller): ConnectionResult {
    return if (controller.isTrusted)
      ConnectionResult.accept(DEFAULT_SESSION_AND_LIBRARY_COMMANDS, DEFAULT_PLAYER_COMMANDS)
    else
      ConnectionResult.accept(SessionCommands.EMPTY, restrictedAccessPlayerCommands)
  }

  override fun onGetLibraryRoot(session, browser, params): ListenableFuture<LibraryResult<MediaItem>> {
    return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
  }

  override fun onGetChildren(session, browser, parentId, page, pageSize, params)
    : ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    return Futures.immediateFuture(LibraryResult.ofItemList(
      libraryProvider.getChildren(service, parentId), params
    ))
  }

  // Resolve media items for playback (expand album -> songs, etc.)
  override fun onAddMediaItems(session, controller, mediaItems)
    : ListenableFuture<List<MediaItem>> {
    return Futures.immediateFuture(libraryProvider.resolveForPlayback(mediaItems))
  }

  // State persistence for process restart
  override fun onPlaybackResumption(session, controller, isForPlayback)
    : ListenableFuture<MediaItemsWithStartPosition> {
    return CoroutineScope(Dispatchers.Unconfined).future {
      service.retrieveLastStoredMediaItem()?.let { restoreFromPreferences(it) }
      throw IllegalStateException("no previous session")
    }
  }
}
```

### Queue Management (from Media3 canonical)

Use Media3's built-in `Timeline` — no custom `RawQueue`. This gives us:
- `setMediaItems(items, startIndex, startPositionMs)` — replace queue
- `addMediaItem(index, item)` — insert at position
- `moveMediaItem(from, to)` — reorder
- `removeMediaItem(index)` — delete
- `shuffleModeEnabled = true` — built-in shuffle
- `repeatMode` — NONE/ALL/ONE
- `currentMediaItemIndex` — position tracking

### Playback State (from Auxio's listener pattern + Vanilla's simplicity)

Expose state via `StateFlow` from a `PlaybackStateManager` interface:

```kotlin
interface PlaybackStateManager {
  val isPlaying: StateFlow<Boolean>
  val currentSong: StateFlow<Song?>
  val queue: StateFlow<List<Song>>
  val currentIndex: StateFlow<Int>
  val repeatMode: StateFlow<RepeatMode>
  val isShuffled: StateFlow<Boolean>
  val progress: StateFlow<Long>
}
```

Implementation listens to `Player.Listener` events from the ExoPlayer and updates flows. ViewModels collect these flows.

---

## ReplayGain (from Auxio + Vinyl)

### Strategy: AudioProcessor (from Auxio)

```kotlin
class ReplayGainAudioProcessor(
  private val playbackState: PlaybackStateManager,
  private val preferences: ReplayGainPreferences,
) : BaseAudioProcessor() {

  private var volume = 1f

  private fun applyReplayGain(song: Song?) {
    if (song == null) { volume = 1f; return }
    val gain = song.replayGainAdjustment  // RGData(track, album)
    val preAmp = preferences.preAmp

    val resolved = when (preferences.mode) {
      OFF -> null
      TRACK -> gain.track ?: gain.album
      ALBUM -> gain.album ?: gain.track
      DYNAMIC -> gain.album?.takeIf { /* playing from album */ } ?: gain.track
    }

    val amplified = if (resolved != null) resolved + preAmp.with else preAmp.without
    volume = 10f.pow(amplified / 20f)
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (volume == 1f) { /* passthrough */; return }
    for (i in pos until limit step 2) {
      var sample = inputBuffer.getLeShort(i)
      sample = (sample * volume).toInt()
        .coerceAtLeast(Short.MIN_VALUE.toInt())
        .coerceAtMost(Short.MAX_VALUE.toInt())
        .toShort()
      buffer.putLeShort(sample)
    }
  }
}
```

### Mode Logic (from Vinyl)
- **TRACK**: Prefer track gain, fallback album
- **ALBUM**: Prefer album gain, fallback track
- **DYNAMIC**: Album gain when in album context, track otherwise
- Pre-amp configurable per-mode (with/without RG tags)

### RG Tag Extraction
- Read via `jaudiotagger` on initial MediaStore sync (like Vinyl)
- Store in Room `song` table as `replayGainTrack REAL, replayGainAlbum REAL`
- On Android P+: optionally use `DynamicsProcessing.setInputGainAllChannelsTo()` (Vinyl pattern) as an alternative, falling back to AudioProcessor

---

## MediaStore Reading (from BoomingMusic)

### MediaQueryDispatcher (builder pattern)

```kotlin
// :data/src/.../MediaQueryDispatcher.kt
class MediaQueryDispatcher(private val uri: Uri = getAudioContentUri()) {

  private var projection: Array<String>? = null
  private var selection: String? = null
  private var selectionArgs: Array<String>? = null
  private var sortOrder: String? = null

  fun withColumns(vararg cols: String) = apply { projection = arrayOf(*cols) }
  fun setSelection(sel: String) = apply { selection = sel }
  fun addSelection(sel: String, mode: String = "AND") = apply {
    selection = if (selection.isNullOrEmpty()) sel else "$selection $mode $sel"
  }
  fun addArguments(vararg args: String) = apply { selectionArgs = selectionArgs.orEmpty() + args }
  fun setSortOrder(order: String) = apply { sortOrder = order }

  fun dispatch(): Cursor? {
    return try {
      get<ContentResolver>().query(uri, projection, selection, selectionArgs, sortOrder)
    } catch (e: Exception) { null }
  }
}
```

### Repository pattern (from BoomingMusic)

```kotlin
// :data/src/.../SongRepository.kt
class RealSongRepository(private val contentResolver: ContentResolver) : SongRepository {
  override suspend fun allSongs(): List<Song> = withContext(Dispatchers.IO) {
    MediaQueryDispatcher()
      .withColumns(*BASE_PROJECTION)
      .setSelection("is_music=1 AND title != ''")
      .addSelection("${Media.DURATION} >= ?")
      .addArguments(minDurationMs.toString())
      .setSortOrder(PreferenceUtil.songSortOrder)
      .dispatch()
      .use { cursor -> cursor.map { it.toSong() } }
  }
}
```

---

## Android Auto (from Vinyl + Media3)

Use `MediaLibrarySession.Callback` for library browsing (no separate service). The tree structure follows Vinyl's pattern:

```
ROOT
  ├── Albums (browsable)
  │   ├── Album 1 -> songs
  │   └── Album N -> songs
  ├── Artists (browsable)
  │   └── Artist 1 -> albums -> songs
  ├── Playlists (browsable)
  │   ├── [smart] Last Added
  │   ├── [smart] Top Tracks
  │   └── [static] Playlist name -> songs
  └── Songs (playable: shuffle all)
```

The `LibraryProvider` (from BoomingMusic) generates `MediaItem` trees from the repository:

```kotlin
class LibraryProvider(private val repository: Repository) {
  suspend fun getChildren(parentId: String): List<MediaItem> = when (parentId) {
    ROOT -> listOf(albumsItem, artistsItem, playlistsItem, songsItem)
    ALBUMS -> repository.allAlbums().map { it.toBrowsableMediaItem() }
    ALBUM_ITEM -> repository.albumSongs(extractId(parentId)).map { it.toPlayableMediaItem() }
    // ...etc
  }
}
```

---

## DI: Koin (from BoomingMusic)

```kotlin
// :app/src/.../AppModule.kt
val appModules = listOf(
  mainModule,      // ContentResolver, SharedPreferences
  roomModule,      // Database, DAOs
  dataModule,      // Repositories, MediaQueryDispatcher
  playerModule,    // PlayerController, AudioProcessors
  viewModule,      // ViewModels
  syncModule,      // Sync clients
)
```

---

## Room Database (from BoomingMusic)

Track metadata lives in **MediaStore**. Room is for app-specific data:

| Entity | Purpose |
|--------|---------|
| `PlaylistEntity` | User created playlists |
| `SongEntity` | Playlist-song junction |
| `HistoryEntity` | Recently played |
| `PlayCountEntity` | Play counts / timestamps |
| `QueueEntity` | Saved queue state |
| `SettingsEntity` | Preferences |
| `ReplayGainEntity` | Cached RG values per song ID |

---

## Glassmorphism UI (from Metro's CardBlur)

Based on Metro's `CardBlurFragment` + `BlurTransformation`:

```
NowPlayingScreen layout:
┌──────────────────────────┐
│  [blurred album art bg]  │  ← RenderScript blur on album art
│                          │
│   ┌──────────────────┐   │
│   │  Album art card  │   │  ← GlassCard (elevated, rounded 14dp)
│   │  (center crop)   │   │
│   └──────────────────┘   │
│                          │
│   Title                  │  ← Color.White
│   Artist                 │  ← Color.White(0.7f)
│                          │
│   ──●───────────────     │  ← SeekBar (accent pink)
│   1:23       3:45        │
│                          │
│   ◄◄  ▶║►  ►►          │  ← Controls (white)
│                          │
│   [shuffle] [repeat]     │  ← Toggle buttons
└──────────────────────────┘
```

The blur is applied via Coil/Glide `BlurTransformation` using `RenderScript` (API 17+) with a fallback to software `StackBlur`. The card is a `Surface` with `Modifier.shadow(elevation=8.dp)` on top of the blurred image.

---

## Media3 Features (adopted from canonical samples)

| Feature | Implementation |
|---------|---------------|
| **Foreground service** | Handled by `MediaSessionService` default lifecycle |
| **Notification** | `DefaultMediaNotificationProvider` with custom `getMediaButtons()` |
| **Audio focus** | `ExoPlayer.Builder(this).setAudioAttributes(..., handleAudioFocus=true)` |
| **Becoming noisy** | Handled automatically by `MediaSessionService` |
| **Gapless playback** | Built-in ExoPlayer (no custom logic needed) |
| **Playback resumption** | `onPlaybackResumption()` + DataStore/Protobuf |
| **Shuffle** | `Player.shuffleModeEnabled` (built-in) |
| **Repeat** | `Player.repeatMode` (NONE/ALL/ONE) |
| **State persistence** | DataStore for queue + position (protobuf via protobuf-java) |

---

## State Persistence (from Media3 canonical)

Use `DataStore<Preferences>` with protobuf:

```kotlin
// Store on media item transition
private fun storeCurrentMediaItem() {
  val mediaId = player.currentMediaItem?.mediaId ?: return
  val positionMs = player.currentPosition
  CoroutineScope(Dispatchers.IO).launch {
    preferenceDataStore.updateData { prefs ->
      prefs.toBuilder()
        .setMediaId(mediaId)
        .setPositionMs(positionMs)
        .build()
    }
  }
}

// Restore on process restart
override fun onPlaybackResumption(...): ListenableFuture<MediaItemsWithStartPosition> {
  return future {
    val saved = preferenceDataStore.data.first()
    maybeExpandSingleItemToPlaylist(saved.mediaId, 0, saved.positionMs) ?:
      throw IllegalStateException("no previous session")
  }
}
```

---

## Dev/Prod Flavors (from BoomingMusic)

```kotlin
flavorDimensions += "build"
productFlavors {
  create("normal") { dimension = "build" }          // Full: network, lyrics, scrobbling
  create("fdroid") { dimension = "build" }          // F-Droid: no network services
  create("playstore") { dimension = "build" }       // Play Store: no built-in updater
}
```

---

## Reference Project Locations

Cloned repos for source-code reference (GPL-3.0 compatible):

| Project | Path |
|---------|------|
| Auxio | `/tmp/opencode/referencias/Auxio/` |
| BoomingMusic | `/tmp/opencode/referencias/BoomingMusic/` |
| Metro | `/tmp/opencode/referencias/Metro/` |
| Vinyl | `/tmp/opencode/referencias/Vinyl/` |
| Vanilla | `/tmp/opencode/referencias/Vanilla/` |
| Media3 | `/tmp/opencode/referencias/Media3/` |

### Key Files by Pattern

| Pattern | Reference | File |
|---------|-----------|------|
| ReplayGain AudioProcessor | Auxio | `.../playback/replaygain/ReplayGainAudioProcessor.kt:47-231` |
| ReplayGain mode logic | Vinyl | `.../service/MultiPlayer.java:321-384` |
| MediaLibraryService | Media3 | `demos/session_service/.../DemoPlaybackService.kt:54-285` |
| MediaLibrarySession.Callback | Media3 | `demos/session_service/.../DemoMediaLibrarySessionCallback.kt:43-259` |
| MediaQueryDispatcher | BoomingMusic | `.../data/local/MediaQueryDispatcher.kt:32-82` |
| AdvancedForwardingPlayer | BoomingMusic | `.../playback/AdvancedForwardingPlayer.kt:12-312` |
| LibraryProvider | BoomingMusic | `.../playback/library/LibraryProvider.kt:24-461` |
| Android Auto tree | Vinyl | `.../service/BrowsableMusicProvider.java:42-417` |
| Glassmorphism/Blur | Metro | `.../fragments/player/cardblur/CardBlurFragment.kt:44-185` |
| BlurTransformation | Metro | `.../glide/BlurTransformation.kt:18-132` |
| State machine | Auxio | `.../playback/state/PlaybackStateManager.kt:47-811` |
| PlaybackCommand factory | Auxio | `.../playback/state/PlaybackCommand.kt:38-196` |
| Queue Simple (for ref) | Vanilla | `.../vanilla/SongTimeline.java` (plain ArrayList) |

---

## Related Documentation

- [KDE_INTEGRATION.md](KDE_INTEGRATION.md) — Integration with KDE server
- [MICHI_SYNC_PROTOCOL.md](MICHI_SYNC_PROTOCOL.md) — Wire protocol
- [THIRD_PARTY.md](THIRD_PARTY.md) — Licenses and attribution
