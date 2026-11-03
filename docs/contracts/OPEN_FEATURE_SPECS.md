# Open feature specs — written first, not implemented

Status: **SPEC_ONLY for all four items.** The owner's mandate forbids implementing
features without a specification, so each one below states goal, scope, out-of-scope,
acceptance criteria and risks. None of them has code in this batch, and nothing here
authorises a "done" claim.

---

## LIB-ART-01 phase 2 — artist identity beyond the local tags

**Phase 1 (shipped):** `ArtistEnrichmentPanel` reads embedded tags from local files
(`ArtistEmbeddedTags`, `LocalArtistTagProbe`), caches them in an isolated Room database
(`data/.../artist/ArtistEnrichmentCache.kt`) and only shows a remote profile after an
explicit search and tap.

**Goal of phase 2:** resolve a stable artist identity so the profile does not depend on
the spelling of the artist string.

**In scope:**
- MusicBrainz id resolution from name + optional disambiguation, with rate limiting and
  a user-visible "no match" state.
- Store the resolved `mbid`, `type`, `area` and `disambiguation` in the existing cache,
  keyed by the resolved id, and keep the raw query for auditability.
- Surface licences and the data source in the panel ("MusicBrainz · CC0", for example)
  because the contract requires attribution.
- Offline reads from cache only; no network call on scroll.

**Out of scope:** biography text, images, discography, any write to MusicBrainz, and any
Google Play Services dependency (prohibited by `AGENTS.md`).

**Acceptance:** a test proving no network call happens without a tap; a test proving a
cached profile survives a restart; the panel names the source and licence; a name with two
plausible matches shows both and never picks silently.

**Risks:** hitting the MusicBrainz rate limit from a mobile client; ambiguous names being
bound to the wrong artist; cache growth. Mitigations: debounce, one request per explicit
tap, `user-agent` with the app identity, and a bounded cache with eviction.

---

## DSP-STUDIO-03 phase 2 — real-rate curve and dedicated screen

**Phase 1 (shipped):** `PeqResponseCurve` draws a theoretical magnitude from the same RBJ
coefficients the processor uses, labelled "Respuesta calculada · NO es una medición".

**Goal of phase 2:** make the curve track the sample rate actually in use and give the six
filters a full editing surface.

**In scope:**
- Feed the curve the observed PCM sample rate from `SignalTruthTracker` instead of the
  48 kHz reference, and redraw when the rate changes.
- A dedicated editor screen with `MichiFineSlider` per parameter (frequency log 20 Hz–20 kHz,
  gain ±12 dB, Q 0.3–8) and a numeric field for exact entry.
- Telemetry line that separates `Configurado`, `Procesando PCM` and `Clipping observado`,
  never collapsing them into one "active" badge.
- Keyboard/D-pad focus order and TalkBack labels for every control.

**Out of scope:** measuring the DAC's real clock, importing external measurement files,
automatic room correction, and any claim of bit-perfect behaviour.

**Acceptance:** the curve's legend states the reference rate and the source of that rate;
changing the rate moves the axis without a full recomposition of the screen; the editor
round-trips six bands through persistence; reduced motion removes any animation.

**Risks:** the curve is easy to misread as a measurement; a per-frame recomposition if the
rate is read in composition instead of draw. Mitigation: keep the mandatory disclaimer and
read the rate through a deferred read.

---

## NP-01 — expanded Now Playing menu

**Goal:** replace the current two-sheet flow (Signal Truth + Michi Control) with one
expanded menu that still separates *evidence* from *control*.

**In scope:** a single entry point with two labelled groups ("Qué está sonando" y
"Adónde va el audio"), each opening its existing sheet; keep every current test tag and
`contentDescription`; keep the 48 dp targets; keep the honest texts already written.

**Out of scope:** merging the sheets into one scroll surface, adding new controls, and any
change to Signal Truth semantics.

**Acceptance:** the existing accessibility gate keeps passing; no sheet loses information
in the compact layout; the menu does not become a third source of truth for the route.

**Risks:** an expanded menu usually grows a second way to trigger playback; that must stay
out of scope, because control actions belong to the player, not to a menu.

---

## NP-02B / NP-02C — shared dialog system

**Goal:** one dialog shell for the app instead of the current per-screen compositions.

**In scope:** a shared `MichiDialog` built on the existing Aero surface tokens
(`MichiSurfaces`, `MichiShapes`, hairline border), with title, optional eyebrow, content
slot, dismiss action and a mandatory test tag; migrate the pairing, queue, playlist and
equalizer dialogs in separate commits; keep `AeroFloatingSurface` behaviour as the visual
reference.

**Out of scope:** re-styling every dialog at once, changing copy, and touching the
signal-truth sheet (it is a sheet, not a dialog).

**Acceptance:** each migrated dialog keeps its existing tests and tags; the shared shell
guarantees the 48 dp target on the close action; no dialog introduces a new colour outside
the Aero tokens; one screenshot per migrated dialog at 320 dp and 200 % font scale.

**Risks:** a shared shell invites a generic `content: @Composable () -> Unit` that hides
required structure. Mitigation: primitive parameters for title/eyebrow and a single content
slot, per `compose-component-design`.

---

## Why these are not implemented here

Each one changes product behaviour and needs its own design review, tests and screenshots
at 320/360/393/412 dp with 200 % font scale. Writing them down now prevents the next batch
from improvising a half-version of any of them.
