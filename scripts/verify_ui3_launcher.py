#!/usr/bin/env python3
"""Fail closed when generated UI3.1 launcher resources violate Android geometry."""

from __future__ import annotations

import shutil
import tempfile
from pathlib import Path

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def verify(root: Path) -> None:
    for density, scale in DENSITIES.items():
        directory = root / f"app/src/main/res/mipmap-{density}"
        adaptive_size = round(108 * scale)
        legacy_size = round(48 * scale)
        foreground = Image.open(directory / "ui3_launcher_foreground.png").convert("RGBA")
        background = Image.open(directory / "ui3_launcher_background.png").convert("RGBA")
        mono = Image.open(directory / "ui3_launcher_monochrome.png").convert("RGBA")
        if foreground.size != (adaptive_size, adaptive_size) or background.size != foreground.size:
            raise ValueError(f"{density}: adaptive dimensions are not 108dp")
        if background.getchannel("A").getextrema() != (255, 255):
            raise ValueError(f"{density}: background is not opaque and full bleed")
        bbox = foreground.getchannel("A").getbbox()
        if bbox is None:
            raise ValueError(f"{density}: empty foreground")
        safe_inset = round(21 * scale)
        tolerance = max(1, round(scale))
        if bbox[0] < safe_inset - tolerance or bbox[1] < safe_inset - tolerance or \
                bbox[2] > adaptive_size - safe_inset + tolerance or bbox[3] > adaptive_size - safe_inset + tolerance:
            raise ValueError(f"{density}: foreground exceeds the 66dp safe zone: {bbox}")
        mono_alpha = mono.getchannel("A")
        if mono_alpha.point(lambda value: 255 if value else 0).tobytes() != \
                foreground.getchannel("A").point(lambda value: 255 if value else 0).tobytes():
            raise ValueError(f"{density}: monochrome silhouette differs from foreground")
        if sum(1 for count in mono_alpha.histogram() if count) < 4:
            raise ValueError(f"{density}: monochrome layer loses nested phone/note detail")
        if mono.convert("RGB").getextrema() != ((255, 255), (255, 255), (255, 255)):
            raise ValueError(f"{density}: monochrome layer contains baked colors")
        for name in ("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png"):
            if Image.open(directory / name).size != (legacy_size, legacy_size):
                raise ValueError(f"{density}: {name} is not 48dp")
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            legacy = Image.open(directory / name).convert("RGBA")
            expected_background = Image.open(directory / "ui3_launcher_background.png").convert("RGBA")
            expected_background = expected_background.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
            inset = round(legacy_size * 0.2)
            center = (inset, inset, legacy_size - inset, legacy_size - inset)
            difference = ImageChops.difference(
                legacy.crop(center).convert("RGB"),
                expected_background.crop(center).convert("RGB"),
            )
            if difference.getbbox() is None:
                raise ValueError(f"{density}: {name} does not contain the launcher symbol")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp)
        shutil.copytree(ROOT / "app", fixture / "app")
        bad = fixture / "app/src/main/res/mipmap-mdpi/ui3_launcher_foreground.png"
        Image.new("RGBA", (108, 108), "cyan").save(bad)
        try:
            verify(fixture)
        except ValueError:
            return
        raise AssertionError("Verifier accepted a baked full-canvas foreground fixture")


if __name__ == "__main__":
    verify(ROOT)
    self_test()
    print("UI3.1 launcher resources verified")
