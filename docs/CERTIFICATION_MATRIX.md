# Matriz de Certificación y Validación Física — Michi Music Mobile (M12)

Fecha de auditoría y certificación: 21 de Octubre de 2026 (Sprint KILLCRITIC)  
Versión base: `0.1.0-alpha` / `0.1.0-alpha-fdroid`  
Objetivo: Certificación rigurosa, auditable y veraz de capacidades móviles, compatibilidad de hardware y límites operativos conforme al roadmap maestro.

---

## 1. Matriz de Pruebas Funcionales por Capa

| Capa / Subsistema | Pruebas Imprescindibles | Evidencia / Mecanismo de Verificación | Estado |
|---|---|---|---|
| **Core & Room DB** | Invariantes de dominio, migración Room v3→v4, integridad de listas de reproducción y defaults | `Migration3To4Test` en `:data`, suites JVM de DAO y cache | **VERIFIED** |
| **Player & Media3** | `MediaLibraryService`, colas persistentes, avance ante pistas faltantes/corruptas, `PlaybackErrorMapper` | `MissingTrackRecoveryTest`, `AudioControllerTest`, suite `:player` | **VERIFIED** |
| **Playlists** | CRUD atómico con Mutex, reordenamiento, persistencia Room y soporte offline | `PlaylistRepositoryTest`, `PlaylistEntityTest` | **VERIFIED** |
| **SAF & MediaStore** | Permisos granulares (`READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` máx 32), importador SafMediaRepository | Pruebas unitarias de repositorio + `AndroidManifest.xml` validado | **VERIFIED** |
| **USB DAC & Audio Routes** | Veracidad de ruta (`Confirmed` vs `Observed`), hotplug auto-pausa, `OutputDescriptor` | `UsbDacManagerTest`, `AudioRouteObserverTest`, dialógos UI | **VERIFIED** |
| **Michi Link & Identidad** | Criptografía Ed25519, recuperación de Keystore ante corrupción de backup, borrado de credenciales en WorkData | `MichiIdentityTest`, `IdentityStorageRecoveryTest`, `Ed25519KeyLoadingTest` | **VERIFIED** |
| **Sync & Resumable Downloads** | Descarga reanudable con rangos HTTP, verificación de checksums SHA-256, promoción atómica `.part` | `ResumableTransferTest`, `SyncManagerTest`, perfiles de red | **VERIFIED** |
| **Remote Stream & ReceiverLite** | Negociación RTP PCM, degradación elegante si no hay hardware receptor, aislamiento de URIs locales | `SimulatedReceiverLiteE2ETest`, `QueueMappingTest` | **VERIFIED** |
| **UI & Accesibilidad** | Targets táctiles >= 48dp, espaciados `MichiSpacing`, modo alto contraste y sin animaciones | `A11yAndTokensTest`, `SettingsHierarchyTest` | **VERIFIED** |
| **Release & Seguridad** | ProGuard/R8 reglas activas, exclusión en cloud backup (`data_extraction_rules`), expurgo de reportes | `assembleNormalRelease`, `DiagnosticsRedactionTest` | **VERIFIED** |

---

## 2. Matriz de Validación Física y Dispositivos de Prueba

> [!IMPORTANT]
> Los ítems marcados como `PENDING_HARDWARE` o `BLOCKED_UPSTREAM` son auditados con honestidad técnica. No se asume certificación física en un entorno de desarrollo o integración continua headless.

| Equipo / Periférico | Acción de Prueba Requerida | Resultado Esperado | Estado de Verificación |
|---|---|---|---|
| **Android API 31 / 32** | Permiso `READ_EXTERNAL_STORAGE` y escaneo inicial de almacenamiento | Acceso a archivos de audio sin crash ni loops de permiso | `PENDING_HARDWARE` (Planificado en dispositivo físico) |
| **Android API 33 / 34 / 35** | `READ_MEDIA_AUDIO`, importación SAF con `OpenMultipleDocuments`, hotplug USB | Cero fugas de URIs de contenido privado, importación limpia | `PENDING_HARDWARE` (Planificado en Pixel 7 / S23) |
| **DAC USB Tipo-C (PCM estándar)** | Conexión en caliente durante reproducción de FLAC/MP3 16-bit 44.1kHz | Detección veraz de `USB_HEADSET` / `USB_DEVICE`, audio sin distorsión | `PENDING_HARDWARE` (Validación física manual con DAC USB-C) |
| **DAC USB Hi-Res (24/96 & 24/192)** | Reproducción de pista FLAC 24-bit / 96kHz | Reporte veraz de ruta; `UNKNOWN` si la HAL de Android resamplea | `PENDING_HARDWARE` (Requiere analizador USB/DAC con display) |
| **Bluetooth (LDAC / AAC / SBC)** | Desconexión en caliente / apagado de auriculares BT | Pausa inmediata del reproductor Media3 conforme a directrices de audio | `PENDING_HARDWARE` |
| **Michi Link con Nodo Revocado** | Dispositivo cliente revocado desde la interfaz de Michi Home | Rechazo inmediato de handshake y denegación de descargas | `PENDING_HARDWARE` (Requiere servidor Michi Home en LAN) |
| **AirCat Peer de Red** | Interrupción intencional de Wi-Fi durante transferencia de archivo grande | Reanudación sin corrupción utilizando validación por SHA-256 | `PENDING_HARDWARE` |
| **ESP32-S3 / ReceiverLite** | Streaming RTP directo a 44.1kHz / 48kHz | Latencia baja y sincronización de buffer sin buffer underrun crónico | `BLOCKED_UPSTREAM` (Hardware receptor en desarrollo de firmware) |
| **Android Auto (Consola)** | Conexión cableada/inalámbrica de infoentretenimiento vehicular | Catálogo navegable vía MediaLibraryService; sin dependencias privativas | `ANDROID_AUTO_PENDING` (Pendiente de certificación automotriz) |

---

## 3. Threat Modeling y Reporte de Privacidad / Seguridad

### A. Almacenamiento Seguro e Identidad (Keystore)
- Las claves privadas de identidad (`Ed25519`) se generan y resguardan mediante `AndroidKeyStore` con `EncryptedSharedPreferences`.
- En caso de corrupción por migración o restauración de backup entre dispositivos, `IdentityStorageRecovery` rota las claves y reinicia el estado de manera segura sin colgar la app.
- Las preferencias de identidad se encuentran expresamente excluidas del backup en la nube mediante `res/xml/data_extraction_rules.xml` y `res/xml/backup_rules.xml`.

### B. Confidencialidad en Red y Diagnósticos
- No se transmiten credenciales ni identificadores privados en texto plano fuera de la red local.
- La pantalla de diagnósticos (`DiagnosticsScreen`) utiliza `DiagnosticsRedactor` para expurgar tokens de portador, secretos, contraseñas e IPs privadas de los informes antes de copiarlos al portapapeles o exportarlos.

### C. Aislamiento de Componentes Android
- `MichiPlaybackService` tiene fijado `android:exported="false"`, impidiendo que aplicaciones maliciosas de terceros secuestren la sesión de reproducción o inyecten comandos arbitrarios.
- No se expone ningún `ContentProvider` sin protección de permisos.

### D. Política de Privacidad de Datos
1. **Sin telemetría oculta:** La aplicación no envía telemetría de uso a servidores analíticos ni a Google/Firebase en ninguna de sus variantes.
2. **Sabor F-Droid 100% Libre:** La variante `fdroid` está libre de cualquier dependencia de Google Play Services (`GMS`).
3. **Control Local Primero:** Todos los metadatos de biblioteca, listas de reproducción y favoritos se almacenan de manera estrictamente local en SQLite/Room (`michi_music.db`).
