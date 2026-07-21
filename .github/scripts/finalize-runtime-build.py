#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def patch(rel: str, transform):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    path.write_text(transform(text), encoding="utf-8")


def add_import(text: str, imp: str) -> str:
    line = f"import {imp}"
    if line in text:
        return text
    lines = text.splitlines()
    package_index = next(i for i, value in enumerate(lines) if value.startswith("package "))
    lines.insert(package_index + 1, line)
    return "\n".join(lines) + ("\n" if text.endswith("\n") else "")


def fix_sync_screen(text: str) -> str:
    text = text.replace("import androidx.compose.foundation.layout.weight\n", "")
    text = add_import(text, "androidx.compose.foundation.layout.width")
    text = text.replace("Spacer(Modifier.weight(1f))", "Spacer(Modifier.width(8.dp))")
    return text


def fix_now_playing(text: str) -> str:
    text = add_import(text, "androidx.compose.ui.geometry.CornerRadius")
    text = text.replace(
        "drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(0f, barTop), Size(size.width, barHeightPx), 3f, 3f)",
        "drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(0f, barTop), Size(size.width, barHeightPx), CornerRadius(3f, 3f))",
    )
    text = text.replace(
        "drawRoundRect(accent, Offset(0f, barTop), Size(activeW, barHeightPx), 3f, 3f)",
        "drawRoundRect(accent, Offset(0f, barTop), Size(activeW, barHeightPx), CornerRadius(3f, 3f))",
    )
    return text


def fix_sync_components(text: str) -> str:
    return text.replace("peer?.name", "peer?.alias")


patch("app/src/main/java/org/michimusic/mobile/ui/screens/SyncScreen.kt", fix_sync_screen)
patch("app/src/main/java/org/michimusic/mobile/ui/screens/nowplaying/NowPlayingComponents.kt", fix_now_playing)
patch("app/src/main/java/org/michimusic/mobile/ui/screens/sync/SyncComponents.kt", fix_sync_components)

print("Final temporary compatibility patches applied successfully.")
