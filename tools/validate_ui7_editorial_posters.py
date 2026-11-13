#!/usr/bin/env python3
"""Validate shipped UI7 *finished horizontal posters*, independent of the V1 art gate.
No image recomposition or title/frame removal occurs here. Python stdlib only.
"""
from __future__ import annotations

import csv
import hashlib
from pathlib import Path
import re
import sys
from typing import Iterable

import validate_ui7_editorial_assets as gate

ROOT = Path(__file__).resolve().parents[1]
POSTERS = ROOT / "app/src/main/assets/ui7/editorial/posters"
HEADER = "# UI7_EDITORIAL_POSTER_MANIFEST_V1"
SOURCE_RE = re.compile(r"UI7_EDITORIAL_LAMINA_[0-9]{2}_[A-Za-z0-9_]+\.png")
MAX_TOTAL = 20 * 1024 * 1024
MAX_WEBP = 1_572_864


def rows_from_text(manifest: str) -> list[list[str]]:
    if len(manifest) > 160_000:
        raise ValueError("oversized poster manifest")
    rows = list(csv.reader(manifest.splitlines(), delimiter="\t"))
    if not rows or rows[0] != [HEADER]:
        raise ValueError("bad poster manifest header")
    return [r for r in rows[1:] if r and not (len(r) == 1 and (not r[0] or r[0].startswith("#")))]


def validate(folder: Path = POSTERS) -> list[str]:
    errors: list[str] = []
    path = folder / "manifest.tsv"
    if folder.is_symlink() or not folder.is_dir() or path.is_symlink() or not path.is_file():
        return ["poster directory/manifest must be real regular paths"]
    try:
        known = gate.source_keys()
        records = rows_from_text(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError, OSError, csv.Error) as error:
        return [f"poster manifest unreadable: {error}"]
    used: set[str] = set()
    total = 0
    for number, cells in enumerate(records, 2):
        if len(cells) != 8:
            errors.append(f"poster row {number}: expected 8 fields")
            continue
        slug, filename, sha, width, height, sheet, state, presentation = cells
        if slug not in known or filename != f"genre_{slug}_editorial.webp" or filename in used:
            errors.append(f"poster row {number}: unknown or duplicate slug/file")
            continue
        used.add(filename)
        if state != "USER_CONFIRMED_COMPLETE" or presentation != "POSTER_COMPLETE":
            errors.append(f"poster row {number}: incorrect state or presentation")
        if not SOURCE_RE.fullmatch(sheet) or not re.fullmatch(r"[a-f0-9]{64}", sha):
            errors.append(f"poster row {number}: bad provenance/hash")
        file = folder / filename
        if file.is_symlink() or not file.is_file():
            errors.append(f"poster row {number}: missing/unsafe WebP")
            continue
        payload = file.read_bytes()
        total += len(payload)
        if len(payload) > MAX_WEBP:
            errors.append(f"poster row {number}: too many WebP bytes")
        if hashlib.sha256(payload).hexdigest() != sha:
            errors.append(f"poster row {number}: SHA-256 mismatch")
        try:
            dims = gate.webp_size(payload)
            requested = (int(width), int(height))
            w, h = dims
            if dims != requested or w < 320 or h < 200 or w > 4096 or h > 4096 or w * h > gate.MAX_PIXELS or not 1.4 <= w / h <= 4.5:
                errors.append(f"poster row {number}: invalid dimensions {dims}")
        except (ValueError, IndexError) as exc:
            errors.append(f"poster row {number}: invalid WebP: {exc}")
    others = {p.name for p in folder.iterdir() if p.suffix.lower() == ".webp"} - used
    if others:
        errors.append("undocumented poster WebP: " + ", ".join(sorted(others)))
    if total > MAX_TOTAL:
        errors.append("poster payloads exceed offline APK asset budget")
    return errors


if __name__ == "__main__":
    problems = validate()
    if problems:
        print("UI7 Editorial POSTER FAIL\n" + "\n".join(problems), file=sys.stderr)
        sys.exit(1)
    print("UI7 Editorial POSTER PASS")
