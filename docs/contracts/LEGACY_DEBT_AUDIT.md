# Legacy debt audit — R15/R16 tickets still open

Status: **AUDIT ONLY. No behaviour changed by this document.** Every line below was
verified against the checkout that produced the commit this file ships with; a ticket is
only marked CLOSED when the code plus a test prove it, and only marked
`PENDING_HARDWARE` when the remaining gap is physical.

Reference authority: `docs/contracts/R16_AERO_IMPLEMENTATION_CONTRACT.md` §5–§6.

## Summary

| Ticket | Subject | State after this audit |
|---|---|---|
| K16-01 / K16-02 | Global primary keys in the synced cache | **CLOSED** — composite `(sourceId, id)` key, schema v8 |
| K16-08 / K16-26 | Bearer token over plain HTTP on the LAN | **MITIGATED, OPEN** — accepted LAN concession |
| K15-02 / K15-04 | Signal Truth epoch ownership | **CLOSED in code**, `PENDING_HARDWARE` for evidence |
| K15-03 | PEQ active → bypass transition without a ramp | **MITIGATED** — 5 ms crossfade, `PENDING_HARDWARE` measurement |
| K16-16 | IIR memory ownership across `onFlush` and publication | **MITIGATED** — lock + per-buffer snapshot |
| K16-29 | CPU/allocations budget inside `queueInput` | **OPEN** — needs measurement |
| K16-13 / K16-15 | — | **NO LOCAL DEFINITION** — see below |
| `compileSdk 36` | CameraX 1.6.2 requires API 36 | **CLOSED** — every module compiles against 36 |

## K16-01 / K16-02 — global keys in `cached_tracks` and `cached_playlists`

**State: CLOSED.** `data/schemas/org.michimusic.data.cache.MichiDatabase/8.json` and
`MichiDatabase.MIGRATION_7_8` move both tables to a composite `(sourceId, id)` primary key.
`sourceId` names the library that owns the row; an empty value is this device, which is what
the migration writes for every pre-existing row. `SyncedTrackRepository.saveLibrary` and
`saveManifestPlaylists` now take the peer's identifier and read their previous rows from that
source only, so two peers that hand out the same track or playlist id keep separate rows.
`PlaylistDao` mutations by id require the owner as well, which makes it impossible to edit or
delete another library's playlist by guessing its id.

**Evidence:** `Migration7To8Test` migrates a version-7 database, asserts that the legacy rows
survive under the local source, and asserts that the same id can be inserted twice under two
different owners. The full suite (1918 JVM tests) and the 12 instrumented tests pass, and the
schema-8 build migrated the emulator's real version-7 database with the library intact.

**Residual scope:** rows are scoped per *peer*, not per library inside a peer. A single server
that exposes several libraries still shares one source id; splitting that further needs the
server to send a library identifier in the manifest.

## K16-08 / K16-26 — bearer token over plain HTTP

**Verified:** `LinkClientConfig.validateEndpointSecurity` accepts `https` unconditionally and
`http` only for `localhost`, `127.0.0.1`, `::1`, `*.local`, private IPv4 by octet and private
IPv6 by real bytes (`fc00::/7`, `fe80::/10`). Single-label names are rejected on purpose: they
can resolve anywhere through the search domains.

**Still open:** `LinkClient.kt` sends `Authorization: Bearer …` on every authenticated call
(helper at line 149, headers at lines 162, 201, 524, 574, 670) regardless of scheme. Inside a
trusted LAN that is the intended concession, but it means the device token is readable by
anyone who can observe that LAN.

**What closes it:** either TLS with a pinned self-signed certificate for the server, or a
transport that never exposes the token (Tailscale/WireGuard), plus a UI that states plainly
which of the two is in use. Until then the honest position is: plain HTTP is a LAN-only
concession, and the app must not offer it for a public host — which the validator now enforces.

## K15-02 / K15-04 — Signal Truth epoch ownership

**Verified closed in code:** `player/.../SignalTruthTracker.kt` keeps `trackGeneration`,
`routeGeneration` and `currentAudioTrackGeneration`, bumps the track generation on
`onTrackChanged` and on `onMediaItemTransitionPending`, and rejects stale audio-track
callbacks (`if (generation > 0L && generation < currentAudioTrackGeneration) return`,
lines 193-195). `observedAtMs` is the max of the observed layer timestamps and is never
synthesised from `System.currentTimeMillis()`.

**Remaining:** the guarantee "a tap in an evidence view never changes the track" is only
covered by UI tests; a physical run is needed to confirm the sheet cannot show another
endpoint's telemetry after a handoff.

## K15-03 — PEQ active → bypass must not click

**Mitigated:** `HeadphonePeqAudioProcessor` crossfades between the previous and the new
filter bank (`oldFilters`, `oldPreamp`, `transitionTotal = sampleRate / 200`, i.e. 5 ms) and
bypasses byte-exact when the profile, route gate or bank is not ready.

**Still open:** "zero-click" is an audible property. It needs a measurement with a real DAC
and a signal analyser, or at minimum a recorded sweep, which is `PENDING_HARDWARE`.

## K16-16 — IIR memory ownership

**Mitigated by construction:** coefficients are compiled in `setProfile`/`onConfigure` under
`configurationLock`, `queueInput` never takes that lock, and each buffer reads an immutable
`PeqRequest` snapshot (`bands`, `active`, `revision`, `gate`, `prepared`, `rate`, `channels`,
`headroom`). `onFlush` resets IIR state and telemetry without discarding the compiled bank.
A stale revision can therefore never mix old bands with a new flag.

**Remaining:** a stress test that flushes while publishing revisions on a real audio thread;
today the invariant is argued from the lock discipline, not measured.

## K16-29 — CPU and allocation budget in `queueInput`

**Open.** No measurement exists for the audio callback. `queueInput` avoids allocation in the
steady path (no locks, no strings, no persistence), but the contract asks for a measured
budget, which belongs to the same physical run as PERF-06/07. Do not claim a budget without
numbers.

## K16-13 and K16-15 — no local definition

A repository-wide search for `K16-13` and `K16-15` (Kotlin sources and Markdown) returns
nothing. They were named in the handoff as open tickets but never described here, so this
audit records them as **unresolved identifiers**, not as work items. Whoever owns the
original list must supply the definition before any implementation, otherwise the "fix"
would be invented.

## `compileSdk 36` — CLOSED

All five modules now set `compileSdk = 36` and `gradle/libs.versions.toml` pins
`camerax = "1.6.2"`. `targetSdk` stays at 35 on purpose: raising it changes runtime behaviour
(edge-to-edge enforcement, background limits) and was never part of this ticket. The twelve
`GradleDependency` CameraX warnings disappeared with the update (`app` lint went from 151 to
139 warnings, zero errors).

## What this document does not do

It does not fix any of the above. It records the verified state so the next batch starts from
evidence instead of assumption, and so no report can claim a closed ticket that the code has
not closed.
