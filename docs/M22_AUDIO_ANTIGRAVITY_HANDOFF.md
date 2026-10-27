# Michi Music Mobile — M22 / núcleo de audio / Antigravity

**Entrega:** Sprint 4, 25-10-2026. **Destino:** carpeta original `michi-music-mobile` en Google Drive. **Medio de ejecución:** copia descargada, Android SDK y teléfono real.

## 0. Alcance y frontera de coordinación

Esta intervención modifica **solo `player/` y la documentación/herramienta de verificación del núcleo de audio**. Se prohíbe modificar Compose, temas, iconografía, navegación, `app/src/main/java/**/ui/**`, diseños, o suprimir trabajos simultáneos de la conversación UI/UX. `app` es compilado y probado **sin editarlo**. Si Antigravity descubre un problema en `app`, documentar su localización y separar la propuesta en otra rama; no resolverlo con cambios de UI en este sprint.

`Media3` (no "Media4") es el motor Android del repositorio. Este módulo Mobile es Hi-Res PCM/FLAC portátil: **NO prometer DSD, DoP, bit-perfect ni tasa final certificada por DAC físico.** Distinguir fuente, PCM decodificado, configuración AudioTrack y ruta observada.

## 1. Estado del trabajo ejecutado en Drive

- `player/src/main/java/org/michimusic/player/PlaybackRouteEvidenceGate.kt` — nuevo gate puro, requiere Media3 + AudioTrack reproduciendo para autorizar `Confirmed`.
- `.../AudioRouteObserver.kt` — utiliza el gate, impide revivir `Confirmed` al estar pausado y descarta callbacks de AudioTrack anterior.
- `.../RtpPcmAudioTap.kt` — el callback remoto fallido no derriba el hilo Media3; persiste el silencio local solicitado, almacena `hasDeliveryFault`, exige recuperación explícita, bloquea cambios de formato y descarta errores tardíos de emisores antiguos mediante generación.
- `.../PlayerDependencies.kt` — adopta `startSender`/`stopSender` con reinicio explícito y no reanuda un sender con fault latched; diagnóstico consultable con `pcmStreamingDeliveryFailed`.
- `player/src/test/java/org/michimusic/player/RtpPcmAudioTapTest.kt` — amplía JUnit con fallos RTP, formato, reinicios y callback obsoleto.
- `.../PlaybackRouteEvidenceGateTest.kt` — 5 JUnit nuevos sobre autorización de rutas.
- `tools/verify_m22_audio.sh` — script local, guarda logs, no altera UI.

Los archivos se reemplazaron en sus ID originales o se crearon en los directorios efectivos; no son propuestas de parche en una carpeta paralela. Hay ZIP de respaldo y manifest SHA-256. Integrar en tu descarga de Drive.

## 2. Evidencia ejecutada ANTES de entregar a Antigravity

- `kotlinc` con **implementación real** de `RtpPcmAudioTap.kt` y stubs Media3 + coroutine Flow: **13/13 PASS**. Incluye PCM 16/24/32/float, fallo del listener, silencio local ante remoto, renegociación inicial y cambio de formato, reset y callback viejo después de nuevo sender.
- `kotlinc` con el **gate real** de rutas de audio: **6/6 PASS**.
- Se escribió código de pruebas JUnit reales, pero **Gradle, Media3 Android, instrumentación, emulador y DAC físico NO EJECUTADOS** en Drive. La compilación aislada no demuestra compatibilidad total con Media3 ni ejecución de listeners reales.

## 3. Instrucción ejecutable para Antigravity (Gemini Flash)

**OBJETIVO:** cerrar técnicamente M22-A+B a 100% SOLO cuando la evidencia sea completa. No reportar porcentajes "por intuición" ni corregir test mediante omisión. Lee primero `M22_AUDIO_ANTIGRAVITY_HANDOFF.md`, código indicado, `RELEASE_BLOCKERS.md`, `TEST_REPORT.md` y los informes M22 anteriores. Compara cambios con la rama principal si existe historial. Preserva todos los trabajos UI concurrentes.

**Fase 1 — Reproducibilidad y compilación**
1. Inspecciona `git status --short`, módulos, `gradlew`, `build.gradle.kts`, flavors y JDK/SDK antes de actuar.
2. Ejecuta `bash tools/verify_m22_audio.sh` con `M22_CONNECTED_TESTS=0`. Guarda sus logs y estado de cada gate. Si falla compilación Kotlin o Media3 1.6.1, corrige el código de `player/`, no sustituyas las APIs por mocks productivos ni elimines funcionalidad para compilar.
3. Ejecuta todas las pruebas `:player:testDebugUnitTest`, incluyendo pruebas nuevas del tap, gate, UsbDacManager y SignalTruth. Investiga y corrige *todas* las regresiones en `player/`.
4. Ejecuta lint, compila `androidTest` y build debug de `app`; documenta cada error independiente, especialmente fallos preexistentes del trabajo paralelo UI.

**Fase 2 — Invariantes de reproducción**
5. Demuestra en pruebas que existe una sola instancia efectiva para ReplayGain, `SignalTruthTracker` y `AudioModeManager`; la UI puede observarla sin duplicar autoridades. El modo TRANSPARENT debe impedir EQ/ReplayGain incluso al alternar opciones de usuario; AUTO usa la ruta actual, no la sola presencia del dongle.
6. Prueba fallos de construcción de CustomAudioSink/Renderer: el fallback debe mantener un renderer de audio real, declarar instrumentación degradada y **no iniciar silenciosamente un streaming remoto que carece de PCM tap**. Si falta comunicación de estado entre servicio/tap/sender, implementar contrato de fallo explícito en `player/`, con pruebas; no asumir éxito basándose en `isEnabled`.
7. Verifica Source → Decoded PCM → DSP real → AudioTrack → ruta real (diferenciar datos medidos y declarados). `LastObserved` es histórico y nunca confirmación vigente; los estados de falla no se sobrescriben con `Detected` o `Requested` por escaneos redundantes.
8. Verifica ciclos `service onCreate/onDestroy`, interrupción durante `BUFFERING`, `READY`, `PAUSED`, `IDLE`, `ENDED`, errores de decodificador y `AudioTrackReleased`. Retira listeners y jobs; nunca retengas referencias obsoletas ni apliques metadatos de una generación de reproducción ya reemplazada.

**Fase 3 — DAC / rutas y emisiones RTP**
9. Ejecuta pruebas con 0, 1 y 2 DAC USB; A/B: solicitud vs confirmación, retirada B mientras A persiste, hotplug de mismo hardware con Android ID nuevo, transición a altavoz/BT inesperada, y modo AUTO. No afirmar `Confirmed` si Media3 está pausado aunque AudioTrack notifique `PLAYSTATE_PLAYING` tardíamente.
10. Prueba PCM 16/24/32/float y archivos FLAC 44.1/48/96 kHz sin alterar bits declarados por instrumento. Revisa formato cambiado durante RTP, buffering, cancelación, reintento y fallo de callback, además de la latencia/bloqueo de callback sobre hilo audio (riesgo de underrun). Exigir sender no bloqueante o una cola acotada con backpressure si se demuestra bloqueo.
11. `M22_CONNECTED_TESTS=1 bash tools/verify_m22_audio.sh` con teléfono Android conectado. Adjunta `adb logcat`, versión Android, modelos DAC, pasos de reproducción y observación real. Si no existe hardware, reportar `BLOCKED_HARDWARE`, no inventar pruebas ni declarar 100%.

**Fase 4 — Reporte de cierre auditable**
12. Entrega una tabla por gate `PASS / FAIL / BLOCKED / NOT_RUN`, fallos detectados, archivos+líneas editados, resultados antes/después, logs, y evidencia de recuperación real. No mezclar pruebas Kotlin con mocks y pruebas Android reales.
13. Actualiza `RELEASE_BLOCKERS.md` y `TEST_REPORT.md` **solo** con pruebas nuevas efectivamente ejecutadas; conserva la trazabilidad histórica. No borres advertencias de hardware pendientes.
14. Añade resumen `git diff --stat`, `git status --short`, commits propuestos y recomendaciones. No fusionar cambios UI ajenos, no reescribir master, no eliminar archivos de otros módulos.

### Criterios de aceptación del 100% técnico

- [ ] Gradle compila y corre JVM tests sin fallos del audio actual.
- [ ] Lint Android y build APK terminan en PASS.
- [ ] Android test harness conecta con el verdadero MediaLibraryService/Media3.
- [ ] DAC USB real satisface matriz A/B y manejo fail-closed.
- [ ] PCM/Sender no produce fuga audible inesperada ni send tras cambio de formato.
- [ ] Signal Truth no inventa tasa física, bit-perfect o hardware no medidos.
- [ ] Evidencia y fecha de cada gate publicadas, reproducibles y vinculadas.

## 4. Cómo iniciar

```bash
cd michi-music-mobile
bash tools/verify_m22_audio.sh
M22_CONNECTED_TESTS=1 bash tools/verify_m22_audio.sh
```

El script escribe los resultados en `build/m22-audio-verification/<fecha>/RESULTS.md` y logs por tarea. Ejecutar la segunda orden solo con `adb devices` mostrando un dispositivo `device`. No ejecutar simultáneamente con el chat que está editando UI; al descargar/importar, verificar que los cambios UI recientes siguen intactos.

## 5. Bloqueos y porcentaje

**No hay certificación de 100% desde Drive.** Este sprint termina con código y pruebas aisladas verificadas; las puertas Gradle, integración Android y DAC físico siguen pendientes. El 100% final es una decisión basada en evidencia local, no en porcentajes de tickets completados.
