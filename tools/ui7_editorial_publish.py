"""Offline, guarded staging of *already approved* UI7 Editorial art.

Default prints a candidate TSV row and changes NOTHING. --install requires explicit
human approval of text/frame/safe area in the cumulative plan; this tool cannot
establish artistic quality. Does not generate, recolor, crop, or edit images.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import tempfile

import validate_ui7_editorial_assets as gate


def checked_candidate(source: Path, slug: str, variant: str, drive_id: str) -> tuple[str, bytes, str]:
    if source.is_symlink() or not source.is_file():
        raise ValueError("source must be a regular, already approved WebP file")
    if slug not in gate.source_keys() or variant not in gate.VARIANTS:
        raise ValueError("genre key / variant is not in the Editorial contract")
    if not re.fullmatch(r"[A-Za-z0-9_-]{10,}", drive_id):
        raise ValueError("source Drive file ID invalid")
    payload = source.read_bytes()
    if not payload or len(payload) > gate.MAX_IMAGE_BYTES:
        raise ValueError("WebP file is empty or exceeds byte budget")
    width, height = gate.webp_size(payload)
    if not gate.geometry_valid(variant, width, height):
        raise ValueError("WebP geometry violates Editorial variant")
    name = f"genre_{slug}_{variant}.webp"
    row = "\t".join((slug, variant, name, hashlib.sha256(payload).hexdigest(),
                     str(width), str(height), drive_id, "APPROVED", "YES", "YES", "YES"))
    return name, payload, row


def _atomic_bytes(target: Path, payload: bytes):
    fd, temporary = tempfile.mkstemp(prefix=".ui7_", dir=target.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def publish(
    source: Path,
    slug: str,
    variant: str,
    drive_id: str,
    assets: Path = gate.ASSETS,
    install: bool = False,
    approval_confirmed: bool = False,
) -> str:
    name, payload, row = checked_candidate(source, slug, variant, drive_id)
    if not install:
        return row  # Dry run: NO filesystem writes.
    if not approval_confirmed:
        raise ValueError("explicit artistic approval is required; do not auto-approve source recuts")
    if assets.is_symlink() or not assets.is_dir():
        raise ValueError("production directory must be a real existing directory")
    manifest = assets / "manifest.tsv"
    if manifest.is_symlink() or not manifest.is_file():
        raise ValueError("production manifest missing or symlinked")
    before = manifest.read_bytes()
    if len(before) > gate.MAX_MANIFEST_CHARS:
        raise ValueError("oversized production manifest")
    text = before.decode("utf-8")
    if not text.startswith(gate.MANIFEST_HEADER + "\n"):
        raise ValueError("unknown manifest version")
    dest = assets / name
    if dest.exists() or dest.is_symlink():
        raise FileExistsError(f"approved artwork must NEVER be overwritten: {name}")
    # Check the pre-existing installation before adding one entry; prevent smuggling
    # an unapproved file during a one-file publication.
    old_assets, old_manifest = gate.ASSETS, gate.MANIFEST
    try:
        gate.ASSETS, gate.MANIFEST = assets, manifest
        errors = gate.validate()
        if errors:
            raise ValueError("existing Editorial production gate failed: " + "; ".join(errors))
        # Appended records are naturally idempotency-safe: duplicate target is refused.
        future = (text if text.endswith("\n") else text + "\n") + row + "\n"
        if len(future.encode("utf-8")) > gate.MAX_MANIFEST_CHARS:
            raise ValueError("production manifest would exceed maximum size")
        _atomic_bytes(dest, payload)
        try:
            _atomic_bytes(manifest, future.encode("utf-8"))
            errors = gate.validate()
            if errors:
                raise ValueError("post-install validation failed: " + "; ".join(errors))
        except BaseException:
            _atomic_bytes(manifest, before)
            dest.unlink(missing_ok=True)
            raise
        return row
    finally:
        gate.ASSETS, gate.MANIFEST = old_assets, old_manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-webp", type=Path, required=True)
    parser.add_argument("--genre-key", required=True)
    parser.add_argument("--variant", choices=sorted(gate.VARIANTS), required=True)
    parser.add_argument("--source-drive-id", required=True)
    parser.add_argument("--install", action="store_true", help="change production assets and manifest")
    parser.add_argument("--approval-confirmed", action="store_true", help="human approved artifact, no embedded text/frame, safe area QA passed")
    args = parser.parse_args()
    try:
        row = publish(args.source_webp, args.genre_key, args.variant,
                      args.source_drive_id, install=args.install,
                      approval_confirmed=args.approval_confirmed)
    except (ValueError, FileNotFoundError, FileExistsError, OSError) as error:
        parser.error(str(error))
    print(("INSTALLED " if args.install else "DRY RUN (no file changes): ") + row)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
