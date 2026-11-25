# Room preimage and CAS

Implemented:
- `MetadataIndexPreimage` with explicit `NoRow` vs `Existing`, all scalar fields, versioned binary sidecar, SHA envelope, atomic move, fsync, no symlink.
- `MetadataSaveService` captures the preimage before `MUTATING` and refuses with `PREIMAGE_NOT_DURABLE` / `PREIMAGE_UNAVAILABLE`.
- `MetadataRecoveryCoordinator` refuses `PREIMAGE_MISSING_OR_SUSPICIOUS` instead of reading the live row as the preimage.
- `MetadataRecoveryJournalV1.completeVerified` deletes preimage with intent/stage/backup/owner and resumes from `CLEANUP_SAFE`.
- Tests: `MetadataIndexPreimageStoreTest` (round-trip, tamper, missing, foreign identity); `MissingPreimageFailsClosed...`; cleanup failure inside `completeVerified`.

Not implemented:
- No Room `metadata_applied_transaction` marker inserted in the same transaction as the scalar CAS.
- No monotonic metadata revision, so ABA protection is predicate-based only.
- No forced Room-down-after-write test.
- Preimage sidecar is not yet bound by a Room grant; it is private evidence, not a global write authority.
