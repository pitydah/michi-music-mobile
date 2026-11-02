# AGENTS.md — Michi Music Mobile

## Project Identity

**Michi Music Mobile** — Portable node of the [Michi Music Ecosystem](https://github.com/pitydah).

| Field | Value |
|---|---|
| License | GPL-3.0 |
| Repository | https://github.com/pitydah/michi-music-mobile |
| Ecosystem Protocol | [Michi Link](https://github.com/pitydah/michi-link) |
| Language | Kotlin 2.1+ |
| UI | Jetpack Compose + Material 3 |
| Min SDK | 31 (Android 12) |
| Target SDK | 36 (Android 16) |
| Audio Engine | AndroidX Media3 (ExoPlayer + PCM ReplayGain + HAL Audio Effects) |
| Dependency Injection | Koin |
| Network & Remote | OkHttp + Server-Sent Events (SSE) + UDP Broadcast Discovery |
| Persistence & Cache | Room 2.7+ (SQLite) + EncryptedSharedPreferences |
| FLOSS Compliance | 100% FLOSS, pure AOSP compatibility, zero Google Play Services |

---

## Ecosystem Architecture & Source of Truth

Michi Music Mobile is the **portable node** of the Michi ecosystem. Its 5 core responsibilities are:
1. **PLAY**: Real local playback via AndroidX Media3 / ExoPlayer.
2. **ACCESS**: Remote library access and streaming from Michi Micro Server / Big Server.
3. **CONTROL**: Remote playback control of Desktop Player, Servers, and physical Michi Stream receivers.
4. **CONTINUE**: Seamless handoff between devices.
5. **CARRY**: Resilient offline library synchronization via Michi Sync.

### Hierarchy of Truth

When resolving contract or protocol differences:
1. **Priority 1**: Executable code, unit tests, and build configurations in the target repository.
2. **Priority 2**: [pitydah/michi-link](https://github.com/pitydah/michi-link) — the canonical interoperability contract of the ecosystem (schemas, OpenAPI, and wire formats).
3. **Priority 3**: Current architecture documentation of Michi Music Mobile.
4. **Priority 4**: Peer ecosystem repositories ([michi-music-player](https://github.com/pitydah/michi-music-player), [michi-micro-server](https://github.com/pitydah/michi-micro-server), [michi-music-stream](https://github.com/pitydah/michi-music-stream)).
5. **Priority 5**: Historical reference repositories ([michi-legacy](https://github.com/pitydah/michi-legacy)).

> **CRITICAL RULE**: Do not invent parallel APIs or arbitrary payloads. The `pitydah/michi-link` contract supersedes historical ad-hoc protocols.

---

## Active Gradle Modules

| Module | Responsibility |
|---|---|
| `:app` | Jetpack Compose UI, Navigation (`MichiNavHost`), Koin DI Modules, `SyncWorker` (WorkManager) |
| `:core` | Shared domain models (`Track`, `Album`, `Artist`, `Playlist`, `DiscoveredPeer`, enums) |
| `:data` | Room database (`MichiDatabase`), `MediaQueryDispatcher` (MediaStore), `SyncedTrackRepository` |
| `:player` | `MichiPlaybackService` (`MediaLibraryService`), `ExoPlayer`, `ReplayGainAudioProcessor`, `MichiAudioEffects`, `UsbDacManager` |
| `:michi-link-client` | `LinkClient` (HTTP REST), `EventClient` (SSE), `LinkDiscovery` (UDP 21120), `TokenStore` (AES Encrypted) |

---

## Build & Test

```bash
# Set Android SDK and JDK 21
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21

# Run unit tests across all modules
./gradlew test

# Run lint quality gate
./gradlew lint

# Build all APK variants (Normal & F-Droid)
./gradlew assembleNormalDebug assembleFdroidDebug assembleNormalRelease assembleFdroidRelease
```

---

## Design System: Digital Ethereal

- **Base Theme**: Pure Obsidian (`#090B11`), smoked glass surfaces (`0x1FFFFFFF`), 14–24dp rounded corners.
- **Accents**: Emotional Pink (`PrimaryPinkContainer`), Cyan audio feedback (`TertiaryCyan`), Secondary Purple (`SecondaryPurple`).
- **CoverFlow**: 3D interactive album carousel in Library with native Canvas/graphicsLayer rendering.
- **Accessibility**: All interactive targets $\ge 48\text{ dp}$, Spanish semantic descriptions, and truthful affordances (zero placebo controls).
---

## OpenCode — Chris Banes Skills mandatory for Android / Kotlin / Compose

**Canonical provider:** `chrisbanes/skills` (https://github.com/chrisbanes/skills). This R15 rule **supersedes** R14's `obra/superpowers` mandatory IDs. The two providers are distinct; never claim a `chrisbanes` skill was used when a different provider's skill was loaded. This section is an OpenCode agent policy, not evidence that the plugin is already installed.

**At startup:** invoke OpenCode's native `skill` tool, enumerate its available skills, load **`using-chrisbanes-skills`** and follow its router. Before editing, consult the relevant SKILL.md instructions from the installed version. The repository's skill taxonomy may change; use exact IDs exposed by OpenCode, not invented aliases. If the plugin isn't registered, record `BLOCKED_SKILL_CHRISBANES`; the human operator handles installation. Do not install plugins, change global OpenCode configuration or download third-party code implicitly.

**Project-scope required skills (this cumulative audio/Compose patch):**

| Workstream | Chris Banes skill ID | When and what must happen |
|---|---|---|
| Router | `using-chrisbanes-skills` | FIRST: enumerate and route relevant Android/Kotlin/Compose work |
| Planning | `to-plan` | BEFORE edits: baseline SHA, failure cases, acceptance criteria, scopes, isolation |
| Delivery | `deliver-spec` | Implement the approved plan, retain bounded changes, integrate and independently review |
| Coroutines/async | `kotlin-concurrency-and-flow` | Cancellation, track-epoch guards, hotplug, Flow/SSE, scope ownership |
| Domain/API | `kotlin-api-design` | Signal Truth evidence models, PEQ/route authority and public interfaces |
| Compose lifecycle | `compose-state-and-effects` | Screen state, effects, collectAsState, lifecycles and route changes |
| Compose components | `compose-component-design` | Michi Control, Signal Truth sheets, parameterized/reusable Compose APIs |
| Compose tests | `compose-ui-testing-patterns` | Compose semantics, state, focus and interaction regression coverage |
| Gradle/CI | `gradle-run` | EVERY agent-initiated Gradle command via Chris Banes's compact-output lifecycle |

**Additional conditional skills:** `compose-performance` for claimed recomposition/performance/jank improvements; `android-benchmark-comparison` for physical Android benchmark comparisons; `compose-animations` when editing Compose animations/transitions; `compose-focus-navigation` for focus and keyboard/D-pad behavior; `kotlin-control-flow` for complex/sealed branch changes; `implement-with-subagents` only when independent tasks justify delegation; `shepherd` for PR review/CI shepherding; `grounded-writing` for authoritative release notes/report text. `run-github-project` is not required without a GitHub Project workflow. Do NOT require every skill for every task; *do* require each applicable skill before editing that scope.

**Gradle safety rule:** `gradle-run` states that agent-initiated Gradle commands MUST use its `<skill-dir>/scripts/gradle_run.py create|run|status|finish` lifecycle as separate standalone tool invocations. Do not invoke `./gradlew` directly, pipe full logs or run `bash scripts/verify_michi_mobile_audio.sh --full` as an agent. The existing verification script's direct Gradle runner is reserved for an explicitly approved **human/operator** invocation (`MICHI_DIRECT_GRADLE_OPERATOR=1`) and never substitutes for the agent's `gradle-run` evidence. The agent must invoke the focused Gradle tasks from the R13 gate matrix through `gradle-run`, checking bounded results.

**Traceability gate:** export `build/verification/michi-audio/skills-used.tsv` with exactly `phase<TAB>skill_id<TAB>state<TAB>evidence_reference`; evidence references must identify real OpenCode native skill calls/session logs, never a fabricated row or a sample placeholder. Run `bash scripts/verify_michi_opencode_skills.sh --inventory` to inspect local skills and plugin hints, then `--check` to validate declared required rows. Plugin-provided skills may NOT exist in standard `.opencode/skills` directories; filesystem-only discovery is not authoritative. The TSV check is only a manifest sanity check; an independent reviewer must inspect original OpenCode logs before `RELEASE_GO`.

**NO-GO policy:** unavailable skill -> `BLOCKED_SKILL_CHRISBANES`, missing invocation evidence -> `BLOCKED_SKILL_EVIDENCE`, failed test -> `FAIL`, missing hardware -> `BLOCKED_HARDWARE`. Never silently change the source provider, equate a receipt with an observed tool call, or elevate `STATIC_GO` into Android/physical certification. Engram continues as the existing memory source; Serena/Context7 remain optional MCP complements, not Chris Banes skills.
