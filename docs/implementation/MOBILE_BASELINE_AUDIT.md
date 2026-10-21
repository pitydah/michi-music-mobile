# MOBILE_BASELINE_AUDIT.md — Michi Music Mobile

## 1. Identificación y Estado Inicial

| Parámetro | Valor Verificado |
|---|---|
| Repositorio | `https://github.com/pitydah/michi-music-mobile.git` |
| Rama Activa | `master` |
| HEAD Inicial de Auditoría | `bab314cc37ce07dd22af49baecf56f2503b3799c` |
| Tag de Respaldo Local | `mobile-baseline-20261020-bab314cc` |
| Estado del Árbol Git | Limpio (sin archivos modificados ni stash destructivo) |
| Entorno JDK | 17.0.20.1 (Arch Linux 17.0.20.1+1) / Daemon compatible con Java 21 |
| Gradle | 8.13 |
| Android SDK | `/home/cristian/Android/Sdk` |

---

## 2. Módulos y Proyectos

El proyecto se compone de 5 módulos activos:
- `:app`: Interfaz Jetpack Compose, navegación `MichiNavHost`, módulos Koin DI, `SyncWorker`.
- `:core`: Modelos de dominio (`Track`, `Album`, `Artist`, `Playlist`, etc.).
- `:data`: Room database (`MichiDatabase`), repositorios de medios (`LocalMediaRepository`, `PlaylistRepository`, `SyncedTrackRepository`).
- `:player`: `MichiPlaybackService`, `AudioController`, `PlayerController`, `UsbDacManager`, efectos y procesadores de audio.
- `:michi-link-client`: `LinkClient`, `EventClient`, `LinkDiscovery`, `TokenStore`.

---

## 3. Estado de Pruebas de Línea Base

Antes de cualquier intervención se ejecutó la suite de tests unitarios:
- `:core:testDebugUnitTest`: PASSED (up-to-date)
- `:data:testDebugUnitTest`: PASSED (up-to-date)
- `:player:testDebugUnitTest`: PASSED (up-to-date)
- `:michi-link-client:testDebugUnitTest`: PASSED (up-to-date)
- Resultado: 59 tareas ejecutadas/up-to-date, 0 fallos.

---

## 4. Auditoría de Ramas Históricas

- `feature/playlists`: 14 commits exclusivos respecto a `master`.
  - 13 commits de playlists, Media3 y UI funcional integrados selectivamente a `master` en M0 vía `cherry-pick`.
  - Commit `b8d288bb8607512357d227e24e520c14b0de7674` (recuperación de identidad tras fallo en almacenamiento cifrado) aislado para rediseño y endurecimiento seguro en M7.
- `feature/playlists-audio-import`: Solo contiene workaround para URI `content://`, no se fusiona.
- `dev-jose`, `feature/consolidation`: Sin código divergente activo.
- `automation/runtime-ui-capture-20260720`: Scripts de captura de referencia, no fusionados.

---

## 5. Hardware y Dependencias de Entorno

- Teléfono Android físico: No conectado en este entorno (marcado como `PENDING_HARDWARE`).
- Dongles DAC USB / hardware externo: No conectados (marcado como `PENDING_HARDWARE`).
- Servidores remotos (Michi Micro/Big Server, Michi Stream ESP32): `BLOCKED_UPSTREAM` para pruebas E2E reales.
