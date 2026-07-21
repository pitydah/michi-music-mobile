#!/usr/bin/env bash
set -Eeuo pipefail

PACKAGE="org.michimusic.mobile.debug"
ACTIVITY="org.michimusic.mobile.MainActivity"
OUT_DIR="runtime-screenshots"
APK="app/build/outputs/apk/normal/debug/app-normal-debug.apk"

mkdir -p "$OUT_DIR"

finish() {
  adb logcat -d > "$OUT_DIR/logcat.txt" 2>/dev/null || true
  adb shell dumpsys window > "$OUT_DIR/window-dumpsys.txt" 2>/dev/null || true
  adb shell dumpsys activity activities > "$OUT_DIR/activity-dumpsys.txt" 2>/dev/null || true
  adb shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/app-meminfo.txt" 2>/dev/null || true
}
trap finish EXIT

capture_screen() {
  local name="$1"
  timeout 15s adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml "$OUT_DIR/${name}.xml" >/dev/null 2>&1 || true
  adb exec-out screencap -p > "$OUT_DIR/${name}.png"
}

dump_ui() {
  timeout 15s adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || return 1
  adb pull /sdcard/window.xml /tmp/window.xml >/dev/null 2>&1 || return 1
}

has_ui_label() {
  local label="$1"
  dump_ui || return 1
  python3 - "$label" <<'PY'
import sys
import xml.etree.ElementTree as ET

label = sys.argv[1]
root = ET.parse('/tmp/window.xml').getroot()
for node in root.iter('node'):
    text = node.attrib.get('text', '')
    desc = node.attrib.get('content-desc', '')
    if label in text or label in desc:
        raise SystemExit(0)
raise SystemExit(1)
PY
}

wait_for_ui_label() {
  local label="$1"
  local attempts="${2:-60}"
  for _ in $(seq 1 "$attempts"); do
    if has_ui_label "$label"; then
      return 0
    fi
    sleep 2
  done
  echo "La UI no mostró el texto esperado: $label" >&2
  capture_screen "00-timeout-${label// /-}"
  return 1
}

tap_node() {
  local label="$1"
  local prefer="${2:-top}"
  dump_ui

  local point
  point="$(python3 - "$label" "$prefer" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

label = sys.argv[1]
prefer = sys.argv[2]
root = ET.parse('/tmp/window.xml').getroot()
results = []
for node in root.iter('node'):
    text = node.attrib.get('text', '')
    desc = node.attrib.get('content-desc', '')
    if text != label and desc != label:
        continue
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds', ''))
    if not match:
        continue
    x1, y1, x2, y2 = map(int, match.groups())
    if x2 <= x1 or y2 <= y1:
        continue
    source_rank = 0 if desc == label else 1
    results.append((source_rank, y1, (x1 + x2) // 2, (y1 + y2) // 2, x2 - x1, y2 - y1))

if not results:
    raise SystemExit(2)

if prefer == 'bottom':
    results.sort(key=lambda item: (item[0], -item[1], -(item[4] * item[5])))
elif prefer == 'largest':
    results.sort(key=lambda item: (item[0], -(item[4] * item[5]), item[1]))
else:
    results.sort(key=lambda item: (item[0], item[1], -(item[4] * item[5])))

_, _, x, y, _, _ = results[0]
print(f'{x} {y}')
PY
)" || {
    echo "No se encontró el nodo UI: $label" >&2
    return 1
  }

  read -r x y <<< "$point"
  adb shell input tap "$x" "$y"
  sleep 4
}

echo "Estabilizando el dispositivo virtual..."
adb wait-for-device
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global show_first_crash_dialog 0 || true
adb shell settings put global anr_show_background 0 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb shell settings put global device_provisioned 1 || true
adb shell settings put secure user_setup_complete 1 || true
adb shell wm dismiss-keyguard || true
adb shell wm size 720x1280 || true
adb shell wm density 320 || true
adb shell input keyevent 82 || true
sleep 25

echo "Instalando el APK real compilado previamente..."
test -f "$APK"
adb install -r --no-streaming "$APK"
adb shell pm grant "$PACKAGE" android.permission.READ_EXTERNAL_STORAGE || true
adb shell pm grant "$PACKAGE" android.permission.READ_MEDIA_AUDIO || true
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
adb shell cmd package compile -m speed -f "$PACKAGE" || true
adb logcat -c || true

# Primera ejecución real, sin biblioteca cargada.
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/$ACTIVITY"
wait_for_ui_label "Inicio" 90
sleep 8
capture_screen "01-inicio-sin-biblioteca"

# Crea audio de prueba local para activar los estados reales con biblioteca.
rm -rf /tmp/michi-runtime-audio
mkdir -p /tmp/michi-runtime-audio
python3 <<'PY'
import math
import os
import struct
import wave

tracks = [
    ('Aurora Austral', 220.00),
    ('Bosque de Palena', 246.94),
    ('Luz de Invierno', 261.63),
    ('Ruta Patagónica', 293.66),
    ('Noche en Chiloé', 329.63),
    ('Michi Session', 349.23),
]
out = '/tmp/michi-runtime-audio'
rate = 22050
seconds = 2
for index, (title, frequency) in enumerate(tracks, start=1):
    path = os.path.join(out, f'{index:02d} - {title}.wav')
    with wave.open(path, 'w') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        frames = bytearray()
        for n in range(rate * seconds):
            envelope = min(1.0, n / (rate * 0.05), (rate * seconds - n) / (rate * 0.08))
            sample = int(8000 * envelope * math.sin(2 * math.pi * frequency * n / rate))
            frames.extend(struct.pack('<h', sample))
        wav.writeframes(frames)
PY

adb shell rm -rf /sdcard/Music/MichiRuntime
adb shell mkdir -p /sdcard/Music/MichiRuntime
adb push /tmp/michi-runtime-audio/. /sdcard/Music/MichiRuntime/ >/dev/null
for file in /tmp/michi-runtime-audio/*.wav; do
  base="$(basename "$file")"
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/MichiRuntime/$base" >/dev/null || true
done
sleep 15
adb shell content query --uri content://media/external/audio/media > "$OUT_DIR/media-store.txt" 2>&1 || true

# Relanza para que el repositorio lea MediaStore desde cero.
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/$ACTIVITY"
wait_for_ui_label "Inicio" 90
sleep 12
capture_screen "02-inicio-con-biblioteca"

# Capturas de navegación real. Si una pantalla falla, conserva las demás.
tap_node "Biblioteca" || true
sleep 5
capture_screen "03-biblioteca"

tap_node "Inicio" || true
tap_node "Aleatorio" largest || true
sleep 8
tap_node "Ahora" || true
sleep 5
capture_screen "04-ahora-reproduciendo"

tap_node "Remoto" || true
sleep 5
capture_screen "05-remoto"

tap_node "Sync" || true
sleep 5
capture_screen "06-sync"

tap_node "Ajustes" || true
sleep 5
capture_screen "07-ajustes"

tap_node "Inicio" || true
tap_node "Buscar canciones..." largest || true
sleep 5
capture_screen "08-busqueda"

# Evidencia técnica de que la actividad quedó ejecutándose.
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > "$OUT_DIR/resumed-activity.txt" || true
ls -lah "$OUT_DIR"
