# ME-CLOSURE MEGASPRINT — Ledger de ejecución

Baseline: `bcb537ed` · HEAD inicial: `5091e7a2` · Rama: `fix/me14-close-100`
Worktree único: `/home/cristian/michi-mobile-me14-close`
Workflow Gradle activo: `adb9c64d6a7af06bca344f1f47397957`

Objetivo: Metadata Editor terminado como producto funcional en MP3, FLAC y M4A/MP4
audio-only, en MediaStore propio, MediaStore de otra UID con consentimiento real y
SAF local; campos avanzados, multivalor representable, artwork add/replace/remove/resize,
lyrics add/modify/remove, seguridad durable, recuperación real, UI y Library.

## Estado de tareas

| # | Tarea | Estado | Commit | Test focal | Resultado | Siguiente acción |
|---|---|---|---|---|---|---|
| 0 | Recuperar estado y baseline | DONE | `5091e7a2` | `git status` | árbol limpio, sincronizado | — |
| 1 | Mapa pipeline MP3/FLAC/M4A | DONE | — | subagentes explore | mapas completos | — |
| 2 | Front A1: MP3 campos avanzados + n/total | DONE | `bbbbdfb9` | ID3 unit + E2E | MP3 avanzado, fecha/pares, evidencia Room | — |
| 3 | Front A2: multivalor MP3/FLAC extremo a extremo | DONE | `bbbbdfb9` | unit + E2E | MP3/FLAC multivalor; FLAC people evidence | M4A multivalor limitado a single |
| 4 | Front A3: FLAC integral + fixtures reales ffmpeg | DONE | `bbbbdfb9` | unit + E2E | FLAC real ffmpeg, frames de audio idénticos | — |
| 5 | Front B: M4A/MP4 campos, artwork, lyrics, inspector estructural | PARTIAL | `bbbbdfb9` | unit + E2E | M4A texto/pares/fecha; covr pendiente; inspector parcial | covr + inspector |
| 6 | Artwork add/replace/remove/resize (MP3/FLAC/M4A) | PARTIAL | `4c47dd9f` | unit + E2E | MP3/FLAC add/replace/remove; resize por replace; M4A covr pendiente | M4A covr |
| 7 | Lyrics add/modify/remove | PARTIAL | `bbbbdfb9` | unit + E2E | lyrics escalar canónico; add/remove por recurso pendiente | resource lyrics |
| 8 | Front C: durabilidad backup/stage (fsync+sha+dir) | DONE | pendiente commit | unit | `MetadataDurableFile` fsync+sha+dir; candidato fsync | commit |
| 9 | Front C: exclusión mutua restore/cleanup/reconciler | DONE | pendiente commit | unit | restore/discard bajo `MetadataSaveLocks`; resume ya bloqueado | commit |
| 10 | Front C: eliminar fallback `row.asMetadataPreimage()` | DONE | pendiente commit | unit | fallback live-row eliminado; preimagen durable obligatoria | commit |
| 11 | Front C: auditoría revisión Room/ABA/marker idempotente | TODO | — | unit | — | implementar |
| 12 | Front D: SAF multiformato (MP3 avanzado, FLAC, M4A, recursos, lyrics) | TODO | — | E2E DocumentsUI | — | implementar |
| 13 | Front D: fixture app UID independiente + consentimiento real | TODO | — | E2E | — | implementar |
| 14 | UI Workbench: todas las capacidades, dirty/undo/redo/revert/review | TODO | — | Compose tests | — | implementar |
| 15 | Library: consumidores por identidad + revisión | TODO | — | unit/compose | — | implementar |
| 16 | Recuperación real: process death + reboot + checkpoints | TODO | — | instrumented | — | implementar |
| 17 | KILLCRITIC review independiente | TODO | — | revisión | — | ejecutar |
| 18 | Matrices me14-evidence actualizadas | TODO | — | — | — | actualizar |
| 19 | Suites completas, lint, variantes, migraciones | TODO | — | gradle | — | ejecutar |
| 20 | Commit, push, CI al SHA final | TODO | — | CI | — | ejecutar |

## Hallazgos técnicos registrados

- Rewriters reales en `core/src/main/java/org/michimusic/core/metadata/`:
  `MetadataId3StageRewriter.kt`, `MetadataFlacStageRewriter.kt`,
  `MetadataMp4StageRewriter.kt`, `MetadataMp4TailStageRewriter.kt`,
  `MetadataPrivateBundleStage.kt`, `MetadataSelectiveAssetStage.kt`.
- `MetadataM4aPrivateStage.kt` está en `data/`.
- Perfiles certificados actuales: MP3 4 campos, FLAC solo TITLE, M4A solo TITLE,
  SAF MP3 solo TITLE.
- `MetadataCapabilityRegistry.flacStageFields` ya declara ~30 campos FLAC.
- `MetadataSaveLocks`: `Mutex` único de proceso, `release()` no-op.
- `MetadataTrackDaoCasPort`: fallback `row.asMetadataPreimage()` cuando no hay store.
- Puertos selectivos Genre/People/Combined escriben Room con evidencia estructurada
  propia, sin preimagen durable.

## Bloqueos

- Ninguno todavía. Hardware físico Xiaomi no disponible (no bloquea trabajo
  implementable/emulador).

## Progreso real 2026-11-25

- `5091e7a2`: SAF local con grant persistente real para MP3 TITLE; escritura,
  postcheck, Room, reapertura, restauración exacta y journal limpio.
- `bbbbdfb9`: MP3 texto avanzado, fecha, pares pista/disco, créditos y multivalor
  representable; FLAC integral con fixture real FFmpeg y frames de audio idénticos;
  M4A audio-only texto/pares; SAF local con texto avanzado; evidencia Room exacta.
- `4c47dd9f`: artwork MP3/FLAC añadir, reemplazar y quitar; resize por reemplazo;
  E2E reales con audio preservado, publicación Room y journal limpio.
- Seguridad (pendiente de commit): copia de respaldo durable con fsync/tamaño/SHA y
  directorio, candidato forzado antes de STAGE_READY, CAS sin fallback a la fila
  viva (preimagen durable obligatoria), restauración y descarte bajo el mismo
  `MetadataSaveLocks`.
- Verde: suites completas `:core` y `:data`; E2E MP3/FLAC/SAF/M4A citados arriba.
- Pendiente: artwork `covr` M4A, perfiles SAF FLAC/M4A, lyrics por recurso,
  consumidores Library/UI restantes, muerte real de proceso/reboot, revisión
  independiente, matrices A01-A30, lint, variantes y CI final.
