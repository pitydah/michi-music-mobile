# Compile and test results

| Check | Result | Log |
|---|---|---|
| JVM seven suites | **7511 tests, 0 failures/errors** | workflow 6cac6ed8, log 0024 |
| Data instrumented | **45 tests, 0 failures, 5 opt-in skips** | log 0031 |
| App instrumented Normal | **207 tests, 0 failures, 25 skips** | log 0025 |
| `:app:lint` | PASS | log 0033 (first combined run hit a lint-tool exception; isolated rerun passed) |
| Normal/F-Droid debug+release | PASS | log 0034 |
| T48 focused | PASS | focused `am instrument`, 7–8 s |

JVM breakdown: core 1023, data 749, player 234, michi-link-client 205, aircat-transfer 414, app Normal 2443, app F-Droid 2443.

No SKIP is counted as PASS. The five data skips are opt-in synthetic-restart/kill/media-store tests.

## ME-FINAL incremental

- `MetadataTrackDaoCasPortTest` (including durable-preimage authority) PASS, log 0003.
- T48 with preimage-authoritative CAS PASS, focused `am instrument`, 8.7 s.
- Full data/app/JVM suites from ME-14 remain the last complete run; final HEAD needs one more full pass/CI.
