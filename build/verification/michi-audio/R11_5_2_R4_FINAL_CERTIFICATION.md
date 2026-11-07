# R11.5.2-R4 final local certification

> **Historical certification — superseded.** This report certifies the earlier Room v8
> candidate `5dcaddedfd4774a04569025f5f07e3b88926a5dc`. The final Room v9 structural
> architecture and current gate decisions are defined by
> `ARTIST_FINAL_CERTIFICATION.md` and `docs/contracts/ARTIST_ENRICHMENT_DOMAIN_FINAL.md`.
> In particular, the v8 overlap model below is not current authority.

## Scope and heads

- Initial R4 HEAD: `6cb5a19311158f3707f76db667a41fde2268f91f`.
- Certified source candidate: `5dcaddedfd4774a04569025f5f07e3b88926a5dc`.
- Branch: `feat/ui2-r11-artist-artwork-federation`.
- Local commits: `19d8661`, `ab2f77b`, `cebecca6`, `5dcadde`.
- No R4 push, PR mutation, merge, release, UI3, provider addition, or playback change.

## Root causes and final architecture

K08 mixed canonical ownership with temporary membership and then reused exact choices by
overlap. K09 invalidated every decision for a credit when clearing one group. The final
domain preserves immutable canonical evidence, stores exact-context choices separately,
fails closed on incompatible history, and invalidates only decisions dependent on the
deleted canonical group. Room v8 remains sufficient and all migrations stay
non-destructive.

The first independent review at `cebecca6` returned SHIP with no P0/P1 and identified two
conditional P2 risks. Both were reproduced and fixed through RED→GREEN: malformed legacy
evidence now fails closed, and screen bind is provider-free. Renewal review session
`ses_e4baa9699ffew71NMX3MKHfxxB` inspected `cebecca6..5dcadde` and returned SHIP with no
P0/P1. Its informational notes are that post-clear intentionally shows only truthful clear
state until rebind, and release authority still requires human inspection of original skill
and Gradle logs.

## Local evidence

| Gate | Evidence | Result |
|---|---|---|
| K08/K09 focused | Runs 0001–0006, 0011–0012 | PASS |
| Migration v1→v8 | Run 0010, 6/6 | PASS |
| Artist Enrichment unit | Run 0009, 139/139 | PASS |
| Final app unit | Run 0026, Normal 883/883 and F-Droid 883/883 | PASS |
| Data | Run 0026, 171/171 per debug/release variant | PASS |
| Player | Run 0026, 169/169 per debug/release variant | PASS |
| Core | Run 0026, 34/34 per debug/release variant | PASS |
| Michi Link | Run 0026, 130/130 per debug/release variant | PASS |
| Lint | Run 0027 | PASS |
| Four APK variants | Run 0028 | PASS |
| API 36 instrumentation | Run 0031: runner 79; XML 69, 10 designed skips, 0 failures | PASS |
| SQLite integrity | File-backed transaction suite: `integrity_check=ok`, no FK rows | PASS |
| Final exact-source JVM matrix | Run 0032 | PASS |
| Final exact-source lint | Run 0033 | PASS |
| Final exact-source four APK variants | Run 0034 | PASS |
| Independent renewal review | `ses_e4baa9699ffew71NMX3MKHfxxB`, SHIP, no P0/P1 | PASS |

Run 0015 encountered an Android Lint internal failure at
`:app:lintVitalAnalyzeFdroidRelease`; unchanged isolated run 0016 and identical four-APK
retry 0017 passed. This was a non-reproducible build-tool transient, not a product failure.

## Gate decisions

| Gate | Decision |
|---|---|
| `ARTIST_IDENTITY_GO` | PASS |
| `ARTIST_HOMONYM_INTEGRITY_GO` | PASS |
| `ARTIST_ASSOCIATION_GO` | PASS |
| `ARTIST_SELECTIVE_CLEAR_GO` | PASS |
| `ARTIST_PERSISTENCE_TRUTH_GO` | PASS |
| `ARTIST_ARTWORK_GO` | PASS |
| `ARTIST_OFFLINE_GO` | PASS |
| `ARTIST_TRANSACTION_GO` | PASS |
| `ARTIST_MIGRATION_GO` | PASS |
| `ARTIST_REGRESSION_GO` | PASS |
| `ARTIST_DOMAIN_FREEZE_GO` | PASS_LOCAL |
| `DOMAIN_GO_LOCAL` | PASS |
| `REMOTE_CI_GO` | `REMOTE_CI_NOT_RUN` |
| `PHYSICAL_DEVICE_GO` | NOT RUN |
| `PR_MERGE_GO` | NOT AUTHORIZED |
| `RELEASE_GO` | NOT AUTHORIZED |

## UI adaptation and limits

Future UI3 must consume controller state/actions and the frozen contracts. It must not
infer MBIDs, start provider work during composition/screen bind, write association tables,
use Coil or remote URLs as authority, or create a parallel Artist Enrichment pipeline.

Potentially destructive instrumentation ran only on the isolated API 36 AVD. Physical
device, TalkBack, Bluetooth, and real-provider live certification were not run. Therefore:

The optional docs-write helper named by its generic skill was not installed at
`/home/cristian/.cursor/skills/docs-write/scripts/check-docs.sh`; the repository skill
manifest checks did run and returned `RECEIPT_FORMAT_PASS`. This unavailable helper does
not replace or weaken the repository checks.

**`ARTIST_ENRICHMENT_DOMAIN_STATUS = CLOSED_LOCAL`**

**`ARTIST_ENRICHMENT_READY_FOR_UI_ADAPTATION = YES`**
