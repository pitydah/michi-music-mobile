# Executed results (final HEAD `2fb39f17`)

All commands were run through `gradle-run` workflow `86dc1d84ded8c9a7cef4d7b523d2a861`; log IDs are from that workflow.

| Check | Result | Evidence |
|---|---|---|
| JVM seven suites | **7507 tests, 0 failures** | log 0048; core 1023, data 745, player 234, michi-link-client 205, aircat-transfer 414, app Normal 2443, app F-Droid 2443 |
| Data instrumented | **39 tests, 3 opt-in skips, 0 failures** | log 0049, emulator-5554 |
| App instrumented Normal | **207 tests, 25 skips, 0 failures** | log 0050, emulator-5554; includes the real T48 flow |
| `:app:lint` | **PASS** | log 0051 |
| Normal debug/release + F-Droid debug/release | **PASS** | log 0051 |
| Block A targeted snapshot tests | PASS | log 0003 |
| Block A data connected | PASS, 24 tests/3 skips | log 0004 |
| Block C applied-proof/reconciler tests | PASS | log 0008 |
| Block C data instrumented T36/X08/X12 | PASS | log 0016 |
| Block B five field E2E | PASS | log 0019 |
| Block D race E2E | PASS | log 0024 |
| T48 focused UI | PASS | focused `am instrument`, run14/T48 evidence |
| App unit isolated flake rerun | PASS | log 0047; full variants rerun PASS log 0048 |

No SKIP is counted as PASS. The three data skips and 25 app skips are opt-in/manual or visual-capture tests.
