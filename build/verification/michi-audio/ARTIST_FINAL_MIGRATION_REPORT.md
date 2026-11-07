# Artist Enrichment final migration report

## Schema

Room database `michi-artist-enrichment.db` advances from v8 to v9. The main library/playback
database is unchanged. New v9 tables are `durable_artist_association`,
`artist_album_credit_binding`, and `artist_membership_evidence`; exact resolutions gain
nullable `contextAlbumKeysJson`. The exported schema is
`data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/9.json`.

## Non-destructive migration

`MIGRATION_8_9`:

1. adds the exact-resolution context column;
2. creates stable associations, authoritative bindings, evidence, indexes, and composite FKs;
3. copies each v8 identity row into an association using its existing key;
4. preserves all identity/profile, biography, artwork asset/link/selection/focus/revision,
   provenance/licence, and exact-resolution rows;
5. creates confirmed bindings only for reproducible explicit legacy contexts;
6. records every other legacy album observation as `INSUFFICIENT` evidence.

No destructive fallback, name inference, identity replacement, or historical-row deletion is
used.

## Executable evidence

`ArtistEnrichmentMigrationTest` has eight passing tests. The v8→v9 case constructs a real v8
database containing two identities, an exact decision, and artwork records. After migration
it verifies both identities/associations survive, only the trustworthy context becomes a
binding, uncertain data is insufficient, exact resolution/artwork survives,
`PRAGMA integrity_check` returns `ok`, and `foreign_key_check` returns zero rows.

Full data debug/release suites each passed 172 tests in Gradle workflow run 0026.

## Install preservation

On the isolated API36 emulator, the Normal debug APK was installed, a private sentinel was
written under the package data directory, and the identical APK was reinstalled with
`adb install -r`. The sentinel remained byte-identical. This proves Android package-data
preservation for `install -r`; it is not claimed as a substitute for the executable v8→v9
Room migration test.
