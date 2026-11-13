#!/usr/bin/env python3
"""Fail-closed, offline UI7 Editorial publication gate (Python stdlib only).

Runs for every :app:preBuild. Visual approval is a separate HUMAN gate.
"""
from __future__ import annotations

import csv
import hashlib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/ui7/editorial/production"
MANIFEST = ASSETS / "manifest.tsv"
POLICY = ROOT / "app/src/main/java/org/michimusic/mobile/ui/screens/GenreEditorialArtworkPolicy.kt"
MANIFEST_HEADER = "# UI7_EDITORIAL_PRODUCTION_MANIFEST_V1"
VARIANTS = {"cover", "portrait_left", "portrait_right", "single"}
FIELDS = 11
MAX_IMAGE_BYTES = 1_572_864
MAX_PIXELS = 3_686_400
MAX_MANIFEST_CHARS = 250_000


def geometry_valid(variant: str, w: int, h: int) -> bool:
    if not (320 <= w <= 4096 and 240 <= h <= 4096 and w * h <= MAX_PIXELS):
        return False
    ratio = w / h
    return {"cover": 1.40 <= ratio <= 2.20,
            "portrait_left": 0.58 <= ratio <= 0.85,
            "portrait_right": 0.58 <= ratio <= 0.85,
            "single": 1.25 <= ratio <= 2.10}.get(variant, False)


def webp_size(payload: bytes) -> tuple[int, int]:
    """Validate RIFF/chunk boundaries and reject animated containers.

    Does not replace Android's decoder or visual inspection of a feline subject.
    """
    if len(payload) < 30 or payload[:4] != b"RIFF" or payload[8:12] != b"WEBP":
        raise ValueError("invalid WebP signature")
    if int.from_bytes(payload[4:8], "little") != len(payload) - 8:
        raise ValueError("RIFF length mismatch")
    chunks: list[tuple[bytes, bytes]] = []
    pos = 12
    while pos < len(payload):
        if pos + 8 > len(payload):
            raise ValueError("truncated chunk header")
        tag = payload[pos:pos + 4]
        n = int.from_bytes(payload[pos + 4:pos + 8], "little")
        end = pos + 8 + n
        if end > len(payload):
            raise ValueError("truncated WebP chunk")
        chunks.append((tag, payload[pos + 8:end]))
        pos = end + (n & 1)
    if pos != len(payload) or not chunks:
        raise ValueError("bad WebP chunk padding")
    tag, data = chunks[0]
    if tag == b"VP8X":
        if len(data) != 10 or data[0] & 0x02:
            raise ValueError("invalid or animated VP8X")
        if not any(kind in (b"VP8 ", b"VP8L") for kind, _ in chunks[1:]):
            raise ValueError("VP8X has no image payload")
        w = 1 + int.from_bytes(data[4:7], "little")
        h = 1 + int.from_bytes(data[7:10], "little")
        return w, h
    if tag == b"VP8L" and len(data) >= 5 and data[0] == 47:
        a, b, c, d = data[1:5]
        return 1 + (a | (b & 0x3F) << 8), 1 + ((b >> 6) | (c << 2) | ((d & 0x0F) << 10))
    if tag == b"VP8 " and len(data) >= 10 and data[3:6] == b"\x9d\x01\x2a":
        return (int.from_bytes(data[6:8], "little") & 0x3FFF,
                int.from_bytes(data[8:10], "little") & 0x3FFF)
    raise ValueError("unsupported or malformed WebP image")


def source_keys() -> set[str]:
    data = POLICY.read_text(encoding="utf-8")
    match = re.search(r'editorialSourceKeys: Set<String> = setOf\((.*?)\n\)', data, re.S)
    if not match:
        raise ValueError("source key policy not found")
    keys = re.findall(r'"([a-z0-9_]+)"', match.group(1))
    if len(keys) != 176 or len(set(keys)) != 176:
        raise ValueError("Editorial source inventory changed without review (expected 176 unique)")
    return set(keys)


def validate() -> list[str]:
    errors: list[str] = []
    if not MANIFEST.is_file() or MANIFEST.is_symlink():
        return ["manifest.tsv missing or symlink: refuse to publish"]
    if MANIFEST.stat().st_size > MAX_MANIFEST_CHARS:
        return ["manifest.tsv over 250 kB limit"]
    try:
        known = source_keys()
        with MANIFEST.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.reader(handle, delimiter="\t"))
    except (OSError, ValueError, UnicodeError, csv.Error) as exc:
        return [f"manifest/keys unreadable: {exc}"]
    if not rows or rows[0] != [MANIFEST_HEADER]:
        return ["manifest header/version invalid"]
    used: set[str] = set()
    for number, cells in enumerate(rows[1:], 2):
        if not cells or (len(cells) == 1 and (not cells[0] or cells[0].startswith("#"))):
            continue
        if len(cells) != FIELDS:
            errors.append(f"line {number}: expected {FIELDS} TAB fields")
            continue
        slug, variant, filename, sha, w, h, source, approval, text_free, frame_free, safe = cells
        if slug not in known or variant not in VARIANTS or filename != f"genre_{slug}_{variant}.webp":
            errors.append(f"line {number}: unknown slug, variant or filename")
            continue
        if filename in used:
            errors.append(f"line {number}: duplicate asset {filename}")
        used.add(filename)
        if not re.fullmatch(r"[a-f0-9]{64}", sha) or not re.fullmatch(r"[A-Za-z0-9_-]{10,}", source):
            errors.append(f"line {number}: malformed hash/provenance")
        if (approval, text_free, frame_free, safe) != ("APPROVED", "YES", "YES", "YES"):
            errors.append(f"line {number}: not manually approved / artwork or geometry unsafe")
        path = ASSETS / filename
        if not path.is_file() or path.is_symlink():
            errors.append(f"line {number}: {filename} not packaged regular file")
            continue
        payload = path.read_bytes()
        if len(payload) > MAX_IMAGE_BYTES:
            errors.append(f"line {number}: {filename} exceeds {MAX_IMAGE_BYTES} B limit")
        if hashlib.sha256(payload).hexdigest() != sha:
            errors.append(f"line {number}: {filename} SHA mismatch")
        try:
            real = webp_size(payload)
            expected = int(w), int(h)
            if real != expected or not geometry_valid(variant, *real):
                errors.append(f"line {number}: {filename} invalid geometry real={real} declared={expected}")
        except (ValueError, IndexError) as exc:
            errors.append(f"line {number}: {filename} WebP structure/dimensions: {exc}")
    loose = {p.name for p in ASSETS.iterdir() if p.suffix.lower() == ".webp"} - used
    if loose:
        errors.append("undeclared production artwork: " + ", ".join(sorted(loose)))
    return errors


if __name__ == "__main__":
    failures = validate()
    if failures:
        print("UI7 Editorial manifest FAIL:\n" + "\n".join(failures), file=sys.stderr)
        sys.exit(1)
    print("UI7 Editorial manifest PASS (approved assets: %d)" % sum(
        1 for item in ASSETS.glob("*.webp") if item.is_file()))
