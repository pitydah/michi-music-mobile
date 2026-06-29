# Sync & Download Flow

## Arquitectura

```
┌─────────────────────────────────────────────────────┐
│                    SyncScreen                         │
│  (Compose UI)                                        │
│  - Discovery → Pairing → Connected                   │
│  - SyncProgress (Idle, Downloading, Complete, Error)  │
│  - Botón "Sincronizar biblioteca"                    │
└──────────────────────┬──────────────────────────────┘
                       │ collectAsStateWithLifecycle()
                       ▼
┌─────────────────────────────────────────────────────┐
│                 SyncViewModel                         │
│  - selectPeer() → Pairing → Connected                │
│  - syncLibrary() → WorkManager                       │
│  - schedulePeriodicSyncIfEnabled() → PeriodicWork    │
│  - Observa WorkInfo via getWorkInfoByIdFlow()        │
└──────────────────────┬──────────────────────────────┘
                       │ buildInputData()
                       ▼
┌─────────────────────────────────────────────────────┐
│                  SyncWorker                           │
│  (CoroutineWorker + Foreground notification)         │
│                                                      │
│  1. LinkClient.fetchSyncManifest(deviceId)           │
│  2. SyncedTrackRepository.saveLibrary()              │
│  3. SyncedTrackRepository.saveManifestPlaylists()    │
│  4. LinkTransferManager.downloadTracks()             │
│  5. TrackDao.markDownloadedWithPath()                │
└─────────────────────────────────────────────────────┘
```

## Flujo completo

### 1. Discovery
```
User abre SyncScreen
→ LaunchedEffect llama startDiscovery()
→ LinkDiscovery.start() inicia socket UDP multicast
→ Escucha en 224.0.0.167:53318
→ Descubre AnnounceMessage
→ Crea DiscoveredPeer
→ Lista servidores en UI
```

### 2. Pairing
```
User selecciona servidor
→ SyncViewModel.selectPeer()
→ LinkClient.getServerInfo()
→ Detecta PairingStrategy (PLAYER_PASSWORD | SERVER_CODE | LEGACY)
→ Si RECEIVER_BUTTON: error, no continuar

PLAYER_PASSWORD:
→ User ingresa username + password
→ LinkClient.pairStart()
→ LinkClient.pairConfirm(pairingId, username, password)
→ Guarda deviceToken en TokenStore

SERVER_CODE:
→ User ingresa código PIN
→ LinkClient.pairStart()
→ LinkClient.pairConfirm(pairingId, pin=code)
→ Guarda deviceToken + refreshToken en TokenStore
```

### 3. Sync (manifest)
```
User presiona "Sincronizar biblioteca"
→ SyncViewModel.syncLibrary()
→ SyncWorker.doWork()

1. LinkClient.fetchSyncManifest(deviceId)
   → GET /api/v1/sync/manifest?device_id=<id>
   → Recibe SyncManifestDto con tracks + playlists

2. SyncedTrackRepository.saveLibrary(tracks)
   → Upsert: preserva downloaded/filepath
   → Solo borra tracks no descargados que ya no están en manifest

3. SyncedTrackRepository.saveManifestPlaylists(playlists)
   → DeleteAll + InsertAll
```

### 4. Download
```
4. SyncWorker calcula items a descargar:
   → Manifest tracks que NO estén en downloadedIds

5. LinkTransferManager.downloadTracks(client, items)
   → Por cada item:
     a. Crea archivo en synced_music/{trackId}.{format}
     b. Si archivo existe y checksum matchea: skip
     c. LinkClient.streamTrack(trackId, outputStream)
        → GET /api/v1/stream/{trackId}
        → Escribe en FileOutputStream
     d. Verifica SHA-256 checksum si disponible
     e. Si checksum falla: borra archivo, marca error

6. TrackDao.markDownloadedWithPath(id, filepath)
   → Marca como descargado en DB
```

### 5. Progreso
```
SyncWorker.setProgress:
  - PROGRESS_TOTAL: total items
  - PROGRESS_CURRENT: items completados

SyncWorker.setForeground:
  - Notification con progreso
  - "Sincronizando X de Y"

SyncViewModel observa WorkInfo:
  - getWorkInfoByIdFlow(workRequest.id)
  - Actualiza SyncProgress en UI
  - Downloading(completed, total)
  - Complete(tracks, downloaded, errors)
  - Error(message)
```

## Manejo de errores

| Error | Comportamiento |
|-------|----------------|
| LinkException.Unauthorized (401) | `Result.failure()` - No retry, mostrar error |
| LinkException.Revoked (403) | `Result.failure()` - No retry, mostrar error |
| LinkException.NotImplemented (501) | `Result.failure()` - Feature no disponible |
| Network timeout | `Result.retry()` - Reintentar |
| Storage full | `Result.failure()` - IOException catch |
| Checksum mismatch | Marcar error en ese track, continuar con los demás |

## Estados de descarga

| Estado | Significado | UI |
|--------|-------------|-----|
| Idle | Sin sync | Botón "Sincronizar" |
| Downloading(0, 0) | Iniciando | Barra indeterminada |
| Downloading(c, t) | Descargando | Barra de progreso |
| Complete | Finalizado | Check + resumen |
| Error | Falló | Mensaje de error |

## Persistencia

### TokenStore
```
serverId
service
name
baseUrl
roles
features
authStrategy
tokenRefreshSupported
deviceToken
refreshToken
clientDeviceId
lastSeen
```

### Room (michi-sync.db)
```
cached_tracks: id, title, artist, album, format, filepath, downloaded, ...
cached_playlists: id, name, trackIds, trackCount
replaygain_cache: trackId, trackGain, albumGain
play_history: trackId, playedAt
play_counts: trackId, playCount, lastPlayed
saved_queue: trackIds, startIndex, positionMs, repeatMode, shuffleMode
```

### Downloads
```
Archivos en: context.filesDir/synced_music/{trackId}.{format}
Ejemplo: /data/data/org.michimusic.mobile/files/synced_music/abc123.mp3
```

## Seguridad

- Token en SharedPreferences (TODO: migrar a EncryptedSharedPreferences)
- Todas las llamadas HTTP autenticadas con `Authorization: Bearer <token>`
- Header `X-Michi-Device-Id` para validación de dispositivo
- Checksum SHA-256 para integridad de archivos descargados
- No guardar contraseña del servidor
- No loguear tokens
