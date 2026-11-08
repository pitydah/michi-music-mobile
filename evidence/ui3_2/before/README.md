# UI3.2 before evidence

Source: `/home/cristian/Escritorio/ui3-3.webm`

The source recording is VP9, 1080×2400, 24 fps and 10.535 seconds long. Frames
were extracted without scaling with `ffmpeg -ss <timestamp> -i <source>
-frames:v 1 <output>`.

| File | Timestamp | Observed contract defect |
|---|---:|---|
| `detail-heading-and-sort.png` | 0.5 s | `Pistas (1)` and the A-Z action are visible. |
| `album-menu-opening.png` | 4.5 s | Album options bottom sheet starts opening. |
| `bottom-sheet-ghost.png` | 7.0 s | Displaced oversized rounded outline is visible around the modal region. |
| `bottom-sheet-dismiss.png` | 9.5 s | Dismissal frame for before/during/after comparison. |

These frames establish the visual baseline only. Root-border causality must be
confirmed by reproducing the same flow after the bounded `MichiBottomSheet`
change.
