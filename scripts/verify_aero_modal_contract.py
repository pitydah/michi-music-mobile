#!/usr/bin/env python3
"""AMR-90 self-contained static contract guard. Does not replace Gradle/Robolectric.

Run after applying the final Aero patch in a clean *test* checkout:
    python3 scripts/verify_aero_modal_contract.py
"""
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parent.parent
MAIN = REPO / 'app/src/main/java/org/michimusic/mobile/ui'

def read(path: str) -> str:
    return (MAIN / path).read_text(encoding='utf-8')

fields = read('components/AeroDialogField.kt')
dialogs = read('components/DialogComponents.kt')
settings = read('screens/settings/SettingsComponents.kt')
device = read('screens/devices/DeviceDetailSheet.kt')
strip = read('components/AeroM60Editorial.kt')
test_file = (REPO / 'app/src/test/java/org/michimusic/mobile/ui/components/AeroDialogMaterialAndFormsTest.kt')
checks = {
    'single color policy with high contrast': all(token in fields for token in [
        'aeroDialogFieldColors(', 'LocalVisualPreferences.current.highContrast',
        'focusedBorderColor = focus', 'errorBorderColor = ErrorColor',
        'AeroDialogFieldShape = RoundedCornerShape(12.dp)',
    ]),
    'no new material engine': 'RuntimeShader(' not in fields and 'Haze' not in fields,
    'nine modal inputs share one field style': dialogs.count('colors = aeroDialogFieldColors(') == 9,
    'one settings input shares the style': settings.count('colors = aeroDialogFieldColors(') == 1,
    'six simple forms support scroll with keyboard': dialogs.count('.aeroModalFormViewport()') == 6,
    'no nested scroll on queue': '.aeroModalFormViewport()' not in dialogs.split('fun QueueDialog(',1)[1].split('fun EqualizerDialog(',1)[0],
    'create playlist is disabled while creating': 'enabled = !isCreating && playlistName.isNotBlank()' in dialogs,
    'rename confirmation reports disabled semantic': 'enabled = canConfirm' in dialogs,
    'delete confirmation reports disabled semantic': 'enabled = !isDeleting' in dialogs,
    'password masking and input keyboard': all(token in dialogs for token in [
        'PasswordVisualTransformation()', 'KeyboardType.Password',
        'player_password_input_field',
    ]),
    'form callbacks remain present': all(token in dialogs for token in [
        'onCreate(trimmed)', 'onConnect(serverName.trim().ifEmpty',
        'onConfirm(username.trim(), password.trim())', 'onConfirm(trimmedName)',
    ]),
    'modal buttons have no 44/46dp targets': '.height(44.dp)' not in dialogs and '.height(46.dp)' not in dialogs,
    'icon-only modal close is at least 48dp': 'IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp))' in dialogs,
    'device forget confirmation stays explicit': all(token in device for token in [
        'showUnpairConfirmation = true', 'onForgetDevice()',
        'containerColor = dialogSurface.fill', 'AeroSurfaceRole.Floating',
    ]),
    'Now Playing compact Signal Truth unchanged': all(token in strip for token in [
        'Color(0xFF211A30)', 'TextAlign.Center',
        'signal_strip_footer', 'signal_truth_button', 'audio_route_button',
    ]),
    'Robolectric regressions written': test_file.exists() and test_file.read_text().count('@Test fun ') >= 6,
}
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' ' + name)
print(f'RESULT {sum(checks.values())}/{len(checks)}')
if not all(checks.values()): sys.exit(1)
