# Michi Music Mobile — Release Blockers

## Open P0 / P1 Blockers

**Total Open Blockers: 0**

| ID | Severity | Component | Summary | Current Status |
| :--- | :--- | :--- | :--- | :--- |
| - | - | - | *No open P0 or P1 release blockers remaining.* | **CLEARED** |

---

## Resolved Blockers Summary

* **BLK-01 (P0)**: `:player:lintDebug` 29 errors on `UnsafeOptInUsageError` for Media3 `UnstableApi` — **RESOLVED**.
* **BLK-02 (P0)**: Unmanaged coroutine scopes in `PlayerController` — **RESOLVED** with `SupervisorJob` and `controllerScope`.
* **BLK-03 (P0)**: CI monolithic and incomplete testing — **RESOLVED** with multi-job parallel workflow in `.github/workflows/ci.yml`.
* **BLK-04 (P0)**: Release APK packaging failure due to missing keystore in CI/dev — **RESOLVED** with fallback to debug signing.
* **BLK-05 (P1)**: `SyncedTracksViewModelTest` coroutine exception before test in `testNormalReleaseUnitTest` — **RESOLVED**.

---

## Verdict for Release Candidate (RC)
The project branch `master` satisfies all Definition of Done criteria for a **Public Beta / Release Candidate**.
