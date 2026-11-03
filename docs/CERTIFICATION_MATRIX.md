# Matriz de Certificación y Validación Física — Michi Music Mobile (M12)

Fecha de auditoría y certificación: 21 de Octubre de 2026 (Sprint KILLCRITIC)
Última actualización de procedimientos: 3 de Noviembre de 2026 (estabilización Aero)
Estado global de hardware: **`HARDWARE_GO = BLOCKED`** — ningún ítem de la sección 2 fue certificado en hardware físico; los procedimientos de 2.1 existen para ejecutarlos cuando el equipo esté disponible.  
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
| **USB Mixer API 34+** | `UsbDacManager.requestMixerRate()` con un DAC que anuncie `AudioMixerAttributes` | Preferencia aceptada por Android visible en la UI y aplicada tras reinicio de la salida; el mensaje no debe prometer medición del reloj del DAC | `PENDING_HARDWARE` (Requiere dispositivo API 34+ y DAC con mixer) |
| **Cámara QR real (pairing)** | Escanear el QR `michi://pair?…` que muestra Michi Music Player con CameraX + ZXing | El enlace llega completo al campo de emparejamiento; permiso denegado y dispositivo sin cámara degradan al pegado manual | `PENDING_HARDWARE` (Solo se verificó el preview en el emulador; la decodificación se cubre con `QrDecoderTest` JVM) |

### 2.1 Procedimientos de validación física (a ejecutar, no ejecutados)

Cada procedimiento exige la misma evidencia: captura de pantalla de la app, salida del
comando `adb` indicado, `adb logcat` filtrado por la etiqueta relevante y el SHA del
build instalado. Sin esos cuatro elementos el ítem sigue `PENDING_HARDWARE`.

#### A. DAC USB PCM (16-bit 44.1/48 kHz)

1. Conectar el DAC por USB-C con un adaptador OTG y confirmar `adb shell dumpsys audio | grep -i usb`.
2. Abrir Now Playing y tocar el sello de ruta de audio hasta que `UsbDacManager` reporte la ruta como `Confirmed`.
3. Reproducir un FLAC 16/44.1 y verificar en Signal Truth: `Fuente` con el códec real del archivo, `Salida Android` observada y `Dispositivo físico` en `No verificado` hasta que exista evidencia del DAC.
4. Desconectar el DAC en caliente durante la reproducción.

Criterio: la reproducción se pausa sola al desconectar, la ruta vuelve al altavoz sin silencio
prolongado, y Signal Truth nunca declara `Confirmed` una ruta que ya no existe.

#### B. DAC USB Hi-Res (24/96 y 24/192) y mezclador API 34

1. Reproducir un FLAC 24/96 y anotar lo que reporta Signal Truth (`bitDepth` solo puede
   mostrarse si la clasificación es lossless).
2. En Ajustes → Calidad de Audio & DSP, abrir el panel USB con el DAC conectado y elegir
   manualmente 96 kHz; volver a la reproducción y confirmar que Android aceptó la preferencia.
3. Reiniciar la salida de audio (desconectar/reconectar) y verificar que la preferencia se
   reaplica o que la UI explica que debe reconfirmarse.
4. Repetir con un DAC que no anuncie `AudioMixerAttributes` (API < 34 o hardware limitado).

Criterio: la app distingue "preferencia aceptada por Android" de "reloj del DAC verificado";
el texto de la sección USB no debe afirmar una medición que no se hizo. Con hardware sin
mixer, la UI muestra el mensaje explícito y no deja controles muertos.

#### C. Bluetooth LDAC / AAC / SBC

1. Emparejar auriculares con LDAC forzado desde las opciones de desarrollador, reproducir y
   anotar el códec que reporta `adb shell dumpsys bluetooth_manager`.
2. Repetir con AAC y con SBC.
3. Apagar los auriculares y encenderlos de nuevo en mitad de una pista.

Criterio: la pausa al perder la ruta es inmediata, al reconectar no se reanuda solo sin
autorización, y Signal Truth no atribuye al archivo la calidad del códec Bluetooth.

#### D. Nodo revocado en la LAN

1. Emparejar el móvil con Michi Home y confirmar sync manual correcto.
2. Revocar el dispositivo desde la interfaz de Michi Home.
3. Lanzar un sync manual y un intento de streaming remoto.

Criterio: el handshake y las descargas se rechazan con un mensaje honesto, la app no
reintenta en bucle y el dispositivo revocado desaparece de la lista de endpoints activos
en la siguiente observación (la capa observable de `PairedDeviceRegistry` no puede
mostrarlo como autorizado).

#### E. Android Auto

1. Conectar la consola (cableada) y abrir Michi en la pantalla del vehículo.
2. Navegar el catálogo, iniciar reproducción y cambiar de pista desde los controles del auto.

Criterio: el catálogo se sirve vía `MediaLibraryService` sin dependencias de Google Play
Services, la navegación funciona con el foco del vehículo y no hay crashes al desconectar.

#### F. Cámara QR de emparejamiento

1. Mostrar en Michi Music Player la pantalla con el QR `michi://pair?format=michi-link-pairing&…`.
2. En el móvil: Dispositivos → menú → Escanear código QR → conceder permiso de cámara.
3. Escanear con luz baja y con el QR en ángulo; repetir negando el permiso.

Criterio: el payload llega completo al campo de enlace y habilita "Vincular"; con el permiso
denegado o sin cámara el diálogo mantiene el pegado manual como camino real, sin prometer
un escáner que no está activo.



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
