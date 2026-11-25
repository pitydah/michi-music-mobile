# ME-CLOSURE MEGASPRINT — Ledger de ejecución

Baseline: `bcb537ed` · HEAD inicial: `5091e7a2` · Rama: `fix/me14-close-100`
Worktree único: `/home/cristian/michi-mobile-me14-close`
Workflow Gradle activo: `b6f13958f0bbefc34ca823494f132d31`

Objetivo: Metadata Editor terminado como producto funcional en MP3, FLAC y M4A/MP4
audio-only, en MediaStore propio, MediaStore de otra UID con consentimiento real y
SAF local; campos avanzados, multivalor representable, artwork add/replace/remove/resize,
lyrics add/modify/remove, seguridad durable, recuperación real, UI y Library.

## Estado de tareas

| # | Tarea | Estado | Commit | Test focal | Resultado | Siguiente acción |
|---|---|---|---|---|---|---|
| 0 | Recuperar estado y baseline | DONE | `5091e7a2` | `git status` | árbol limpio, sincronizado | — |
| 1 | Mapa pipeline MP3/FLAC/M4A | RUNNING | — | subagentes explore | pendiente | recoger mapas |
| 2 | Front A1: MP3 campos avanzados + n/total | TODO | — | ID3 unit + E2E | — | implementar |
| 3 | Front A2: multivalor MP3/FLAC extremo a extremo | TODO | — | unit + E2E | — | implementar |
| 4 | Front A3: FLAC integral + fixtures reales ffmpeg | TODO | — | unit + E2E | — | implementar |
| 5 | Front B: M4A/MP4 campos, artwork, lyrics, inspector estructural | TODO | — | unit + E2E | — | implementar |
| 6 | Artwork add/replace/remove/resize (MP3/FLAC/M4A) | TODO | — | unit + E2E | — | implementar |
| 7 | Lyrics add/modify/remove | TODO | — | unit + E2E | — | implementar |
| 8 | Front C: durabilidad backup/stage (fsync+sha+dir) | TODO | — | unit | — | implementar |
| 9 | Front C: exclusión mutua restore/cleanup/reconciler | TODO | — | unit | — | implementar |
| 10 | Front C: eliminar fallback `row.asMetadataPreimage()` | TODO | — | unit | — | implementar |
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
