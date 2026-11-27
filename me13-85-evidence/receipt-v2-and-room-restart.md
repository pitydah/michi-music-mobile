# Block C — durable V2 obligations and crash-safe cleanup

- New `MetadataIndexAppliedReceipt.kt`: versioned tx/source/track/URI/candidate SHA/bytes, SHA envelope, atomic-only write, bounded read, no symlink follow.
- `MetadataIndexReconciliationReceipt.write` no longer falls back to a non-atomic move; `entries(root)` exposes `ValidCandidate` vs `Suspicious(RECEIPT_SYMLINK | NOT_REGULAR_FILE | INVALID_RECEIPT_SIZE)` without deleting anything.
- `MetadataIndexObligation` is the single V2-only writer for new certified transactions; `MetadataSaveService` no longer falls back to V1.
- `MetadataIndexReconciler` writes the applied proof before `completeVerified`, resolves an exact applied orphan only with the proof + exact row, and never repeats the CAS.
- `MetadataRecoveryJournalV1.completeVerified` resumes from `CLEANUP_SAFE` and deletes intent/stage/backup/owner idempotently, journal last.
- T36: `MetadataIndexReconcilerInstrumentedTest#t36FailedPublisherKeepsActionableReceiptAcrossRoomRestart` — publisher failure, actionable V2, file Room close/reopen, retry, one CAS, journal Clear. PASS.
- X08: `#x08MissingRowIsPublishedAfterTheExactOriginalRowAppears` — missing row waits, production API inserts the exact original row, next activation indexes. PASS.
- X12: `#x12ReceiptCleanupFailureNeverRepeatsTheCas` — cleanup failure leaves the applied proof and journal clear; next open never repeats the CAS. PASS.
- Unit: `MetadataIndexAppliedReceiptTest`, extended receipt/reconciler tests, `MetadataIndexObligationTest`, cleanup resume tests. All PASS.
- Room used: `me13-reconciler-<uuid>.db` on the emulator, closed/reopened; T48 uses the persistent application DB. No personal DB.
