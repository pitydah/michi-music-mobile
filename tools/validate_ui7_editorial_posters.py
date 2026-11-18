#!/usr/bin/env python3
"""Validate shipped UI7 *finished horizontal posters*, independent of the V1 art gate.
No image recomposition or title/frame removal occurs here. Python stdlib only.

Approval is tracked separately from integrity. The 312 sheets reviewed before
2026-11-14 are ``USER_CONFIRMED_COMPLETE`` / ``POSTER_COMPLETE``; the 353 imported from
the UI7-665 pending batch are ``IMPORTED_PENDING_VISUAL_APPROVAL`` /
``POSTER_IMPORTED_PENDING``, meaning "bytes verified, art not reviewed". ``--write``
never promotes a row by itself: an existing state is inherited and a file new to the
pack enters as pending. ``--certified`` fails while anything is still pending.
"""
from __future__ import annotations

import csv
import hashlib
from pathlib import Path
import re
import sys

import validate_ui7_editorial_assets as gate

ROOT = Path(__file__).resolve().parents[1]
POSTERS = ROOT / "app/src/main/assets/ui7/editorial/posters"
HEADER = "# UI7_EDITORIAL_POSTER_MANIFEST_V1"
SOURCE_RE = re.compile(r"UI7_EDITORIAL_LAMINA_[0-9]{2}_[A-Za-z0-9_]+\.png")
FIELDS = 8

APPROVAL = "USER_CONFIRMED_COMPLETE"
PRESENTATION = "POSTER_COMPLETE"
PENDING_APPROVAL = "IMPORTED_PENDING_VISUAL_APPROVAL"
PENDING_PRESENTATION = "POSTER_IMPORTED_PENDING"
REJECTED = "REJECTED"
ACCEPTED_STATES = frozenset({
    (APPROVAL, PRESENTATION),
    (PENDING_APPROVAL, PENDING_PRESENTATION),
})

# The complete Editorial pack: 312 reviewed sheets plus 353 imported ones measure
# 50_220_892 bytes. 52 MiB is the bounded envelope that holds them without ever
# becoming an unbounded allowance; the per-file cap below stays enforced.
MAX_TOTAL = 52 * 1024 * 1024
MAX_WEBP = 1_572_864
# 665 rows measure 179_922 characters today; 192 Ki leaves headroom for longer state
# words while remaining an explicit limit.
MAX_MANIFEST_CHARS = 196_608

EXPECTED_POSTERS = 665


def rows_from_text(manifest: str) -> list[list[str]]:
    if len(manifest) > MAX_MANIFEST_CHARS:
        raise ValueError("oversized poster manifest")
    rows = list(csv.reader(manifest.splitlines(), delimiter="\t"))
    if not rows or rows[0] != [HEADER]:
        raise ValueError("bad poster manifest header")
    return [r for r in rows[1:] if r and not (len(r) == 1 and (not r[0] or r[0].startswith("#")))]


def published_rows(folder: Path = POSTERS) -> dict[str, list[str]]:
    """Rows already published, keyed by filename. Read-only; never invents a state."""
    path = folder / "manifest.tsv"
    if not path.is_file():
        return {}
    try:
        rows = rows_from_text(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError, OSError, csv.Error):
        return {}
    return {row[1]: row for row in rows if len(row) == FIELDS}


def sheets_from_csv(path: Path) -> dict[str, str]:
    """slug -> source sheet filename, from the UI7 recut manifest.

    It is the only authoritative record of which sheet the 40-84 recuts came from:
    the manual only names sheets 01-39, and a poster row may not invent provenance.
    """
    sheets: dict[str, str] = {}
    with path.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            slug = (row.get("slug") or "").strip()
            sheet = (row.get("source_filename") or "").strip()
            if slug and sheet:
                sheets[slug] = sheet
    return sheets


def build_rows(
    folder: Path = POSTERS,
    known: set[str] | None = None,
    inherited: dict[str, list[str]] | None = None,
    sheets: dict[str, str] | None = None,
) -> list[list[str]]:
    """Deterministic inventory over the packaged posters.

    ``inherited`` carries the rows already published, so their approval and sheet
    survive a rewrite; ``sheets`` fills provenance for files new to the pack. A slug
    with no known sheet is an error: a poster without provenance is not publishable.
    """
    known = known if known is not None else gate.source_keys()
    inherited = inherited if inherited is not None else published_rows(folder)
    sheets = sheets or {}
    rows: list[list[str]] = []
    for slug in sorted(known):
        filename = f"genre_{slug}_editorial.webp"
        previous = inherited.get(filename)
        path = folder / filename
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"documented poster is not packaged: {filename}")
        if previous is not None:
            sheet = previous[5]
            state, presentation = previous[6], previous[7]
            if state == REJECTED:
                raise ValueError(f"rejected poster is still packaged: {filename}")
            if (state, presentation) not in ACCEPTED_STATES:
                state, presentation = PENDING_APPROVAL, PENDING_PRESENTATION
        else:
            sheet = sheets.get(slug, "")
            state, presentation = PENDING_APPROVAL, PENDING_PRESENTATION
        if not SOURCE_RE.fullmatch(sheet):
            raise ValueError(f"no recorded source sheet for {slug}")
        payload = path.read_bytes()
        width, height = gate.webp_size(payload)
        rows.append([
            slug, filename, hashlib.sha256(payload).hexdigest(),
            str(width), str(height), sheet, state, presentation,
        ])
    return rows


def write_manifest(
    folder: Path = POSTERS,
    inherited: dict[str, list[str]] | None = None,
    sheets: dict[str, str] | None = None,
) -> None:
    rows = build_rows(folder, inherited=inherited, sheets=sheets)
    (folder / "manifest.tsv").write_text(
        HEADER + "\n" + "".join("\t".join(row) + "\n" for row in rows),
        encoding="utf-8", newline="\n")


def validate(folder: Path = POSTERS, certified: bool = False) -> list[str]:
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
    used_slugs: set[str] = set()
    pending: list[str] = []
    total = 0
    for number, cells in enumerate(records, 2):
        if len(cells) != FIELDS:
            errors.append(f"poster row {number}: expected {FIELDS} fields")
            continue
        slug, filename, sha, width, height, sheet, state, presentation = cells
        if slug not in known or filename != f"genre_{slug}_editorial.webp" or filename in used:
            errors.append(f"poster row {number}: unknown or duplicate slug/file")
            continue
        used.add(filename)
        used_slugs.add(slug)
        if state == REJECTED:
            errors.append(f"poster row {number}: rejected artwork must not be packaged")
        elif (state, presentation) not in ACCEPTED_STATES:
            errors.append(f"poster row {number}: incorrect state or presentation")
        elif state == PENDING_APPROVAL:
            pending.append(filename)
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
    uncovered = sorted(known - used_slugs)
    if uncovered:
        errors.append("approved slug without poster: " + ", ".join(uncovered[:5]))
    if len(used_slugs) != EXPECTED_POSTERS:
        errors.append(f"expected exactly {EXPECTED_POSTERS} posters, found {len(used_slugs)}")
    if total > MAX_TOTAL:
        errors.append(
            f"poster payloads {total} B exceed the {MAX_TOTAL} B offline APK asset budget")
    if certified and pending:
        errors.append(
            f"{len(pending)} posters are still pending visual approval, starting with "
            + ", ".join(sorted(pending)[:3]))
    return errors


def pending_count(folder: Path) -> int | None:
    """How many packaged rows wait for art review, or None if the pack is unreadable."""
    try:
        rows = rows_from_text((folder / "manifest.tsv").read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001 - an unreadable pack has no count to report
        return None
    return sum(1 for row in rows if len(row) == FIELDS and row[6] == PENDING_APPROVAL)


if __name__ == "__main__":
    arguments = sys.argv[1:]
    source_csv: Path | None = None
    if "--sources" in arguments:
        source_csv = Path(arguments[arguments.index("--sources") + 1])
    folder = POSTERS
    if "--folder" in arguments:
        folder = Path(arguments[arguments.index("--folder") + 1])
    if "--write" in arguments:
        write_manifest(
            folder,
            inherited=published_rows(POSTERS),
            sheets=sheets_from_csv(source_csv) if source_csv else None,
        )
        print(f"UI7 Editorial poster manifest rewritten ({EXPECTED_POSTERS} entries)")
    certified = "--certified" in arguments
    problems = validate(folder, certified=certified)
    if problems:
        # Structured receipt for the CI release gate; see the Mosaico validator.
        if certified:
            count = pending_count(folder)
            if count is not None:
                print(f"UI7_RELEASE_GATE view=posters status=ARTISTIC_APPROVAL_PENDING "
                      f"pending={count}")
        print("UI7 Editorial POSTER FAIL\n" + "\n".join(problems), file=sys.stderr)
        sys.exit(1)
    rows = rows_from_text((folder / "manifest.tsv").read_text(encoding="utf-8"))
    approved = sum(1 for row in rows if len(row) == FIELDS and row[6] == APPROVAL)
    print(f"UI7 Editorial POSTER PASS (posters: {len(rows)}, approved: {approved}, "
          f"pending: {len(rows) - approved})")
