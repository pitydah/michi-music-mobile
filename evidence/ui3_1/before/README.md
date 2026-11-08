# UI3.1 before-state evidence

The files in this directory freeze the user-supplied UI3 recordings and the
launcher state installed from the verified baseline
`qa/ui3-r19@47577c0b33efbadd90843d9880e173982b4db3fc`.

| Artifact | Source | Capture |
| --- | --- | --- |
| `ui3-library-15s.png` | `/home/cristian/Escritorio/ui3.webm` | `ffmpeg -ss 15 -i … -frames:v 1` |
| `ui3-detail-37s.png` | `/home/cristian/Escritorio/ui3.webm` | `ffmpeg -ss 37 -i … -frames:v 1` |
| `ui3-2-detail-08s.png` | `/home/cristian/Escritorio/ui3-2.webm` | `ffmpeg -ss 8 -i … -frames:v 1` |
| `ui3-2-metadata-24s.png` | `/home/cristian/Escritorio/ui3-2.webm` | `ffmpeg -ss 24 -i … -frames:v 1` |
| `baseline-pixel-launcher.png` | `emulator-5554`, API 36 Pixel Launcher | `adb exec-out screencap -p` after Home |

Video frames retain their original 1080×2400 dimensions. The launcher capture
is environmental evidence, not OEM/physical-device certification.
