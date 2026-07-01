# Testing Checklist — Michi Music Mobile

## Build

- [ ] `./gradlew clean assembleNormalDebug` finishes successfully
- [ ] APK is generated at `app/build/outputs/apk/normal/debug/app-normal-debug.apk`

## Install

- [ ] APK installs on Android 12+ device
- [ ] App does not crash on launch
- [ ] Permission dialog appears (Android 13+: READ_MEDIA_AUDIO)

## Local Library

- [ ] Home screen shows loading state when scanning
- [ ] Home screen shows tracks list after scan
- [ ] Albums screen shows albums in CoverFlow
- [ ] Album tracks load on selection
- [ ] Search finds tracks by title, artist, album
- [ ] Empty state shown when no music found
- [ ] Permission denied shows informational message

## Playback

- [ ] "Reproducir todo" plays first track
- [ ] "Aleatorio" shuffles and plays
- [ ] MiniPlayer appears when a track is playing
- [ ] MiniPlayer play/pause works
- [ ] MiniPlayer skip next/previous works
- [ ] MiniPlayer tap navigates to NowPlaying
- [ ] NowPlaying shows track info
- [ ] NowPlaying play/pause works
- [ ] NowPlaying seek bar works
- [ ] NowPlaying source selector opens
- [ ] NowPlaying volume slider works

## CoverFlow

- [ ] Albums appear in carousel
- [ ] Swiping through albums updates selected album info
- [ ] Album artwork loads (or fallback color)
- [ ] Track list below album shows correct tracks
- [ ] Play from track row starts playback

## Sync (requires Michi Music KDE on local network)

- [ ] Sync discovery finds servers
- [ ] Peer list shows discovered servers
- [ ] Connection attempt starts
- [ ] Registration completes
- [ ] Sync button triggers WorkManager download
- [ ] Progress indicator shows during download
- [ ] Synced tracks appear in SyncedTracks screen
- [ ] Download fails gracefully if server unavailable

## Remote (requires Michi Music KDE)

- [ ] Remote screen shows "No hay conexión remota" when disconnected
- [ ] Configuring remote URL in Settings works
- [ ] Player status updates on poll
- [ ] Play/pause/next/prev buttons work
- [ ] Volume slider updates remote volume

## Stability

- [ ] App survives rotate
- [ ] Background playback continues
- [ ] Notification shows with controls
- [ ] App does not crash on permission deny
- [ ] "Sin reproducción" shown in MiniPlayer when idle
