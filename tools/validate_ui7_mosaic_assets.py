#!/usr/bin/env python3
"""Fail-closed gate for the UI7 "Mosaico destacado" local artwork pack.

The 312 WebP crops are copied byte-for-byte from the approved Mosaico source.
This gate never re-crops, renames, re-encodes or reorders them: it only hashes,
measures and cross-checks the checked-in manifest. Run with ``--write`` to
regenerate ``manifest.tsv`` deterministically from the packaged files.

Genre keys are derived from the filename suffix only (``UI7_MOSAICO_NNN_<Genre>.webp``)
so an asset can never be attached to a genre that the source pack did not name.
Python stdlib only; reuse the WebP container reader from the Editorial gate.
"""
from __future__ import annotations

import csv
import hashlib
from pathlib import Path
import re
import sys
import unicodedata

import validate_ui7_editorial_assets as gate

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/ui7/mosaic"
MANIFEST = ASSETS / "manifest.tsv"
HEADER = "# UI7_MOSAICO_MANIFEST_V1"
FIELDS = 6
APPROVAL = "USER_CONFIRMED_COMPLETE"
EXPECTED_ASSETS = 312
MAX_WEBP = 1_572_864
MAX_PIXELS = 3_686_400
MAX_MANIFEST_CHARS = 96_000

FILENAME_RE = re.compile(r"UI7_MOSAICO_([0-9]{3})_([A-Za-z0-9]+)\.webp")

# Suffixes the CamelCase tokenizer cannot prove on its own. Every other suffix
# must split into plain words; there is no fuzzy fallback.
SUFFIX_ALIASES = {"RnB": "r&b"}

# Mirror of genreGroupingKey() in GenreClassificationPolicy.kt, so a manifest key
# is the same key the Kotlin resolver derives from a real Track.genre label.
GROUPING_ALIASES = {
    "synthpop": "synth-pop", "synth pop": "synth-pop", "synth-pop": "synth-pop",
    "newwave": "new wave", "new-wave": "new wave", "new wave": "new wave",
    "lofi": "lo-fi", "lo fi": "lo-fi", "lo-fi": "lo-fi",
    "hiphop": "hip-hop", "hip hop": "hip-hop", "hip-hop": "hip-hop",
    "kpop": "k-pop", "k-pop": "k-pop", "k pop": "k-pop",
    "jpop": "j-pop", "j-pop": "j-pop", "j pop": "j-pop",
    "rnb": "r&b", "r&b": "r&b", "r and b": "r&b",
    "drum and bass": "drum bass", "drum & bass": "drum bass",
    "drum n bass": "drum bass", "drum bass": "drum bass",
    "rock 'n' roll": "rock roll", "rock n roll": "rock roll",
    "rock and roll": "rock roll", "rock & roll": "rock roll",
    "rock roll": "rock roll",
    "italo-disco": "italo disco", "italo disco": "italo disco",
}


def grouping_key(label: str) -> str:
    """Accent-insensitive, lowercase, whitespace-collapsed genre key."""
    decomposed = unicodedata.normalize("NFD", label.strip())
    plain = "".join(char for char in decomposed if unicodedata.category(char) != "Mn")
    compact = re.sub(r"\s+", " ", plain.lower())
    return GROUPING_ALIASES.get(compact, compact)


def camel_words(suffix: str) -> list[str]:
    """Split CamelCase/siglas: SynthPop|UKGarage|EDM|LiquidDrumBass|JPop."""
    words: list[str] = []
    index = 0
    while index < len(suffix):
        if suffix[index].isupper():
            run = index + 1
            while run < len(suffix) and suffix[run].isupper():
                run += 1
            if run < len(suffix) and suffix[run].islower():
                if run - index > 1:
                    words.append(suffix[index:run - 1])  # "UK" from "UKGarage"
                    index = run - 1
                else:
                    end = run
                    while end < len(suffix) and (suffix[end].islower() or suffix[end].isdigit()):
                        end += 1
                    words.append(suffix[index:end])
                    index = end
            else:
                words.append(suffix[index:run])  # "EDM", "NRG", "B"
                index = run
        else:
            end = index
            while end < len(suffix) and (suffix[end].islower() or suffix[end].isdigit()):
                end += 1
            words.append(suffix[index:end])
            index = end
    return words


def genre_key(filename: str) -> str | None:
    """Derive the canonical genre key from the approved filename suffix alone."""
    match = FILENAME_RE.fullmatch(filename)
    if not match:
        return None
    suffix = match.group(2)
    alias = SUFFIX_ALIASES.get(suffix)
    if alias is not None:
        return grouping_key(alias)
    words = camel_words(suffix)
    if not words or any(not word for word in words):
        return None
    return grouping_key(" ".join(words))


def index_key(key: str) -> str:
    """Tolerant lookup form: case/punctuation cannot merge two different genres."""
    return re.sub(r"[^a-z0-9]+", " ", key.lower()).strip()


def geometry_valid(width: int, height: int) -> bool:
    if not (320 <= width <= 4096 and 240 <= height <= 4096):
        return False
    return width * height <= MAX_PIXELS and 1.15 <= width / height <= 2.40


def rows_from_text(manifest: str) -> list[list[str]]:
    if len(manifest) > MAX_MANIFEST_CHARS:
        raise ValueError("oversized mosaic manifest")
    rows = list(csv.reader(manifest.splitlines(), delimiter="\t"))
    if not rows or rows[0] != [HEADER]:
        raise ValueError("bad mosaic manifest header")
    return [row for row in rows[1:]
            if row and not (len(row) == 1 and (not row[0] or row[0].startswith("#")))]


def build_rows(folder: Path = ASSETS) -> list[list[str]]:
    """Deterministic inventory: ascending NNN, unchanged bytes, derived key."""
    rows: list[list[str]] = []
    for path in sorted(folder.glob("*.webp")):
        key = genre_key(path.name)
        if key is None:
            raise ValueError(f"unresolved genre suffix: {path.name}")
        payload = path.read_bytes()
        width, height = gate.webp_size(payload)
        rows.append([
            key, path.name, hashlib.sha256(payload).hexdigest(),
            str(width), str(height), APPROVAL,
        ])
    rows.sort(key=lambda row: row[1])
    return rows


def write_manifest(folder: Path = ASSETS) -> None:
    rows = build_rows(folder)
    (folder / "manifest.tsv").write_text(
        HEADER + "\n" + "".join("\t".join(row) + "\n" for row in rows),
        encoding="utf-8", newline="\n")


def validate(folder: Path = ASSETS) -> list[str]:
    errors: list[str] = []
    path = folder / "manifest.tsv"
    if folder.is_symlink() or not folder.is_dir() or path.is_symlink() or not path.is_file():
        return ["mosaic directory/manifest must be real regular paths"]
    try:
        records = rows_from_text(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError, OSError, csv.Error) as error:
        return [f"mosaic manifest unreadable: {error}"]
    installed = {p.name for p in folder.glob("*.webp")}
    used_files: set[str] = set()
    used_keys: set[str] = set()
    used_indexes: set[str] = set()
    numbers: list[int] = []
    for number, cells in enumerate(records, 2):
        if len(cells) != FIELDS:
            errors.append(f"row {number}: expected {FIELDS} fields")
            continue
        key, filename, sha, width, height, approval = cells
        match = FILENAME_RE.fullmatch(filename)
        if match is None:
            errors.append(f"row {number}: filename is not UI7_MOSAICO_NNN_<Genre>.webp")
            continue
        numbers.append(int(match.group(1)))
        if filename in used_files:
            errors.append(f"row {number}: duplicate asset {filename}")
        used_files.add(filename)
        derived = genre_key(filename)
        if derived is None:
            errors.append(f"row {number}: unresolved genre suffix in {filename}")
        elif key != derived:
            errors.append(f"row {number}: genre_key '{key}' does not match filename ('{derived}')")
        if key in used_keys:
            errors.append(f"row {number}: duplicate genre_key '{key}'")
        used_keys.add(key)
        if index_key(key) in used_indexes:
            errors.append(f"row {number}: ambiguous genre_key '{key}'")
        used_indexes.add(index_key(key))
        if approval != APPROVAL:
            errors.append(f"row {number}: not user-confirmed complete")
        if not re.fullmatch(r"[a-f0-9]{64}", sha):
            errors.append(f"row {number}: malformed SHA-256")
        target = folder / filename
        if target.is_symlink() or not target.is_file():
            errors.append(f"row {number}: missing/unsafe WebP {filename}")
            continue
        payload = target.read_bytes()
        if len(payload) > MAX_WEBP:
            errors.append(f"row {number}: {filename} exceeds {MAX_WEBP} B limit")
        if hashlib.sha256(payload).hexdigest() != sha:
            errors.append(f"row {number}: SHA-256 mismatch for {filename}")
        try:
            real = gate.webp_size(payload)
        except (ValueError, IndexError) as error:
            errors.append(f"row {number}: invalid WebP {filename}: {error}")
            continue
        if (str(real[0]), str(real[1])) != (width, height):
            errors.append(f"row {number}: declared size {width}x{height} != real {real[0]}x{real[1]}")
        elif not geometry_valid(*real):
            errors.append(f"row {number}: invalid artwork geometry {real[0]}x{real[1]}")
    loose = installed - used_files
    if loose:
        errors.append("undocumented mosaic WebP: " + ", ".join(sorted(loose)))
    missing = used_files - installed
    if missing:
        errors.append("declared but not packaged: " + ", ".join(sorted(missing)))
    if numbers != list(range(1, len(numbers) + 1)):
        errors.append("mosaic numbering must be unique, ascending and gap-free")
    if len(numbers) != EXPECTED_ASSETS:
        errors.append(f"expected exactly {EXPECTED_ASSETS} approved mosaics, found {len(numbers)}")
    return errors


if __name__ == "__main__":
    if "--write" in sys.argv[1:]:
        write_manifest()
        print(f"UI7 Mosaico manifest rewritten ({EXPECTED_ASSETS} entries)")
    problems = validate()
    if problems:
        print("UI7 Mosaico FAIL\n" + "\n".join(problems), file=sys.stderr)
        sys.exit(1)
    print(f"UI7 Mosaico PASS (approved assets: {len(build_rows())})")
