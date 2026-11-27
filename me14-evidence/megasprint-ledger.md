# ME-CLOSURE MEGASPRINT — Ledger de ejecución

Baseline: `bcb537ed` · HEAD inicial: `5091e7a2` · Rama: `fix/me14-close-100`
Worktree único: `/home/cristian/michi-mobile-me14-close`
Workflow Gradle activo: `adb9c64d6a7af06bca344f1f47397957`

Objetivo: Metadata Editor terminado como producto funcional en MP3, FLAC y M4A/MP4
audio-only, en MediaStore propio, MediaStore de otra UID con consentimiento real y
SAF local; campos avanzados, multivalor representable, artwork add/replace/remove/resize,
lyrics add/modify/remove, seguridad durable, recuperación real, UI y Library.

## Estado autoritativo — cierre ME-OMEGA y estabilización MF01–MF07

Fecha: 2026-11-26. Rama: `fix/me14-close-100`. HEAD de código: `4c3b204c`; HEAD git de este documento: `926b9563`. Base auditada: `a7059f79`. `origin/master`: `b0104b93`.

Estado: **MF01–MF05 integrados y revisados; MF06 actualizado; MF07 STATIC/GRADLE_GO; KILLCRITIC combinada REVIEW_GO en `926b9563`; CI final y merge a master pendientes.** No se declara el cierre final hasta que el CI corra sobre el SHA exacto integrado y el merge esté verificado en `origin/master`.

| Frente | Estado | Commit(s) | Evidencia | Límite conocido |
|---|---|---|---|---|
| MF01 — muestras MP4 reales | DONE / REVIEW_GO | `209be802` | Hash real de bytes de muestra en orden de decodificación; 19 tests inspector + 5 stage; E2E M4A y M4A carátulas/letras verdes; ffprobe coincide con 17 muestras/4.612 bytes | Trabajo agregado de hashing no acotado por tamaño (muestras solapadas); sin tope de hijos de `stsd`; stage sin callback de cancelación; faltan algunos tests negativos de equivalencia |
| MF02 — géneros multivalor | DONE / REVIEW_GO | `e500ee54`, `0b6385b6` | Lista ordenada desde evidencia estructurada; escalar primario del escritor activo + lista NUL; Room real close/reopen; 855 tests data, 2.457 app; revisión independiente GO | Reemplazo del mismo tamaño que preserva timestamp/duración no es distinguible sin hashing; marcadores `Other`/`Unknown` conservadores; SAF `attachDurableGenres` sin comprobación de modified-time |
| MF03 — identidad del overlay | DONE / REVIEW_GO | `e500ee54` + C `2058a535` | `MetadataAppliedTransaction` exacto (source/track/URI/revisión/candidate bytes+SHA/appliedAt); rechazo de marcador ausente/ajeno/obsoleto; fences de tamaño/duración/modified; sin escaneo/escritura global | Limitaciones de evidencia de proveedor y SAF descritas arriba |
| MF04 — perfiles M4A versionados | DONE / REVIEW_GO | `8182c6f8` | Perfil activo `...TEXT_RESOURCES_V2`; históricos TITLE_V1/TEXT_V1 retenidos; store v4 `profileId`/`evidenceId`; `resolveForIntent` único en write/restore/recovery/save-resume/index | Intents M4A antiguos no ligados con texto avanzado/covr usan un perfil legacy amplio de recuperación exacta; las escrituras nuevas siguen en `...TEXT_RESOURCES_V2`; sin ampliación de permisos |
| MF05 — publicación con marcador | DONE / REVIEW_GO | `2058a535` | Gate exige marcador Room exacto + revisión durable; sin DAO `DURABLE_EVIDENCE_UNAVAILABLE`; tests de marcador ausente/ajeno/obsoleto/segunda edición/Room caído | Sin prueba unitaria dedicada de la rama null-DAO; `sameTransaction` redundante; muerte de proceso cubierta por store/restore, no por kill explícito |
| MF06 — documentación/certificación | DONE / REVIEW_GO | Este ledger + `a01-a30.csv` | Distinción de evidencia y corrección de limitaciones obsoletas | CI final y reporte de merge aún pendientes |
| MF07 — regresiones/certificación | STATIC/GRADLE_GO | `4c3b204c` | JVM combinado + seis connected metadata + F-Droid 2.457 tests + lint 0 errores + 4 builds + migraciones 3/3 + UI7 `RELEASE_CERTIFIED_GO` | Sin dispositivo físico; CI final exacto pendiente |

### Evidencia y clasificación

- **Dispositivo físico:** no disponible; no se certifica rendimiento ni HAL físico.
- **Emulador / connected:** seis pruebas metadata focalizadas verdes en `Medium_Phone(AVD) - 16`: M4A pair clearing, MP3 described text, M4A artwork+lyrics, MP3 multivalue, SAF M4A resources y FLAC lyrics add/clear; más las campañas A01–A30 previas.
- **Fixtures reales:** MP3/FLAC y AAC M4A audio-only; `me14-audio-only.m4a` verificado con ffprobe.
- **Proveedor sintético / harness:** A25 usa checkpoint sintético y reinicio real; A15–A16 usa fixture APK real con UID distinto y consentimiento MediaStore real.
- **Inyección de fallos:** A23 (Room caído tras escritura), A26 (restauración interrumpida), A28 (ENOSPC/cancelación); no es un ENOSPC físico de almacenamiento.
- **CI remoto:** run `45902391974` intento 2 verde sobre `5ea40f95` (232 tests instrumentados, Quality Gate y resto de jobs); **no** certifica el HEAD `4c3b204c`. El CI final debe correr sobre el SHA exacto integrado.
- **Revisión manual/independiente:** MF01, MF02+MF03 y MF04+MF05 con `REVIEW_GO`; KILLCRITIC combinada encontró dos P1, corregidos en `4c3b204c`; re-revisión combinada REVIEW_GO en `926b9563`.

### Estado de matriz y merge

`me14-evidence/a01-a30.csv` sigue en **30/30 PASS** para el producto certificado `5ea40f95`; A10 se corrige para indicar que covr está cubierto por A11. El HEAD integrado `4c3b204c` incorpora MF01–MF05 y requiere CI/regresión final antes de declarar el nuevo baseline certificado.

No se ha hecho push de este HEAD, no se ha abierto PR y no se ha fusionado a `master`. El merge es obligatorio en la Parte II y se ejecutará tras MF07, KILLCRITIC, CI exacto y verificación de protección.

## Historical pre-MF01 ledger

> La tabla y las notas siguientes son el registro histórico anterior a MF01. Se conservan para trazabilidad y están superadas por la sección autoritativa anterior.

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

## ME-TITAN — Seguridad y base MP4 (2026-11-25)

- `79f01052` — publicación idempotente y durable: evidencia sólo en `Applied`, preimagen durable en genre/people/combined, sincronización de directorio estricta en file/preimagen/intent/journal/recibos/owner, límites de exclusión mutua en el reconciliador.
- `b7677aee` — revisiones monotónicas con trigger BEFORE UPDATE y retención acotada de `metadata_applied_transaction` protegiendo journal/recibos pendientes.
- `3a0f2d1b` — base de inspección MP4 `covr`: parser independiente, evidencia `MP4_COVR`, operaciones de recurso, manifiesto/política/perfil M4A y pruebas. La escritura M4A sigue bloqueada por `M4A_SCALAR_ONLY`.
- Verificación: `:core:testDebugUnitTest :data:testDebugUnitTest :app:testNormalDebugUnitTest` verde en `3a0f2d1b` (run 0024, 111.9 s).
- Matriz A01–A30: 13/30 PASS. Pendiente: escritura M4A covr/stage/E2E, lyrics y multivalor MP3/FLAC, recursos SAF, consentimiento de otra UID, Workbench/Library, resiliencia y KILLCRITIC.
