#!/usr/bin/env python3
"""AMR-09 release preflight: static checks only; no SDK/build claims.

Runs against an ISOLATED test checkout containing the cumulative Aero patch.
Returns 0 for passing static gates, 1 for actual failure, and 2 for missing baseline.
Emit machine-readable result without printing private credentials or logs.
"""
from __future__ import annotations
from hashlib import sha256
import json
from pathlib import Path
import re
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/org/michimusic/mobile/ui"
RESULT = ROOT / "build/verification/aero/aero-static-gates.json"
BASELINE = "09a48411617daf81d2f93576fd27690da8c7962f"
EXPECTED_ROUTES = (
    'home', 'library', 'search', 'devices', 'sync_wizard/{deviceId}',
    'nowplaying', 'settings', 'playlist', 'playlist/{playlistId}', 'queue',
    'synced', 'diagnostics', 'audio-route', 'remote', 'sync',
)
EXPECT_MODAL = (
    'QueueDialog', 'EqualizerDialog', 'QrScannerDialog', 'ManualConnectionDialog',
    'CreatePlaylistDialog', 'AudioRouteDialog', 'PinPairingDialog',
    'ReceiverButtonPairingDialog', 'PlayerPasswordPairingDialog',
    'AddTracksDialog', 'RemoveTrackFromPlaylistDialog', 'DeletePlaylistDialog',
    'RenamePlaylistDialog', 'RemoteQueueConfirmationDialog',
    'TrackActionBottomSheet', 'AddToPlaylistDialog', 'DeviceDetailSheet',
)
checks: list[dict] = []

def ck(label: str, ok: bool, detail: str = "") -> None:
    checks.append({"gate": label, "result": "PASS" if ok else "FAIL", "detail": detail})

def text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""

ck("baseline documented", BASELINE in text(ROOT / "docs/design/ROUTE_COVERAGE_MATRIX.md"))
nav = ROOT / "app/src/main/java/org/michimusic/mobile/navigation/MichiNavHost.kt"
if nav.is_file():
    source = text(nav)
    routes = set(re.findall(r'composable\(\s*"([^"\n]+)"', source))
    routes.update(re.findall(r'route\s*=\s*"([^"\n]+)"', source))
    ck("navigation route coverage", set(EXPECTED_ROUTES) <= routes,
       f"expected={len(EXPECTED_ROUTES)} present={len(set(EXPECTED_ROUTES)&routes)}")
else:
    checks.append({"gate": "navigation route coverage", "result": "BLOCKED",
                   "detail": "Full repository required: navigation/MichiNavHost.kt unavailable"})
route_map = text(ROOT / "docs/design/ROUTE_COVERAGE_MATRIX.md")
ck("all 15 route rows documented", all(f'`{r}`' in route_map for r in EXPECTED_ROUTES))
modal_map = text(ROOT / "docs/design/MODAL_COVERAGE_MATRIX.md")
ck("all 17 modal rows documented", all(f'`{m}`' in modal_map for m in EXPECT_MODAL))
ck("unexecuted routes disclosed", "NOT_RUN" in route_map and "0/15" in route_map)
ck("unexecuted modals disclosed", "NOT_RUN" in modal_map)
strip = text(UI / "components/AeroM60Editorial.kt")
ck("Now Playing retains compact plum strip", all(s in strip for s in (
    'Color(0xFF211A30)', 'signal_truth_button', 'audio_route_button',
    'TextAlign.Center', 'signal_strip_footer',
)) and 'Text("RUTA DE AUDIO"' not in strip)
ck("Aero finish family is canonical", all(n in text(UI / "theme/AeroMaterials.kt")
    for n in ('Plain', 'Satin', 'Anodized', 'Smoked', 'Inset')))
build_file = ROOT / "app/build.gradle.kts"
if build_file.is_file():
    ck("no new design dependencies", all(x not in text(build_file) for x in (
        'chrisbanes.haze', 'paper-shaders', 'compose-unstyled', 'cloudy', 'prismal')))
else:
    checks.append({"gate": "no new design dependencies", "result": "BLOCKED",
                   "detail": "Full repository required: app/build.gradle.kts unavailable"})
versions = ROOT / 'gradle/libs.versions.toml'
if versions.is_file():
    v = text(versions)
    ck("locked Kotlin AGP Compose toolchain", all(needle in v for needle in (
        'agp = "8.9.1"', 'kotlin = "2.1.10"', 'compose-bom = "2025.05.00"')))
else:
    checks.append({"gate": "locked Kotlin AGP Compose toolchain", "result": "BLOCKED",
                   "detail": "Full repository required: gradle/libs.versions.toml unavailable"})
ck("single shared modal field", 'aeroDialogFieldColors(' in text(UI / "components/AeroDialogField.kt"))
ck("password obscured", 'PasswordVisualTransformation()' in text(UI / "components/DialogComponents.kt"))
ck("reduced per-item visual overload", 'AeroFinish.Plain' in text(UI / "screens/settings/SettingsComponents.kt"))
grain = ROOT / 'app/src/main/res/drawable-nodpi/aero_graphite_grain.png'
if grain.is_file():
    data = grain.read_bytes()
    good = (data[:8] == b'\x89PNG\r\n\x1a\n' and len(data)>29 and
            struct.unpack('>II',data[16:24]) == (128,128) and
            data[24:26] == b'\x08\x06')
    ck("original 128px RGBA texture valid", good, 'sha256='+sha256(data).hexdigest())
else:
    ck("original 128px RGBA texture valid", False)
# Android first frame and system bars use the same Aero canvas as Compose.
theme_xml = text(ROOT / 'app/src/main/res/values/themes.xml')
ck("Android window and bars match Aero obsidian", all(
    f'<item name="android:{item}">#090C12</item>' in theme_xml
    for item in ('windowBackground', 'statusBarColor', 'navigationBarColor')
))
ck("asset provenance recorded", 'aero_graphite_grain' in text(ROOT / 'docs/design/AERO_ASSET_PROVENANCE.json'))
ck("references and licenses retained", all(x in text(ROOT / 'docs/design/AERO_MATERIAL_REFERENCE_LIBRARY.md')
    for x in ('chrisbanes', 'paper-shaders', 'haze')))
ck("modal contract script retained", (ROOT / 'scripts/verify_aero_modal_contract.py').is_file())

result = {
    "gate": "AMR09_STATIC_ONLY", "expected_baseline": BASELINE,
    "status": ('FAIL' if any(c['result']=='FAIL' for c in checks) else
               'BLOCKED' if any(c['result']=='BLOCKED' for c in checks) else 'PASS'),
    "checks": checks, "android_gradle": "NOT_RUN", "robolectric": "NOT_RUN",
    "android_normal_fdroid": "NOT_RUN", "physical_device": "NOT_REQUIRED",
    "ready_to_apply": False,
}
RESULT.parent.mkdir(parents=True,exist_ok=True)
RESULT.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
for c in checks:
    print(c['result'], c['gate'], c['detail'])
print('RESULT',result['status'],'PASS',sum(c['result']=='PASS' for c in checks),'TOTAL',len(checks))
print('ANDROID_BUILD=NOT_RUN READY_TO_APPLY=false')
sys.exit(1 if result['status']=='FAIL' else 2 if result['status']=='BLOCKED' else 0)
