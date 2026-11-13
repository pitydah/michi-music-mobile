#!/usr/bin/env python3
"""Package the 176 existing Editorial WebPs without touching a single image pixel.

Consumes the original recuts ZIP, verifies its manifest.csv and WebP content,
then creates a ready-to-extract Android assets ZIP. The default action never
writes to the Android repository. Run as an opt-in CLI with --output.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
from pathlib import Path
import re
import zipfile

import validate_ui7_editorial_assets as old_gate
import validate_ui7_editorial_posters as poster_gate

PREFIX = "app/src/main/assets/ui7/editorial/posters/"


def package(source_zip: Path, output_zip: Path):
    if output_zip.exists():
        raise FileExistsError("Refuse to overwrite existing approved output")
    if source_zip.is_symlink() or not source_zip.is_file():
        raise ValueError("source archive missing or symlinked")
    rows = []
    blobs = {}
    with zipfile.ZipFile(source_zip) as src:
        names = set(src.namelist())
        webps = {x for x in names if x.endswith(".webp")}
        if len(webps) != 176 or any('/' in x or '\\' in x for x in webps):
            raise ValueError("source ZIP must contain exactly 176 flat WebP files")
        if "manifest.csv" not in names:
            raise ValueError("source ZIP must contain extraction manifest.csv")
        source_rows = list(csv.DictReader(io.StringIO(src.read("manifest.csv").decode("utf-8"))))
        known = old_gate.source_keys()
        if len(source_rows) != 176:
            raise ValueError("extractor manifest expected 176 rows")
        used = set()
        for row in source_rows:
            filename = row["filename"]
            slug = filename.removeprefix("genre_").removesuffix("_editorial.webp")
            sheet = row["original_source"]
            if filename != f"genre_{slug}_editorial.webp" or slug not in known or filename not in webps or filename in used:
                raise ValueError(f"unsafe/duplicate source name: {filename}")
            if not poster_gate.SOURCE_RE.fullmatch(sheet):
                raise ValueError(f"invalid original sheet: {sheet}")
            used.add(filename)
            data = src.read(filename)
            w, h = old_gate.webp_size(data)
            if (w, h) != (int(row["width_px"]), int(row["height_px"])) or len(data) != int(row["file_bytes"]):
                raise ValueError(f"source WebP was changed: {filename}")
            if not 1.4 <= w / h <= 4.5 or len(data) > poster_gate.MAX_WEBP:
                raise ValueError(f"source incompatible with poster contract: {filename}")
            blobs[filename] = data
            rows.append((slug, filename, hashlib.sha256(data).hexdigest(), str(w), str(h), sheet,
                         "USER_CONFIRMED_COMPLETE", "POSTER_COMPLETE"))
        if used != webps:
            raise ValueError("source ZIP/manifest differ")
    manifest = poster_gate.HEADER + "\n" + "\n".join("\t".join(row) for row in sorted(rows)) + "\n"
    output_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_zip, "x", compression=zipfile.ZIP_STORED) as target:
        target.writestr(PREFIX + "manifest.tsv", manifest)
        for name, data in sorted(blobs.items()):
            target.writestr(PREFIX + name, data)
    return len(rows), sum(map(len, blobs.values()))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-zip", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        count, payload_bytes = package(args.source_zip, args.output)
    except (ValueError, OSError, zipfile.BadZipFile, KeyError) as exc:
        parser.error(str(exc))
    print(f"PACKAGED {count} original unmodified Editorial posters; {payload_bytes} bytes; no repo writes")
