# M22 — Sprint 5 / Auditoría KILLCRITIC del transporte PCM (audio-only)

Fecha: 25-10-2026. Repositorio: `michi-music-mobile`, copia original de Drive.
**Frontera de trabajo: exclusiva de `player/`**. No se modificó `app/`, Compose, navegación, diseños, iconos, temas ni contratos visuales de Michi Aero (M23–M27).

## Hallazgos confirmados en los fuentes del Sprint 4

- **S5-P0-01 / bloqueos de audio real-time**: `RtpPcmAudioTap.queueInput()` invocaba `pcmChunkListener` de manera síncrona. `PlayerDependencies.startPcmStreaming()` conectaba directamente la función de envío al tap. Una función de red lenta puede bloquear el hilo PCM de Media3, producir underruns y comprometer la reproducción. **Corregido en el nuevo recorrido de producción**: `BoundedPcmSender` aísla el envío en `Michi-PCM-Network` con cola FIFO de 8 bloques y `offer` no bloqueante respecto al trabajo de red. La cola usa un candado de memoria corto, por lo que el tiempo real estricto debe medirse en Android.
- **S5-P0-02 / datos silenciosamente perdidos por contrapresión**: al saturar la cola se informa fallo de entrega; no se hace `drop-oldest`, que corrompería la continuidad temporal. `RtpPcmAudioTap.reportSenderFailureIfCurrent()` detiene el envío de forma fail-closed y conserva el mute local para no activar el altavoz sin consentimiento.
- **S5-P1-03 / uso de memoria**: límite adicional de 1 MiB por bloque. Con cola de 8 elementos la carga de trabajo pendiente queda limitada a 8 MiB más un bloque en emisión; no se atribuye al proyecto una cuota de memoria total.
- **S5-P1-04 / corrupción inter-formato**: los cambios de PCM y `reset` vacían los bloques pendientes; `isRenegotiating` mantiene cerrada la emisión hasta el handshake/resume explícito. **Límite importante**: la operación de envío ya en curso no puede cancelarse si la API de red ignora las interrupciones. El receptor debe usar sesiones y timestamps para rechazar datagramas tardíos.
- **S5-P1-05 / carreras de sesión**: los errores de un worker anterior no modifican una sesión nueva porque se comprueba la identidad de listener; el cierre y recuperación son explícitos.
- **S5-P2-06 / limpieza de pruebas**: `resetTestingOverrides()` ahora cierra un worker pendiente antes de limpiar el tap, evitando hilos y mute residuales en suites JVM.

## Evidencia local ejecutada

- `kotlinc` ejecutó **25/25 tests** (7 `BoundedPcmSenderTest`, 15 `RtpPcmAudioTapTest`, 3 `PlayerDependenciesPcmTest`).
- El código real de `BoundedPcmSender`, `RtpPcmAudioTap` y `PlayerDependencies` compiló contra *stubs* Media3/Android y `kotlinx-coroutines-core-jvm`. Las 25 pruebas ejecutan estas implementaciones reales.
- **No se ejecutó Gradle del proyecto, APK, Android Lint, pruebas instrumentadas ni hardware DAC.** No son pruebas de desempeño acústico ni certificación de Android.
- El parche no introduce dependencias Gradle externas ni modifica rutas de código Compose.

## Validación obligatoria para Antigravity

1. Ejecutar `bash tools/verify_m22_audio.sh` (archivo del Sprint 4) desde raíz de la copia; inspeccionar logs de `:player:testDebugUnitTest`, `:app:testNormalDebugUnitTest`, `:app:assembleNormalDebug`, lint y AndroidTest.
2. Compilar/examinar `BoundedPcmSender`, `RtpPcmAudioTap`, `PlayerDependencies`; conservar las pruebas nuevas, corregir cualquier incompatibilidad real Media3 1.6.1 en la copia descargada.
3. Probar FLAC/PCM 44,1/48/96/192 kHz según disponibilidad real; testear pausa, reanudación, cambio de pista, suspensión de app, fallo del receptor, UDP lento, backpressure, reconexión e interrupción. Verificar que una sesión remota averiada **no produce sonido por el altavoz del teléfono**.
4. Perfilado: medir latencia del callback `queueInput` y underruns en Android real; **no aprobar** sin ausencia de bloqueos significativos por red. Medir memoria de cola, pérdidas detectadas y cierre de workers; forzar sender bloqueado y constatar que la app puede detenerse/reiniciarse.
5. Rutas DAC USB: conectar/desconectar con reproducción activa y buffering, múltiples DAC, reconexión con nuevo ID; corroborar `Confirmed` vs `LastObserved` con AudioTrack. La verificación final del DAC nunca se deduce del tipo anunciado.
6. Probar fallback Media3 sin tap instrumentado: jamás mostrar mediciones PCM inexistentes ni declarar streaming efectivo. Este control **permanece abierto**.
7. Probar integración con M27 Aero sin editar sus pantallas. Ante conflictos de UI crear hallazgo separado para el responsable visual, no sobrescribir UI.

## Deuda sin resolver por esta intervención

- Worker anterior con llamada de red no interrumpible: puede terminar tarde. Debe aislarse por identificador de sesión en capa RTP; cerrar transporte subyacente de forma efectiva.
- El estado de fallo PCM es consultable (`pcmStreamingDeliveryFailed`), pero no tiene un `StateFlow` público de errores/eventos. Integrar con autoridad de PlaybackSession de forma coordinada; no simular UI ahora.
- Fallback de Media3, pruebas multiformato y pruebas físicas (Android/DAC) no certificados.
- Controles de política AUTO/DSP/ReplayGain y unidades de medición de AudioTrack deben auditarse en dispositivo; **no** prometer bit-perfect o DSD en Mobile.

## Métrica y alcance

**6/6 archivos Kotlin del parche**: 3 existentes modificados y 3 nuevos. Solo puede darse por completa la implementación de este parche cuando Drive verifique sus bytes; eso no equivale a certificación M22. El porcentaje global anterior de M22 era estimado (89%); no se recalcula al alza sin la compilación y pruebas físicas pendientes.
