# STATUS REPORT — Michi Music Mobile

| Campo | Valor |
|-------|-------|
| Commit | `a5d0080` |
| Variante instalada | `normalDebug` |
| APK | `app/build/outputs/apk/normal/debug/app-normal-debug.apk` |
| Fecha build | 2026-07-03 |

## Logcat al abrir (sin tocar nada)

```
No MichiAudio logs esperados.
No ExoPlayerImpl Init esperado.
No UnsupportedOperationException onPlaybackResumption esperado.
```

## Logcat al tocar reproducir

```
MichiAudio: playQueue requested (o deferred)
MediaController ready
ExoPlayerImpl Init
```

## Comportamiento

| Condición | Esperado | Real |
|-----------|----------|------|
| ExoPlayer arranca al abrir | NO | |
| onPlaybackResumption warning | NO (emptyList en lugar de failedFuture) | |
| MiniPlayer conecta Media3 al arrancar | NO (recibe state como prop) | |
| pendingQueue ejecuta al estar listo | SÍ | |
