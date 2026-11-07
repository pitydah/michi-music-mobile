# UI3.1 launcher source

`michi_launcher_symbol.svg` preserves the cat, note and whisker paths from the
canonical Michi ecosystem artwork at
`michi-music-player/src/michi/presentation/qml/assets/michi-logo.svg`. Its
full-canvas rounded background was removed. The phone surround was redrawn as
simple vector geometry against the verified UI3 foreground at
`qa/ui3-r19@47577c0b33efbadd90843d9880e173982b4db3fc`; this keeps the original
cat/phone/note/whisker identity without retaining the noisy baked square or cyan
edge.

Regenerate and verify resources with:

```bash
python3 scripts/generate_ui3_launcher.py
python3 scripts/verify_ui3_launcher.py
python3 scripts/render_ui3_launcher_evidence.py
```

The generator uses the 108dp adaptive canvas, keeps the symbol inside Android's
66dp safe zone, creates a matching tintable monochrome silhouette, and emits
distinct 48dp rounded-square and circular legacy icons for every density.
