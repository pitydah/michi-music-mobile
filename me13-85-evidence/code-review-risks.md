# Code review risks / fail-closed notes

- New writes still require the exact `MetadataWritePermit`; no public certified boolean was reintroduced.
- `MetadataIndexObligation` refuses a missing/invalid intent, foreign owner token, foreign track or non-actionable preimage; no V1 fallback for new transactions.
- Receipt enumeration only reads bounded names; suspicious entries are reported, never followed or deleted.
- Applied proof is durable before journal cleanup; a kill between CAS and proof leaves the reconcile receipt and a retry, not a second CAS.
- `MetadataSaveLocks` is process-wide; it does not survive process death, but the journal and permit do.
- T48’s Activity recreation is not a process kill; kill matrix claims remain limited.
- No personal song, personal DB, SAF, FLAC, MP4, artwork/lyrics or multivalue path was enabled.
- Diagnostic logs are code-only (`MichiSave publication=<code>`, `prepare_failure=<class>`) and contain no URI, tag or provider text.
