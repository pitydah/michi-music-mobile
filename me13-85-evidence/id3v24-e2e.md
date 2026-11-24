# ID3v2.4 evidence

- `Mp3FixtureTags.build(4, ...)` writes an ID3v2.4 header, synchsafe tag/frame sizes and UTF-8 text frames with encoding byte 3.
- T02 saves TITLE `ME13 Ñandú Nuevo` and reopens it without mojibake.
- T05 saves GENRE `Rock Alternativo` and reopens the exact value; no normalization is invented.
- T06 saves four v2.4 fields in one Review/Save/CAS.
- Opaque `TXXX` and `PRIV` frames survive a TITLE rewrite byte-for-byte in `v24TxxxAndPrivFramesSurviveTheRewrite`.
- The same production MediaStore path, journal, postcheck, permit and Room CAS are used; no unit-only rewriter is counted as E2E.
- Source modified: disposable MediaStore MP3 only.
