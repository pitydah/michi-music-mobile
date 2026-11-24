# Block B — four scalar fields on real MediaStore

Test class: `data/src/androidTest/java/org/michimusic/data/metadata/MetadataSaveMediaStoreFieldsInstrumentedTest.kt`.
Fixture builder: `Mp3FixtureTags.kt` using only `data/src/androidTest/assets/me13-silence.mp3`.

| Scenario | Test | Result |
|---|---|---|
| T02 ID3v2.4 TITLE + UTF-8 Ñ | `t02V24TitleAndUtf8ValueSurvive` | PASS |
| T03 ID3v2.3 ARTIST | `t03V23ArtistIsSaved` | PASS |
| T04 ID3v2.3 ALBUM | `t04V23AlbumIsSaved` | PASS |
| T05 ID3v2.4 GENRE | `t05V24GenreIsSavedWithoutInventedNormalization` | PASS |
| T06 four fields, one transaction | `t06FourScalarFieldsSaveInOneTransaction` | PASS |
| ID3v1 128-byte footer | `v23Id3v1FooterIsPreservedByteForByte` | PASS |
| ID3v2.4 TXXX/PRIV opaque | `v24TxxxAndPrivFramesSurviveTheRewrite` | PASS |
| stale preimage | `stalePreimageKeepsTheRowAndWritesActionableV2` | PASS |

Each test uses its own disposable MediaStore URI, a real `cached_tracks` row, the production `MetadataSaveService`, exact candidate SHA/bytes, one Room CAS, audio-suffix byte comparison, journal Clear and a cold reopen. Source modified: disposable MP3 only; the original audio payload is byte-identical.
