#!/usr/bin/env python3
"""Aero monochrome grain: 128x128 RGBA; deterministic, zero dependencies."""
from hashlib import sha256
from pathlib import Path
import struct
import zlib

SIZE = 128
MASK = 0xFFFFFFFF
state = 0x4D494348


def byte_state():
    global state
    state ^= (state << 13) & MASK
    state ^= state >> 17
    state ^= (state << 5) & MASK
    state &= MASK
    return state


def png_chunk(tag: bytes, payload: bytes) -> bytes:
    return (struct.pack(">I", len(payload)) + tag + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & MASK))


def main():
    raw = bytearray()
    for _y in range(SIZE):
        raw.append(0)  # PNG filter type 0, no scanline prediction
        for _x in range(SIZE):
            n = byte_state()
            shade = 255 if n & 1 else 0
            opacity = (n >> 8) % 7  # alpha 0..6/255: ultra subtle
            raw.extend((shade, shade, shade, opacity))

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    payload = (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", header)
               + png_chunk(b"IDAT", zlib.compress(bytes(raw), level=9))
               + png_chunk(b"IEND", b""))
    output = Path("app/src/main/res/drawable-nodpi/aero_graphite_grain.png")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(payload)
    print(f"{output}  {len(payload)} bytes  sha256={sha256(payload).hexdigest()}")


if __name__ == "__main__":
    main()
