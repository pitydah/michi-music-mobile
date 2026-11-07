# R11.5.2-R4 RED/GREEN matrix

Certified source candidate: `5dcaddedfd4774a04569025f5f07e3b88926a5dc`.

| Defect | RED evidence | Fix | GREEN evidence |
|---|---|---|---|
| K08 exact decision over-inherited temporary membership | Gradle workflow `2e53b6ef6ab8abead90a27dec76e549b`, run 0001: three focused persistence assertions failed | Preserve immutable canonical evidence; apply decisions only to an exact membership fingerprint; fail closed on competition | Run 0004: 17/17 focused tests passed |
| K09 clear removed unrelated decisions | Run 0005: `clearInvalidatesOnlyResolutionsThatDependOnTheDeletedGroup` failed | Delete only resolution rows whose chosen canonical group is the cleared target | Run 0006: 18/18; run 0011 proved shared real PNG bytes survive |
| Changed membership was incorrectly promoted to canonical ownership in instrumentation fixtures | Run 0018: two `ArtistDurableAssociationE2ETest` failures | Require recovered state to remain `needsConfirmation=true`; no production relaxation | Run 0019: 79 runner tests; XML 69 cases, 10 designed skips, 0 failures |
| Partially broadened legacy evidence could claim another subset | Run 0020: `broadenedLegacyEvidenceWithAStaleMembershipCannotClaimANewSubset` failed | Trust overlap evidence only when its album hash equals `albumGroupKey` | Run 0022: 19/19; run 0023: 37/37 integrated tests |
| Screen bind automatically searched MusicBrainz | Run 0024: `freshConcreteLibraryWaitsForExplicitDiscoveryInsteadOfSearchingOnBind` failed | Remove provider discovery from bootstrap; retain explicit `discover()` | Run 0025: 6/6 startup hydration tests |
| Panel lacked an explicit discovery action and eight API 36 tests assumed automatic search | Run 0029 listed eight affected UI tests before harness interruption | Add one truthful 48dp IDLE/FAILED search action; update tests to require an explicit click or local tag confirmation | Run 0031: 79 runner tests; XML 69 cases, 10 designed skips, 0 failures |

Run 0030 was interrupted because the command harness timeout was mistakenly set to one
second. It produced no product diagnostic and is not counted as a gate result.

Final exact-source regression runs 0032 (JVM matrix), 0033 (lint), and 0034 (all four APK
variants) passed after the last production/UI change.
