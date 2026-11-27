# Block A — coherent committed snapshots

- Added `data/src/main/java/org/michimusic/data/metadata/MetadataVerifiedCommittedSnapshotReader.kt`.
- Validates the journal entry against the persisted intent: transaction, source, track, URI, original/candidate SHA and bytes, owner digest.
- Reads the committed candidate twice with `MetadataCurrentBytesReader.readCommittedCandidate`, parses the live `content://` descriptor with `MetadataColdFieldParser`, applies `MetadataPostSourceFieldPolicy`, and rejects any second-read movement.
- Builds the snapshot from the **live committed report**, with evidence token `me13:sha256:<candidateSha>`; no `review.proposed.copy`.
- Refuses with bounded codes: `SNAPSHOT_WRONG_TRANSACTION`, `SOURCE_SHA_OR_SIZE_MISMATCH`, `SOURCE_COLD_READ_FAILED`, `SOURCE_TAG_PARSE_FAILED`, `SOURCE_FIELDS_DO_NOT_MATCH_INTENT`, `SOURCE_CHANGED_DURING_SNAPSHOT`, `SNAPSHOT_NOT_VERIFIABLE`, `KNOWN_OBSERVATION_CONFLICT`.
- `MetadataSaveService` uses the committed snapshot for `Saved(committed, true/false)`.
- `MetadataRecoveryCoordinator` uses the committed snapshot for `Indexed`/`IndexPending`; the backup snapshot is only the CAS preimage.
- Test: `MetadataVerifiedCommittedSnapshotReaderTest` (4 cases) passed; T01/T21/T48 assert `known[TITLE]` and `fieldObservations[TITLE]` agree with the candidate.
- Source modified: disposable MediaStore MP3 only. No personal file.
