# Remaining P0/P1 risks

> **Historical document (superseded).** This risk inventory predates the ME-OMEGA closure and the MF01–MF05 integration. It is retained for traceability. Current status is authoritative in `megasprint-ledger.md` and `a01-a30.csv`; MF01–MF05 are integrated at `4c3b204c` with independent REVIEW_GO; combined KILLCRITIC found two P1s, fixed in `4c3b204c`, and the combined re-review REVIEW_GO at 926b9563. Do not treat the findings below as current P0/P1 status.

- **F3:** no Room transaction marker; CAS and applied proof are not one transaction. Kill after UPDATE remains unproven.
- **F5:** real two-phase process kill did not reach its checkpoint due test-package MediaStore write open failure. T18/T19/T20/T22/T43 NOT RUN.
- **T10/N01:** no independent-UID fixture app; real system consent is NOT RUN.
- **F4:** global lease covers fresh save/continue/resumeExact; restore and discard are not all wrapped yet. No three-caller or concurrent restore test.
- **F2:** title-only T48; artist/album/genre regrouping N07 and Géneros entry N08 are NOT RUN. Library refresh is still a global signal, not selective identity projection.
- **E1–E5:** no advanced field/asset profile, no multivalue, no APIC/lyrics, no FLAC, no SAF, no MP4/M4A. These are 0% implemented.
- No personal audio was used; no global gate was enabled.
