# Inicio en Antigravity — M22 Sprint 5

1. Abrir la carpeta completa de `michi-music-mobile` descargada de Drive. Nunca sobreescribir los sprints M23–M27 visuales.
2. Validar árbol limpio y rutas de estos seis archivos: `player/src/main/java/org/michimusic/player/{BoundedPcmSender,RtpPcmAudioTap,PlayerDependencies}.kt`, `player/src/test/java/org/michimusic/player/{BoundedPcmSenderTest,RtpPcmAudioTapTest,PlayerDependenciesPcmTest}.kt`.
3. Ejecutar `bash tools/verify_m22_audio.sh`; adjuntar todos los logs. Si los task names difieren, usar `./gradlew tasks --all` y ejecutar los equivalentes.
4. Testear `./gradlew :player:testDebugUnitTest` y pruebas Android instrumentadas con un dispositivo; corregir errores de compilación, races y regresiones bajo el **alcance player/**.
5. Simular un listener RTP que bloquea: comprobar que Media3 continúa; al llenar la cola, la salida local sigue silenciada y el fallo permanece hasta reconexión explícita.
6. Verificar la integridad de los formatos PCM y de la sucesión de sesiones. En paralelo, probar Signal Truth, rutas USB y degradación de Media3 sin instrumentos.
7. No tocar `app/src/main/java/**/ui/**`, Compose, recursos, tokens ni CoverFlow. Dejar hallazgos cruzados en issue / informe de integración.
8. Considerar 100% certificado solo si build/lint/tests actuales, regresiones Media3 reales y matriz USB DAC física pasan. Registrar fallos y evidencia reproducible, no inflar porcentajes.

Referencias: `M22_SPRINT5_AUDIO_CORE_REPORT.md` y el handoff preexistente `docs/M22_AUDIO_ANTIGRAVITY_HANDOFF.md`.
