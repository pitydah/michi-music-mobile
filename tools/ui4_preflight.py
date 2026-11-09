#!/usr/bin/env python3
"""Non-destructive UI4 preflight on a REAL Michi checkout containing UI3.2.

The old standalone ui3.patch is obsolete for this base. Never reapply or reverse it.
A disposable copy is created and UI4 is checked there using git apply.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

UI32_BASE = "dd1b755b2954ff6f2a0a39b0ff1fe46575b35865"
SRC = Path("app/src/main/java/org/michimusic/mobile")


def execute(args: list[str], cwd: Path, *, text: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(args, cwd=cwd, capture_output=True, text=text, check=False)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_apply(root: Path, patch: Path, *, check: bool = True,
              reverse: bool = False) -> subprocess.CompletedProcess:
    command = ["git", "apply"]
    if reverse:
        command.append("--reverse")
    command.extend(["--check", "--whitespace=error"] if check else ["--whitespace=error"])
    command.append(str(patch))
    return execute(command, root)


def copy_tracked_checkout(root: Path, dest: Path, *, include_untracked: bool) -> int:
    """Copy current files, not Git history or any ignored build output."""
    cmd = ["git", "ls-files", "-z"]
    if include_untracked:
        cmd.extend(["--cached", "--others", "--exclude-standard"])
    result = execute(cmd, root, text=False)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace"))
    seen = set()
    count = 0
    for raw in result.stdout.split(b"\0"):
        if not raw:
            continue
        rel = Path(os.fsdecode(raw))
        if rel in seen:
            continue
        seen.add(rel)
        if rel.is_absolute() or ".." in rel.parts:
            raise RuntimeError(f"Unsafe Git path: {rel!s}")
        source, dest_path = root / rel, dest / rel
        if not source.exists() and not source.is_symlink():
            raise RuntimeError(f"Tracked path missing: {rel!s}")
        dest_path.parent.mkdir(parents=True, exist_ok=True)
        if source.is_symlink():
            dest_path.symlink_to(os.readlink(source))
        elif source.is_file():
            shutil.copy2(source, dest_path)
        else:
            raise RuntimeError(f"Unsupported submodule path: {rel!s}")
        count += 1
    started = execute(["git", "init", "-q"], dest)
    if started.returncode:
        raise RuntimeError(started.stderr.strip())
    return count


def ui32_contracts(root: Path) -> dict[str, bool]:
    """UI3.2's current structure is authoritative, not the old UI3 patch hash."""
    def has(path: str, needle: str) -> bool:
        file = root / SRC / path
        return file.is_file() and needle in file.read_text(encoding="utf-8")
    return {
        "album tracks by disc": has("ui/screens/AlbumDetailScreen.kt", "trackPresentation.tracks"),
        "mosaic keeps lazy state": has("ui/components/AlbumMosaicGrid.kt", "state: LazyGridState"),
        "dock has shared DockTab": has("ui/components/BottomNavBar.kt", "private fun DockTab("),
        "dock supports 2x2": has("ui/components/BottomNavBar.kt", "DockLayout.TWO_BY_TWO"),
        "album route is installed": has("navigation/MichiNavHost.kt", 'route = "album/{albumId}"'),
        "CoverFlow remains": has("ui/screens/AlbumsScreen.kt", "MichiCoverFlowHost("),
        "library retains grid scroll": has("ui/screens/AlbumsScreen.kt", "state = albumGridState"),
    }


def inspect(root: Path, ui4: Path, *, snapshot_worktree: bool = False,
            legacy_ui3: Path | None = None) -> dict[str, object]:
    report: dict[str, object] = {
        "status": "BLOCKED", "base_expected": UI32_BASE,
        "ui4_sha256": sha256(ui4),
        "ui3": "INTEGRATED_UI3_2_NO_REAPPLY",
        "note": "Static Git/source verification only. No Android build, runtime or benchmark.",
    }
    if legacy_ui3 is not None:
        report["legacy_ui3_note"] = "--ui3 ignored: UI3.2 must already exist; old patch must never be applied or reversed"
    top = execute(["git", "rev-parse", "--show-toplevel"], root)
    if top.returncode or Path(top.stdout.strip()).resolve() != root:
        report["error"] = "Root must be a real Git checkout root"
        return report
    report["head"] = execute(["git", "rev-parse", "HEAD"], root).stdout.strip()
    relation = execute(["git", "merge-base", "--is-ancestor", UI32_BASE, "HEAD"], root)
    report["base_is_ancestor"] = relation.returncode == 0
    dirty = execute(["git", "status", "--porcelain", "--untracked-files=no"], root)
    if dirty.returncode or (dirty.stdout.strip() and not snapshot_worktree):
        report["error"] = "Tracked checkout modified. Use --snapshot-worktree to verify an already modified checkout."
        report["dirty_tracked"] = dirty.stdout.strip()[:1200]
        return report
    if not (root / "app" / "build.gradle.kts").is_file():
        report["error"] = "Missing Michi app/build.gradle.kts"
        return report
    with tempfile.TemporaryDirectory(prefix="michi-ui4-ui32-preflight-") as temp:
        scratch = Path(temp)
        report["copied_files"] = copy_tracked_checkout(root, scratch, include_untracked=snapshot_worktree)
        contracts = ui32_contracts(scratch)
        report["ui3_2_contracts"] = contracts
        if not all(contracts.values()):
            report["error"] = "UI3.2 is not fully integrated; refusing old ui3.patch fallback"
            return report
        check = git_apply(scratch, ui4)
        if check.returncode == 0:
            applied = git_apply(scratch, ui4, check=False)
            if applied.returncode:
                report["error"] = "UI4 failed to apply in temporary copy"
                report["detail"] = applied.stderr.strip()[:2500]
                return report
            report["ui4"] = "APPLIED_TO_TEMP_ONLY"
        elif git_apply(scratch, ui4, reverse=True).returncode == 0:
            report["ui4"] = "ALREADY_PRESENT"
        else:
            report["error"] = "UI4 conflicts with integrated UI3.2"
            report["detail"] = check.stderr.strip()[:3500]
            return report
        gate = scratch / "tools" / "ui4_acceptance.py"
        if not gate.is_file():
            report["error"] = "Static acceptance script not found after UI4 application"
            return report
        test = execute([sys.executable, str(gate), "--root", str(scratch), "--json"], scratch)
        if test.returncode:
            report["error"] = "UI4 acceptance source gate failed"
            report["detail"] = (test.stdout or test.stderr)[-3500:]
            return report
        try:
            report["static_checks_passed"] = json.loads(test.stdout)["passed"]
        except (ValueError, KeyError):
            report["error"] = "Acceptance gate returned unexpected JSON"
            return report
    report["status"] = "READY_FOR_GRADLE"
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--ui4", required=True, type=Path)
    parser.add_argument("--ui3", type=Path, help="Obsolete standalone UI3 patch (ignored on integrated UI3.2)")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--snapshot-worktree", action="store_true")
    args = parser.parse_args()
    root, ui4 = args.root.expanduser().resolve(), args.ui4.expanduser().resolve()
    if not ui4.is_file():
        parser.error(f"Missing UI4 patch: {ui4}")
    try:
        result = inspect(root, ui4, snapshot_worktree=args.snapshot_worktree, legacy_ui3=args.ui3)
    except (OSError, RuntimeError) as exc:
        result = {"status": "BLOCKED", "error": str(exc)}
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        for key, value in result.items():
            print(f"{key}: {value}")
    return 0 if result["status"] == "READY_FOR_GRADLE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
