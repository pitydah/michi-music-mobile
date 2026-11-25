# Process-kill matrix

- Added `MetadataSaveKillInstrumentedTest` with two opt-in phases:
  - phase one drives production `MetadataSaveService` and kills the instrumentation process at `AFTER_PROVIDER_CLOSE`;
  - phase two starts a new process, reads the exact private record, opens the same file Room DB and calls `MetadataRecoveryCoordinator.resume`.
- Normal `:data:connectedDebugAndroidTest` skips both phases (5 opt-in skips total).
- Attempted phase one on the emulator: save returned
  `Failed("La escritura quedó en recuperación pendiente", needsRecovery=true)` because the test package's MediaStore `openFileDescriptor(uri, "rwt")` returned no descriptor at the transport boundary. The test harness is therefore **NOT RUN / FAILED to reach its checkpoint**, not PASS.
- No production process was killed; no user journal was touched.
- T18/T19/T20/T22/T43 and N26–N28 remain NOT RUN.
