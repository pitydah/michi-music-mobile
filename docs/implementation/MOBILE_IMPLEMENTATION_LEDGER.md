# MOBILE_IMPLEMENTATION_LEDGER.md — Michi Music Mobile

## Registro de Fases de Implementación

| Fase | Estado código | Tests JVM | Tests Android | Hardware | SHA | Deuda / dependencia |
|---|---|---|---|---|---|---|
| M0 | VERIFIED | VERIFIED | PENDING_ENV | N/A | 45ed692a | Rescate 13 commits playlists, DI validado, DAO y mutex atómico, UI Error state y Main.immediate |
| M1 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | Autoridad única Media3 y colas persistentes |
| M2 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | Medios locales, SAF, ReplayGain |
| M3 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | USB DAC Route Truth y hotplug |
| M4 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | Hi-Res, DSP/Transparente, Signal Truth |
| M5 | NOT_STARTED | NOT_STARTED | NOT_STARTED | N/A | - | Multi-Library Room v3->v4 y dominio relacional |
| M6 | NOT_STARTED | NOT_STARTED | NOT_STARTED | N/A | - | UI Multi-Library, favoritos, búsqueda |
| M7 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | Michi Link identidad, Keystore recovery, Home trust |
| M8 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | Michi Sync resumable downloads y profiles |
| M9 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | AirCat Mobile consumer contract y Devices UI |
| M10 | NOT_STARTED | NOT_STARTED | NOT_STARTED | BLOCKED_UPSTREAM | - | Catálogos remotos y ReceiverLite Stream |
| M11 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | UI Premium, Settings jerárquico, Android Auto |
| M12 | NOT_STARTED | NOT_STARTED | NOT_STARTED | PENDING_HARDWARE | - | CI, seguridad, certificación y release |
| M13 | NOT_STARTED | NOT_STARTED | NOT_STARTED | N/A | - | IA musical optativa |

---

## Checkpoint de Ejecución — Bloque A (M0)

```text
BLOCK_ID: M0
MASTER_BASE_SHA: bab314cc37ce07dd22af49baecf56f2503b3799c
MASTER_FINAL_SHA: 45ed692a05ed1af7d64fe420ad0c022df8517790
FILES_CHANGED:
  - app/src/main/java/org/michimusic/mobile/screens/PlaylistsViewModel.kt
  - app/src/main/java/org/michimusic/mobile/ui/screens/PlaylistDetailScreen.kt
  - app/src/test/java/org/michimusic/mobile/screens/PlaylistsViewModelTest.kt
  - data/src/main/java/org/michimusic/data/cache/PlaylistDao.kt
  - data/src/main/java/org/michimusic/data/repository/PlaylistRepository.kt
  - data/src/test/java/org/michimusic/data/repository/PlaylistRepositoryTest.kt
  - docs/implementation/MOBILE_BASELINE_AUDIT.md
  - docs/implementation/MOBILE_IMPLEMENTATION_LEDGER.md
PRODUCTION_BEHAVIOR_BEFORE:
  Playlists usaban INSERT OR REPLACE compitiendo en ediciones concurrentes; createPlaylist no normalizaba
  trackIds vacíos ni duplicados distantes; refresco de detalle convertía errores recuperables en NotFound;
  callbacks de navegación/diálogo se ejecutaban en Dispatchers.IO.
PRODUCTION_BEHAVIOR_AFTER:
  createPlaylist normaliza IDs (trim, isNotBlank, distinct, conserva orden) y sincroniza trackCount;
  ediciones usan Mutex y UPDATE targeted (updateTrackIdsAndCount); PlaylistDetailUiState modela estado Error
  con reintento; callbacks UI despachan en Dispatchers.Main.immediate.
UNIT_TESTS_EXECUTED:
  :core:testDebugUnitTest (PASSED)
  :data:testDebugUnitTest (PASSED)
  :player:testDebugUnitTest (PASSED)
  :michi-link-client:testDebugUnitTest (PASSED)
  :app:testNormalDebugUnitTest (PASSED)
  :app:testFdroidDebugUnitTest (PASSED)
INSTRUMENTED_TESTS_EXECUTED: PENDING_ENV (no hay emulador/dispositivo conectado en sesión JVM)
HARDWARE_TESTS_EXECUTED: N/A
UPSTREAM_CONTRACTS: N/A
SECURITY_REVIEW:
  Commit sensible b8d288bb aislado de M0 para implementación rigurosa de recuperación en M7.
  Sin filtración de credenciales en logs o persistencia.
ROLLBACK_PLAN:
  git reset --hard mobile-baseline-20261020-bab314cc (tag de respaldo local confirmado)
BLOCKERS: Ninguno para M0.
NEXT_BLOCK: M1 (Consolidar autoridad única Media3, colas persistentes y recuperación en :player)
```
