# Block D — races, playback and duplicate mutations

- `MetadataSaveCheckpoint`/`MetadataSaveCheckpointHook` are internal test-only seams; production uses `NoMetadataSaveCheckpoint`.
- Checkpoints: `AFTER_BACKUP_DURABLE`, `AFTER_STAGE_READY`, `BEFORE_MUTATING`, `AFTER_MUTATING_DURABLE`, `AFTER_PROVIDER_CLOSE`, `BEFORE_INDEX_CAS`, `AFTER_INDEX_CAS`, `BEFORE_TERMINAL_CLEANUP`.
- `MetadataSaveLocks` is process-wide, keyed by exact `sourceId|trackId|uri`; the second service returns `RecoveryRequired("IN_PROGRESS")` before any backup or provider open.
- T12: source changed at `BEFORE_MUTATING`; product rechecks SHA/size, aborts and discards only private preparation. PASS.
- T13: playback false at Save and after the durable MUTATING fence; journal never reaches provider transport. PASS.
- T15: two concurrent service instances; exactly one save and one CAS; second returns IN_PROGRESS. PASS.
- No production flag or global gate was added.
