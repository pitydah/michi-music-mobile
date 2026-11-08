#!/usr/bin/env python3
"""Static source acceptance gate for UI4 after OpenCode applies UI3 + UI4.

This is intentionally not an Android build or an instrumentation substitute.
It prevents shipping a partially applied diff or regressing fixed Michi policies.
Usage: python3 tools/ui4_acceptance.py --root . [--json]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

SRC = "app/src/main/java/org/michimusic/mobile/"


def acceptance(root: Path) -> list[dict[str, object]]:
    checks: list[dict[str, object]] = []

    def source(rel: str) -> str:
        path = root / rel
        if not path.is_file():
            return ""
        return path.read_text(encoding="utf-8")

    def require(name: str, rel: str, needle: str) -> None:
        content = source(rel)
        checks.append({"name": name, "ok": needle in content, "path": rel})

    nav = SRC + "navigation/MichiNavHost.kt"
    mini = SRC + "ui/components/MiniPlayer.kt"
    now = SRC + "ui/screens/NowPlayingScreen.kt"
    albums = SRC + "ui/screens/AlbumsScreen.kt"
    mosaic = SRC + "ui/components/AlbumMosaicGrid.kt"
    detail = SRC + "ui/screens/AlbumDetailScreen.kt"
    motion = SRC + "ui/motion/"

    require("UI3 detail is present", detail, "fun AlbumDetailScreen(")
    require("UI3 album mosaic is present", mosaic, "fun AlbumMosaicGrid(")
    require("UI3.2 preserves grid state", mosaic, "state: LazyGridState")
    require("UI3.2 preserves track presentation", detail, "trackPresentation.tracks")
    require("UI3.2 preserves disc headings", detail, "presented.position?.discNumber")
    require("UI3.2 retains scroll location", albums, "state = albumGridState")
    require("UI3.2 retains the 2x2 dock", SRC + "ui/components/BottomNavBar.kt", "DockLayout.TWO_BY_TWO")
    require("UI4 animates the shared DockTab", SRC + "ui/components/BottomNavBar.kt", "val emphasis = aeroDockSelectionEmphasis(isSelected)")
    require("CoverFlow was not deleted", albums, "MichiCoverFlowHost(")
    require("UI4 wraps navigation in one scope", nav, "SharedTransitionLayout(modifier = Modifier.fillMaxSize())")
    require("UI4 avoids duplicate nowplaying destinations", nav, "launchSingleTop = true")
    for prop in ("enterTransition", "exitTransition", "popEnterTransition", "popExitTransition"):
        name = f"M52 global {prop} remains off"
        # Check the global NavHost declaration, not route-local transitions.
        transition_type = "EnterTransition" if "enter" in prop.lower() else "ExitTransition"
        require(name, nav, f"{prop} = {{ {transition_type}.None }}")
    require("MiniPlayer uses stable metadata slots", mini, 'testTag("mini_player_endpoint_status")')
    require("MiniPlayer removes measurement feedback", mini, "onTextLayout")
    checks[-1]["ok"] = not checks[-1]["ok"]
    require("MiniPlayer artwork stays local", mini, ".aeroSharedNowPlayingArtwork(")
    checks[-1]["ok"] = not checks[-1]["ok"]
    require("Now Playing artwork stays local", now, ".aeroSharedNowPlayingArtwork(")
    checks[-1]["ok"] = not checks[-1]["ok"]
    require(
        "Player transition policy forbids shared artwork",
        motion + "AeroSharedNowPlayingMotion.kt",
        "shareArtwork = false",
    )
    require("Now Playing uses the bounded dynamic mesh", now, "AeroDynamicMeshBackground(")
    require("Dynamic Mesh is gated before rendering", now, "if (aeroDynamicMeshEnabled())")
    require("Dynamic Mesh reads the build policy", motion + "AeroDynamicPalette.kt", "BuildConfig.AERO_DYNAMIC_MESH_ENABLED")
    require("Dynamic Mesh defaults on", "app/build.gradle.kts", '.orElse("true")')
    require("Dynamic Mesh exposes the same-SHA A/B property", "app/build.gradle.kts", 'gradleProperty("michi.dynamicMeshEnabled")')
    require("Dynamic Mesh has no stale false certification gate", motion + "AeroDynamicPalette.kt", "AeroDynamicMeshPerformanceCertified")
    checks[-1]["ok"] = not checks[-1]["ok"]
    require("UI4 retains album shared artwork", mosaic, ".aeroSharedAlbumArtwork(")
    require("UI4 retains reversible album detail", detail, ".aeroSharedAlbumArtwork(")
    require("UI4 rejects remote palette borrowing", motion + "AeroDynamicMeshBackground.kt", "aeroLocalPaletteSource(it.source, it.coverId, it.filepath)")
    require("UI4 rejects remote numeric artwork", motion + "AeroTrackMotion.kt", "source != TrackSource.LOCAL && value.toLongOrNull() != null")
    require("UI4 never accepts bare numeric synchronized audio URI", motion + "AeroPaletteCachePolicy.kt", "numericLocalCover || isLocalUri(coverId")
    require("UI4 skips dynamic palette decode in battery saver", motion + "AeroDynamicMeshBackground.kt", "powerSaveMode = powerSaving")
    require("UI4 keeps a read-only checkout preflight", "tools/ui4_preflight.py", "def copy_tracked_checkout(")
    require("UI4 forbids remote embedded-art fallback", motion + "AeroTrackMotion.kt", "source == TrackSource.STREAMING")
    require("UI4 snaps local player motion in high contrast", motion + "AeroSharedNowPlayingMotion.kt", "!highContrast")
    require("UI4 shares album art only within contrast policy", motion + "AeroSharedAlbumMotion.kt", "!highContrast")
    require("UI4 does not animate in background", motion + "AeroDynamicMeshBackground.kt", "foreground = inForeground")
    require("UI4 respects high contrast during album reveal", motion + "AeroAlbumMotion.kt", "preferences.highContrast")
    require("UI4 keeps truthful indeterminate progress", motion + "AeroTruthfulProgress.kt", "ProgressBarRangeInfo.Indeterminate")
    require("UI4 uses measured bytes", motion + "AeroTruthfulProgress.kt", "fun aeroMeasuredFraction(")
    require("UI4 does not add OpenGL dependency", "app/build.gradle.kts", "om252345")
    # This gate is an INVERSE check: om252345 must not appear.
    checks[-1]["ok"] = not checks[-1]["ok"]

    for rel in (nav, mini, now, detail, mosaic, motion + "AeroDynamicMeshBackground.kt"):
        content = source(rel)
        checks.append({
            "name": f"No conflict markers in {Path(rel).name}",
            "ok": all(not line.startswith(("<<<<<<<", "=======", ">>>>>>>"))
                      for line in content.splitlines()),
            "path": rel,
        })

    return checks


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="Michi Git checkout, after UI3 and UI4")
    parser.add_argument("--json", action="store_true", help="Machine-readable acceptance report")
    args = parser.parse_args()
    root = args.root.resolve()
    if not (root / "app/build.gradle.kts").is_file():
        parser.error("--root must point at a Michi Music Mobile repository checkout")
    results = acceptance(root)
    failed = [c for c in results if not c["ok"]]
    if args.json:
        print(json.dumps({"checks": results, "passed": len(results) - len(failed),
                          "failed": len(failed), "note": "Static only; Android compilation not run"},
                         ensure_ascii=False, indent=2))
    else:
        for check in results:
            print(f"{'PASS' if check['ok'] else 'FAIL'}: {check['name']} ({check['path']})")
        print(f"Static checks: {len(results) - len(failed)}/{len(results)}. "
              "OpenCode must still compile and run Android tests.")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
