#!/usr/bin/env python3
"""Render reproducible before/after adaptive-icon mask and themed previews."""

from __future__ import annotations

import io
import math
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BASE = "47577c0b33efbadd90843d9880e173982b4db3fc"
RESOURCE = "app/src/main/res/mipmap-xxxhdpi"
OUTPUT = ROOT / "evidence/ui3_1/icon"
SIZE = 432


def committed(name: str) -> Image.Image:
    data = subprocess.run(
        ["git", "show", f"{BASE}:{RESOURCE}/{name}"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    ).stdout
    return Image.open(io.BytesIO(data)).convert("RGBA")


def current(name: str) -> Image.Image:
    return Image.open(ROOT / RESOURCE / name).convert("RGBA")


def mask(shape: str) -> Image.Image:
    result = Image.new("L", (SIZE, SIZE), 0)
    draw = ImageDraw.Draw(result)
    inset = 10
    bounds = (inset, inset, SIZE - inset - 1, SIZE - inset - 1)
    if shape == "circle":
        draw.ellipse(bounds, fill=255)
    elif shape == "rounded-square":
        draw.rounded_rectangle(bounds, radius=round(SIZE * 0.22), fill=255)
    elif shape == "squircle":
        radius = (SIZE - 2 * inset) / 2
        center = SIZE / 2
        points = []
        for degree in range(360):
            angle = math.radians(degree)
            cosine, sine = math.cos(angle), math.sin(angle)
            x = math.copysign(abs(cosine) ** 0.25, cosine) * radius + center
            y = math.copysign(abs(sine) ** 0.25, sine) * radius + center
            points.append((x, y))
        draw.polygon(points, fill=255)
    else:
        raise ValueError(shape)
    return result


def adaptive(background: Image.Image, foreground: Image.Image, shape: str) -> Image.Image:
    image = Image.alpha_composite(background, foreground)
    image.putalpha(mask(shape))
    tile = Image.new("RGBA", image.size, (232, 232, 236, 255))
    tile.alpha_composite(image)
    return tile.convert("RGB")


def themed(monochrome: Image.Image) -> Image.Image:
    alpha = monochrome.getchannel("A")
    glyph = Image.new("RGBA", monochrome.size, (255, 183, 214, 255))
    glyph.putalpha(alpha)
    tile = Image.new("RGBA", monochrome.size, (44, 18, 35, 255))
    tile.alpha_composite(glyph)
    tile.putalpha(mask("circle"))
    background = Image.new("RGBA", monochrome.size, (232, 232, 236, 255))
    background.alpha_composite(tile)
    return background.convert("RGB")


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    before_background = committed("ui3_launcher_background.png")
    before_foreground = committed("ui3_launcher_foreground.png")
    after_background = current("ui3_launcher_background.png")
    after_foreground = current("ui3_launcher_foreground.png")
    shapes = ("circle", "squircle", "rounded-square")
    sheet = Image.new("RGB", (SIZE * len(shapes), SIZE * 2), (232, 232, 236))
    for column, shape in enumerate(shapes):
        before = adaptive(before_background, before_foreground, shape)
        after = adaptive(after_background, after_foreground, shape)
        before.save(OUTPUT / f"before-{shape}.png", optimize=True)
        after.save(OUTPUT / f"after-{shape}.png", optimize=True)
        sheet.paste(before, (column * SIZE, 0))
        sheet.paste(after, (column * SIZE, SIZE))
    sheet.save(OUTPUT / "adaptive-mask-comparison.png", optimize=True)
    themed(current("ui3_launcher_monochrome.png")).save(
        OUTPUT / "after-themed-monochrome.png", optimize=True,
    )
    print(f"Wrote launcher evidence to {OUTPUT}")


if __name__ == "__main__":
    main()
