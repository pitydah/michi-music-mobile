# AirCat Real Integrity Ledger & Manifest Verification Protocol (M49)

**Auditor:** Distributed Audio Reliability Engineer & Principal Android Engineer  
**Milestone:** M49 — AERO FINAL 100  
**Branch:** `integration/m46d-aero-premium`  

---

## 1. Overview & Verification Standard

AirCat is Michi's peer-to-peer Wi-Fi Direct and local network track synchronization protocol. In M49, AirCat underwent a comprehensive security and cryptographic audit to eliminate manifest injection vulnerabilities, byte overflow bugs, and temp-file hashing race conditions (K48-02, K48-03, K48-04).

---

## 2. Manifest Validation Rules (K48-03)

Every incoming or outgoing AirCat manifest is subjected to the following invariant checks prior to initiating any file downloads:

| Validation Rule | Enforcement Mechanism | Failure Mode / Handling |
|---|---|---|
| **SHA-256 Formatting** | Regex `^[a-fA-F0-9]{64}$` enforced per track entry. | `AirCatManifestException.InvalidChecksumFormat` — Transfer aborted immediately. |
| **Negative Track Sizes** | Checked `sizeBytes >= 0L`. Negative values rejected. | `AirCatManifestException.InvalidFileSize` — Transfer rejected. |
| **Byte Sum Overflow** | Checked `Math.addExact(accum, track.sizeBytes)`. | `AirCatManifestException.ByteOverflow` — Transfer rejected before storage allocation. |
| **Track ID Uniqueness** | `Set<String>` lookup over `manifest.tracks.map { it.id }`. | `AirCatManifestException.DuplicateTrackId` — Transfer rejected. |
| **Empty Transfer** | Zero tracks handled gracefully without error. | Returns `AirCatTransferResult.Success(transferredCount = 0, bytes = 0L)`. |

---

## 3. Cryptographic Storage & File Lifecycle (K48-02)

To guarantee that incomplete downloads are never marked as valid or exposed to the audio scanner, the atomic transfer protocol operates through a 5-stage pipeline:

```
[ Incoming Network Stream ]
            │
            ▼
    1. Write to `.tmp` file (e.g., `trk_49.tmp`)
            │
            ▼
    2. Flush buffers & close write stream
            │
            ▼
    3. Calculate SHA-256 hash over completed `.tmp` file
            │
            ├─── Checksum Match ────► 4. Atomic file rename: `trk_49.tmp` ➔ `trk_49.flac`
            │                                    │
            │                                    ▼
            │                         5. Invalidate MediaStore cache & trigger scan
            │
            └─── Checksum Mismatch ─► Delete `.tmp` file & fail with `ChecksumMismatchException`
```

---

## 4. Device Eligibility Policy (K48-04)

`AirCatEligibilityPolicy` enforces strict boundary rules to prevent routing transfers to unsupported or malicious hardware:

```kotlin
object AirCatEligibilityPolicy {
    fun isDeviceEligible(device: DiscoveredDevice): Boolean {
        // Michi Stream is a dedicated I2S/PCM hardware DAC receiver with NO local storage
        if (device.deviceType == DeviceType.STREAM_RECEIVER) return false

        // Local USB DACs and audio peripherals are non-storage output endpoints
        if (device.deviceType == DeviceType.USB_DAC || device.deviceType == DeviceType.AUDIO_OUTPUT) return false

        // Revoked or untrusted endpoints are strictly blocked
        if (device.connectionState == SyncConnectionState.REVOKED ||
            device.connectionState == SyncConnectionState.UNAUTHORIZED) return false

        // Must declare AirCat storage capability
        return device.capabilities.contains(DeviceCapability.AIRCAT_RECEIVER) ||
               device.capabilities.contains(DeviceCapability.FILE_TRANSFER)
    }
}
```

---

## 5. Test Suite Verification

The integrity guarantees are verified in:
- `michi-link-client/src/test/java/org/michimusic/link/AirCatContractTest.kt` (27 unit tests)
- `app/src/test/java/org/michimusic/mobile/devices/aircat/AirCatEligibilityPolicyTest.kt` (9 unit tests)

