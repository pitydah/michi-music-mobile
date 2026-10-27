#!/usr/bin/env bash
# M22 Audio Core local verification on Antigravity/Android SDK host.
# Does not modify the Compose UI or commit changes.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
REPORT_DIR="${M22_REPORT_DIR:-$ROOT/build/m22-audio-verification/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$REPORT_DIR"
SUMMARY="$REPORT_DIR/RESULTS.md"
{
  echo '# M22 Audio Core — ejecución local'
  echo
  echo "- Fecha local: $(date -Is)"
  echo "- Carpeta: $ROOT"
  echo "- Git HEAD: $(git rev-parse --short HEAD 2>/dev/null || echo 'copia de Drive sin .git')"
  echo "- Java: $(java -version 2>&1 | head -1 || true)"
  echo "- ANDROID_HOME: ${ANDROID_HOME:-no definido}"
  echo '- Nota: éxito de Gradle NO sustituye la certificación física de rutas DAC USB.'
  echo
  echo '| Gate | Resultado | Evidencia |'
  echo '|---|---|---|'
} > "$SUMMARY"

if [[ ! -f ./gradlew ]]; then
  echo '| Wrapper Gradle | **FAIL: gradlew ausente** | No se ejecutaron tareas |' >> "$SUMMARY"
  echo "No existe gradlew en $ROOT. Descarga la carpeta completa, incluido gradlew." >&2
  exit 2
fi
chmod +x ./gradlew
failed=0
run_gate() {
  local label="$1" logname="$2"
  shift 2
  local logfile="$REPORT_DIR/$logname.log"
  echo "==> $label"
  if ./gradlew --no-daemon --stacktrace "$@" >"$logfile" 2>&1; then
    echo "| $label | PASS | $logname.log |" >> "$SUMMARY"
  else
    echo "| $label | **FAIL** | $logname.log |" >> "$SUMMARY"
    failed=1
  fi
}

run_gate 'Player JVM tests' player-unit :player:testDebugUnitTest
run_gate 'App JVM tests' app-unit :app:testNormalDebugUnitTest
run_gate 'Android instrumentation compile' instrumentation-compile :app:compileNormalDebugAndroidTestKotlin
run_gate 'Player lint' player-lint :player:lintDebug
run_gate 'App lint' app-lint :app:lintNormalDebug
run_gate 'App debug APK' app-build :app:assembleNormalDebug

if [[ "${M22_CONNECTED_TESTS:-0}" == "1" ]]; then
  if command -v adb >/dev/null 2>&1 && adb devices | awk 'NR>1 && $2=="device" {n++} END {exit(n>0?0:1)}'; then
    run_gate 'Android connected instrumentation' android-connected :app:connectedNormalDebugAndroidTest
  else
    echo '| Android connected instrumentation | **BLOCKED: no device** | Adjuntar teléfono o emulador |' >> "$SUMMARY"
  fi
else
  echo '| Android connected instrumentation | **NOT RUN** | Repetir con M22_CONNECTED_TESTS=1 |' >> "$SUMMARY"
fi

cat >> "$SUMMARY" <<'TAIL'

## Certificación física DAC (manual)
- [ ] Con DAC USB A seleccionado, reproducir FLAC y comprobar AudioTrack.routedDevice == ID A.
- [ ] Con DAC A y B conectados, seleccionar B, desconectarlo sin retirar A: pausa antes de salida por altavoz.
- [ ] Reconectar DAC con nuevo ID: sin adopción automática silenciosa.
- [ ] Ruta solicitada distinta de la observada: PAUSE, nunca Confirmed.
- [ ] Pausa/reanudación y swap de AudioTrack: no mostrar LastObserved como Confirmed.
- [ ] 44,1/48/96 kHz; PCM 16/24/32/float: observar formatos decodificados y reales de AudioTrack.
- [ ] Sender RTP caído / nuevo sender: fault latch, renegociación, sin fuga hacia altavoz.
- [ ] Reinicio de MediaLibraryService, cola repetida, error de decodificador y cambio de sesión audio.
- [ ] Documentar teléfono, versión de Android, firmware del DAC, pasos, logcat y evidencia acústica.

**Regla de aceptación:** No declarar 100% ni `CERTIFIED` si falta cualquier evidencia obligatoria; consignar `BLOCKED_HARDWARE` o `FAILED`.
TAIL

echo "Resultados: $SUMMARY"
exit "$failed"
