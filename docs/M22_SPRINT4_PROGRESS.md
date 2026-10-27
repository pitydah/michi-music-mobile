# Michi Music Mobile · M22 Sprint 4 — Audio-only / Preparación Antigravity

Fecha: 25 de octubre de 2026 (Chile). Base: Google Drive `michi-music-mobile`, tras M22 Sprint 3.

## Límite de intervención

Solo `player/`: AudioRouteObserver, PCM tap RTP, PlayerDependencies, pruebas de audio y un gate nuevo. No se editaron archivos de Compose, componentes UI, iconos, navegación, recursos o temas. El script del repositorio no modifica código.

## Implementaciones

1. **Confianza en ruta actual:** `PlaybackRouteEvidenceGate` exige simultáneamente `Media3 isPlaying` y `AudioTrack PLAYSTATE_PLAYING`. `DefaultAudioRouteObserver` no puede restablecer `Confirmed` después de una pausa solo porque AudioTrack conservó PLAYING momentáneamente. Permanece la distinción entre Requested, Confirmed y LastObserved, y el descarte de callbacks de tracks obsoletos del sprint anterior.
2. **Excepción RTP:** `RtpPcmAudioTap` contiene `Exception` del callback del sender; pausa envío, establece `hasDeliveryFault` y conserva el silencio local cuando el usuario solicitó reproducción remota (nunca activa altavoz por sorpresa). El stream exige recuperación explícita.
3. **Generaciones RTP:** un callback antiguo, fallando después de iniciar otro sender, no puede contaminarlo: captura un número de generación de emisor y se comprueba al procesar la excepción. `startSender` y `stopSender` son atómicos respecto a la captura del listener.
4. **Formato:** si el sender está habilitado antes de conocer la primera configuración PCM, se exige renegociación. El `onReset` activo también invalida la negociación anterior. Preserva FLAC→PCM portable, sin afirmar bit-perfect.
5. **Contrato de servicios:** `PlayerDependencies.startPcmStreaming`, `stopPcmStreaming`, `resumePcmStreaming` y `pcmStreamingDeliveryFailed` usan la nueva autoridad de sender; `resume` se niega si hay fault hasta establecer una sesión nueva.
6. **Pruebas + herramientas:** nuevos casos JUnit, gate JUnit y script local reproducible para Gradle, lint, APK y Android instrumentado.

## Pruebas ejecutadas en este entorno (solo JVM aislada)

- **13/13** escenarios directos de PCM Tap con stubs de Media3 y corrutinas reales.
- **6/6** escenarios de PlaybackRouteEvidenceGate sin Android.
- **1/1** escenario integrado PlayerDependencies/Tap con stubs de DAOs, validando comienzo/fallo/no-resume/reinicio/parada.
- **18/18** métodos JUnit reales del repositorio y nuevos, reejecutados con clases Media3 simuladas. *Estos 18 se solapan con los escenarios previos; no sumarlos como pruebas distintas.*
- **No ejecutados:** Gradle/AGP/SDK completo, instrumentation test, Android real, DAC USB físico, detección de underruns en hilo real, fallback renderer/sink bajo Media3 1.6.1.

## Punto crítico que permanece

Un callback de emisor RTP **lento pero que no lanza excepción** todavía puede bloquear el hilo de audio. Debe perfilarse bajo Android y, si se confirma, desacoplar por cola acotada/cancelable y política de backpressure. Además, un `DefaultAudioSink`/renderer de fallback que excluya el tap requiere señalización explícita hacia el servicio de streaming remoto; actualmente la degradación de Signal Truth no demuestra por sí misma transporte válido.

## Puertas para llegar al 100%

Consultar `docs/M22_AUDIO_ANTIGRAVITY_HANDOFF.md`. Es obligatorio pasar Gradle, instrumentación Android y pruebas de un/two DAC reales; adjuntar evidencia de cada puerta. Nunca sustituirlas por resultados de stubs. Mantener el conteo de blockers de `RELEASE_BLOCKERS.md` hasta obtener las evidencias.

## Avance estimado, no certificado

- M22-A (servicio/Media3/Signal Truth): 95% (sin validación Android nueva).
- M22-B (USB DAC y PCM remoto seguro): 96% (sprint anterior: 94%).
- M22-C (UI + validación integral): 76% (sin intervención; otro chat modifica UI).
- M22 total, promedio ilustrativo: **89%** frente al ~88% anterior; el avance verificable es código y 20 escenarios independientes, **no** cumplimiento del 100%.
