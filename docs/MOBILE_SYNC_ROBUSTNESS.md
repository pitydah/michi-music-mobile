# Michi Mobile — Sync Robustness

## WorkManager

Sync se ejecuta via `SyncWorker` (CoroutineWorker).

### Ciclo de vida

```
1. SyncViewModel.syncLibrary()
2. Crea OneTimeWorkRequest
3. Enqueua en WorkManager
4. SyncWorker.doWork() en background thread
5. Reporta progreso via setProgress()
6. Muestra notificación foreground
7. SyncViewModel observa WorkInfo via getWorkInfoByIdFlow()
8. Actualiza UI con SyncProgress
```

### WakeLock

- Adquiere `PARTIAL_WAKE_LOCK` al iniciar
- Timeout base: 10 segundos (se renueva implícitamente con el progreso)
- Se libera en todas las salidas (success, failure, exception)

### Almacenamiento

- Verifica `StatFs.availableBytes` antes de descargar
- Mínimo requerido: 50MB libres
- Si no hay espacio suficiente, retorna `Result.failure()`

### Red

- Timeout de Ktor: default (sin timeout específico configurado)
- Error de red: IOException → `Result.retry()` por WorkManager
- Límite de reintentos: WorkManager retry policy default (exponential backoff)

### Checksum

- Cada track descargado verifica SHA-256 si el manifest incluye `checksum`
- Si el checksum no coincide: borra el archivo parcial y marca error
- El track se reintentará en la próxima sincronización

### Progreso

```
setProgress:
  - PROGRESS_TOTAL: número total de tracks a descargar
  - PROGRESS_CURRENT: tracks completados

setForeground:
  - Notificación con barra de progreso
  - Mensaje: "Sincronizando X de Y"
```

## Manejo de errores

| Condición | Comportamiento |
|-----------|---------------|
| 401 Unauthorized | `Result.failure()` — No reintentar |
| 403 Forbidden / Revoked | `Result.failure()` — No reintentar |
| 501 Not Implemented | `Result.failure()` — Feature no disponible |
| IOException (red) | `Result.retry()` — WorkManager reintenta |
| IOException (disco lleno) | `Result.failure()` — StorageError |
| Checksum mismatch | Error por track, resto continúa |
| LinkException genérico | `Result.failure()` — No reintentar |
| Exception genérico | `Result.retry()` — Reintentar |

## Persistencia offline

### Room

```
cached_tracks:
  - id (PK)
  - title, artist, album
  - filepath (ruta local si descargado)
  - downloaded (boolean)
  - format, bitrate, etc.
```

### Filesystem

```
context.filesDir/synced_music/
  {trackId}.{format}
```

### Upsert

`SyncedTrackRepository.saveLibrary()`:
- Preserva `downloaded = true` y `filepath` de tracks existentes
- Actualiza metadata (title, artist, etc.) de tracks existentes
- Inserta tracks nuevos
- Solo borra tracks que NO están descargados y ya no están en el manifest

## Estados de sync en UI

### Idle
```
Botón: "Sincronizar biblioteca"
```

### Downloading
```
Barra de progreso
Texto: "X / Y canciones"
```

### Complete
```
Check verde
Resumen: "Y canciones descargadas, X errores"
Botón: "Ver biblioteca"
```

### Error
```
Mensaje de error
Botón: "Reintentar"
```
