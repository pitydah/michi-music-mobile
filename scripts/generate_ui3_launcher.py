#!/usr/bin/env python3
"""Generate UI3.1 adaptive, monochrome, and legacy launcher PNG resources."""

from __future__ import annotations

import io
import subprocess
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SYMBOL = ROOT / "design/ui3_1/michi_launcher_symbol.svg"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def render_symbol(size: int) -> Image.Image:
    # The canonical whiskers span ~76% of its viewBox. Target 64dp so the
    # antialiased edge remains strictly inside Android's central 66dp safe zone.
    rendered_size = round(size * 64 / 76)
    result = subprocess.run(
        ["rsvg-convert", "-w", str(rendered_size), "-h", str(rendered_size), str(SYMBOL)],
        check=True,
        capture_output=True,
    )
    rendered = Image.open(io.BytesIO(result.stdout)).convert("RGBA")
    canvas = Image.new("RGBA", (size, size))
    inset = (size - rendered_size) // 2
    canvas.alpha_composite(rendered, (inset, inset))
    return canvas


def gradient(size: int) -> Image.Image:
    image = Image.new("RGBA", (size, size))
    pixels = image.load()
    start, end = (255, 105, 43), (198, 9, 67)
    for y in range(size):
        for x in range(size):
            t = (x + y) / max(1, 2 * (size - 1))
            pixels[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(start, end)) + (255,)
    return image


def monochrome(symbol: Image.Image) -> Image.Image:
    source_alpha = symbol.getchannel("A")
    luminance = symbol.convert("RGB").convert("L")
    # A single opaque silhouette would merge the face, phone and note. Android
    # accepts alpha in monochrome layers, so retain those nested shapes as
    # one-color tonal detail while keeping fully transparent pixels empty.
    alpha = luminance.point(lambda value: 64 + round(value * 191 / 255))
    alpha = ImageChops.multiply(alpha, source_alpha)
    white = Image.new("RGBA", symbol.size, "white")
    white.putalpha(alpha)
    return white


def legacy_icon(size: int, round_icon: bool) -> Image.Image:
    background = gradient(size)
    symbol = render_symbol(size)
    image = Image.alpha_composite(background, symbol)
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    if round_icon:
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        radius = max(2, round(size * 0.18))
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    image.putalpha(ImageChops.multiply(image.getchannel("A"), mask))
    return image


def main() -> None:
    if not SYMBOL.exists():
        raise SystemExit(f"Missing vector master: {SYMBOL}")
    for density, scale in DENSITIES.items():
        resource_dir = ROOT / f"app/src/main/res/mipmap-{density}"
        resource_dir.mkdir(parents=True, exist_ok=True)
        adaptive_size = round(108 * scale)
        legacy_size = round(48 * scale)
        symbol = render_symbol(adaptive_size)
        gradient(adaptive_size).save(resource_dir / "ui3_launcher_background.png", optimize=True)
        symbol.save(resource_dir / "ui3_launcher_foreground.png", optimize=True)
        monochrome(symbol).save(resource_dir / "ui3_launcher_monochrome.png", optimize=True)
        legacy_icon(legacy_size, round_icon=False).save(resource_dir / "ic_launcher.png", optimize=True)
        legacy_icon(legacy_size, round_icon=True).save(resource_dir / "ic_launcher_round.png", optimize=True)
        symbol.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS).save(
            resource_dir / "ic_launcher_foreground.png", optimize=True,
        )


if __name__ == "__main__":
    main()
