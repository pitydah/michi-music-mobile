#!/usr/bin/env python3
"""Fail-closed, standard-library-only preflight for the Artist Enrichment patch.

This program NEVER applies a patch, runs Gradle, writes repo files or claims that
Android/Room/WorkManager tests passed. Its JSON result is a diagnostic receipt,
NOT substitute evidence for the OpenCode gradle-run and device-test gates.

Usage (after copying script outside repo, or after applying the candidate):
  python3 tools/artist_enrichment_gate.py inventory --patch /tmp/artist.patch --peer-patch /tmp/genres.patch
  python3 tools/artist_enrichment_gate.py preapply --patch /tmp/artist.patch
  python3 tools/artist_enrichment_gate.py postapply --patch /tmp/artist.patch
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
from typing import Any

# K29 receiver baseline: f69eea is UI7/assets and build config; inspect Gradle changes at reception.
BASE_HEAD = "b15b42c3f9cad5f7bf9da8e43edf4963bd14f090"  # K46 candidate integrated on the MI-26 follow-up
ALLOWED_PREFIXES = ("app/", "data/", "core/", "tools/")
PATCH_HEADER = re.compile(r"^diff --git a/(\S+) b/(\S+)$", re.MULTILINE)
ROOT_MARKERS = ("AGENTS.md", "gradlew", "settings.gradle.kts", "gradle/libs.versions.toml")
MAX_PATCH_BYTES = 2_000_000
MAX_PATCH_FILES = 160 # accommodates 130+ paths while retaining a bounded receipt
HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", re.MULTILINE)
FORBIDDEN_METADATA = re.compile(r"(?m)^(?:rename (?:from|to)|copy (?:from|to)|old mode |new mode |similarity index |dissimilarity index |GIT binary patch|Binary files )")
SAFE_MODES = {"100644", "100755"}


def patch_sections(content: str) -> dict[str, str]:
    """Boundaries are canonical 'diff --git' lines; odd headers fail validation."""
    chunks = re.split(r"(?=^diff --git )", content, flags=re.MULTILINE)
    sections: dict[str, str] = {}
    for chunk in chunks:
        if not chunk.startswith("diff --git "):
            continue
        match = PATCH_HEADER.match(chunk)
        if match:
            sections[match.group(1)] = chunk
    return sections


def hunk_ranges(section: str) -> list[tuple[int, int]]:
    """Original line intervals; a 0-length insertion is a point, conservatively."""
    spans = []
    for match in HUNK.finditer(section):
        start = int(match.group(1))
        count = int(match.group(2) or "1")
        spans.append((start, start + max(count - 1, 0)))
    return spans


def collision_receipt(primary: str, peer: str, primary_data: bytes, peer_data: bytes) -> dict[str, Any]:
    try:
        a = patch_sections(primary_data.decode("utf-8"))
        b = patch_sections(peer_data.decode("utf-8"))
    except UnicodeDecodeError:
        return {"peer": peer, "shared_paths": [], "hunk_intersections": [], "error": "invalid UTF-8"}
    shared = sorted(set(a) & set(b))
    hits = []
    for path in shared:
        for first in hunk_ranges(a[path]):
            for second in hunk_ranges(b[path]):
                if max(first[0], second[0]) <= min(first[1], second[1]):
                    hits.append({"path": path, "artist_original_lines": list(first), "peer_original_lines": list(second)})
    return {"peer": peer, "shared_paths": shared, "hunk_intersections": hits,
            "note": "Shared paths block automatic application even when original-line spans are disjoint."}



# These checks are static receipts only. They protect the Metadata/Genres features
# already present in the Metadata-integrated HEAD from accidentally disappearing during an AE rebase.
MERGED_SOURCE_INVARIANTS = {
    "core/src/main/java/org/michimusic/core/models/Models.kt": (
        "@Transient val genreValues:", "@Transient val observedArtistMbids:",
        "@Transient val observedRecordingMbids:",
    ),
    "data/src/main/java/org/michimusic/data/DataModule.kt": (
        "ExtendedTrackMetadataRepository(get(), androidContext())",
        "getOrNull<org.michimusic.data.repository.LocalCatalogListener>()",
    ),
    "data/src/main/java/org/michimusic/data/repository/LocalMediaRepository.kt": (
        "val embedded = if (cachedEvidence != null) cachedEvidence",
        "else if (!needsProbe) null", "freshlyReadTags = fields",
        "genreValues = if (embedded?.genre == genre",
    ),
    "app/src/main/java/org/michimusic/mobile/ui/screens/SettingsLibraryPanel.kt": (
        "ArtistArtworkAutoSettings()", "val context = LocalContext.current",
    ),
}


def merged_source_contract(root: pathlib.Path) -> dict[str, Any]:
    """Presence and order guard, not a Kotlin typechecker or an Android certificate."""
    missing: list[str] = []
    for relative, needles in MERGED_SOURCE_INVARIANTS.items():
        source = root / relative
        try:
            content = source.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            missing.append(relative + ":file")
            continue
        for needle in needles:
            if needle not in content:
                missing.append(relative + ":" + needle[:42])
        if relative.endswith("LocalMediaRepository.kt"):
            cached = content.find("val embedded = if (cachedEvidence != null) cachedEvidence")
            fallback = content.find("else if (!needsProbe) null", cached if cached >= 0 else 0)
            if cached < 0 or fallback < cached or fallback - cached > 256:
                missing.append(relative + ":cached-evidence-priority")
    return result("MERGED_SOURCE_CONTRACT_STATIC", not missing,
                  "Metadata/Genre and Artist coexistence anchors found" if not missing
                  else "Missing anchors: " + "; ".join(missing[:8]))


def no_implicit_backfill_contract(root: pathlib.Path) -> dict[str, Any]:
    """Static wiring check: opt-in and toggles must NOT enqueue catalog-wide backfill."""
    scheduler = root / "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt"
    settings = root / "app/src/main/java/org/michimusic/mobile/ui/screens/ArtistArtworkAutoSettings.kt"
    try:
        sched = scheduler.read_text(encoding="utf-8")
        ui = settings.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return result("NO_IMPLICIT_BACKFILL_STATIC", False, "Scheduler or Settings absent")
    violations = []
    if "enqueueInitialBackfill" in sched or "ArtistArtworkInitialBackfillWorker" in sched:
        violations.append("scheduler-autostarts-backfill")
    if "ArtistArtworkAutoBackfill(app)" not in ui or "artist_artwork_backfill_batch" not in ui:
        violations.append("missing-explicit-user-batch")
    # Three toggles (consent, pause, network) can commit preferences before the
    # scheduler fails. They must always reload the actual state on completion.
    if "suspend fun syncPreferenceState()" not in ui or ui.count(
        "finally { try { syncPreferenceState() } finally { busy = false } }"
    ) < 3:
        violations.append("settings-switches-not-reconciled-after-error")
    return result("NO_IMPLICIT_BACKFILL_STATIC", not violations,
                  "; ".join(violations) if violations else "Only explicit UI action starts a library review")



POST_APPLY_ALLOWLIST = (
    "app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentPersistence.kt",
    "app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkModels.kt",
    "app/src/test/java/org/michimusic/mobile/artist/ArtistEnrichmentPostCommitSignalTest.kt",
    "app/src/test/java/org/michimusic/mobile/artist/artwork/CommonsCategoryArtworkProviderTest.kt",
    "app/src/test/java/org/michimusic/mobile/artist/artwork/CommonsGalleryArtworkProviderTest.kt",
    "data/src/test/java/org/michimusic/data/artist/ArtistArtworkAutomatedCommitTest.kt",
    "data/src/test/java/org/michimusic/data/artist/ArtistArtworkBoundDispatchTest.kt",
    "data/src/test/java/org/michimusic/data/artist/ArtistArtworkCatalogJournalTest.kt",
    "tools/artist_enrichment_gate.py",
    "tools/artist_enrichment_integration_gate.py",
)


def commons_batch_contract(root: pathlib.Path) -> dict[str, Any]:
    """Fail closed if a future diff resurrects unbounded per-file P180 lookups."""
    base = root / "app/src/main/java/org/michimusic/mobile/artist/artwork"
    paths = [base / name for name in (
        "CommonsDepictionBatchPolicy.kt", "CommonsCategoryArtworkProvider.kt",
        "CommonsArtworkProviders.kt")]
    try:
        budget, category, providers = (p.read_text(encoding="utf-8") for p in paths)
    except (OSError, UnicodeDecodeError):
        return result("COMMONS_BATCH_CONTRACT_STATIC", False, "Missing provider or batch proof sources")
    p180 = providers.split("internal class CommonsP180Provider", 1)[-1].split(
        "internal class WikipediaPageImageProvider", 1)[0]
    issues = []
    if "const val MAX_PROBE_FILES = 12" not in budget or             "validWikidataQid(artistQid)" not in budget or             "depictsByMediaId" not in budget:
        issues.append("file-level-proof-or-budget")
    if "commons.depictsBatch(" not in category or             ".take(6)" in category:
        issues.append("category-batch-coverage")
    if ("suspend fun depictsBatch(" not in providers or
            '.addQueryParameter("action", "wbgetentities")' not in providers):
        issues.append("batch-media-info-proof")
    if ("commons.depictsBatch(distinct, qid)" not in p180 or
            "commons.depicts(file, qid)" in p180 or
            '.addQueryParameter("srlimit", "12")' not in p180 or
            "for (page in 0 until 2)" in p180):
        issues.append("p180-query-fanout")
    return result("COMMONS_BATCH_CONTRACT_STATIC", not issues,
                  "; ".join(issues) if issues else
                  "Single P180 search page and 2 batched file-level verification requests")

def review_receipt_contract(root: pathlib.Path) -> dict[str, Any]:
    """Static wiring only: a reviewed claim never becomes an auto identity grant."""
    fields = {
        "app/src/main/java/org/michimusic/mobile/artist/ArtistEnrichmentPanel.kt": (
            "reviewReceipts.save(reviewScope, claim, result)",
            "reviewReceipts.load(reviewScope, claim)",
            "ArtistStructuredCreditClaimPlanner.plan(artistCredit, evidence)",
        ),
        "app/src/main/java/org/michimusic/mobile/artist/ArtistCreditReviewReceiptPersistence.kt": (
            "ArtistCreditReviewReceiptPolicy.create", "db.withTransaction", "sourceId() != scope.sourceId",
        ),
        "data/src/main/java/org/michimusic/data/artist/ArtistEnrichmentCache.kt": (
            "ArtistCreditReviewReceiptRecord::class", "CREATE TABLE IF NOT EXISTS `artist_credit_review_receipt`",
            "suspend fun getCreditReviewReceipt", "suspend fun putCreditReviewReceipt",
        ),
    }
    missing = []
    for relative, tokens in fields.items():
        try: body = (root / relative).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            missing.append(relative + ":missing")
            continue
        missing.extend(relative + ":" + token[:32] for token in tokens if token not in body)
    return result("REVIEW_RECEIPT_STATIC", not missing,
                  "Scoped, expiring Room receipt connected to explicit MusicBrainz review" if not missing
                  else "Missing receipt anchors: " + "; ".join(missing[:8]))


def room_review_schema_shape(schema: pathlib.Path) -> bool:
    """Never accept arbitrary 11.json: the complete receipt PK must be generated by KSP."""
    try:
        exported = json.loads(schema.read_text(encoding="utf-8"))
        database = exported.get("database") or {}
        if database.get("version") != 11 or not isinstance(database.get("identityHash"), str) or \
            len(database["identityHash"]) < 16:
            return False
        entities = database.get("entities")
        if not isinstance(entities, list): return False
        receipt = next((e for e in entities if e.get("tableName") == "artist_credit_review_receipt"), None)
        if receipt is None: return False
        columns = {f.get("columnName") for f in receipt.get("fields", [])}
        if not {"libraryId", "sourceId", "creditKey", "albumKey", "trackId",
                "artistMbid", "recordingMbid", "releaseMbid", "role", "verifiedAtMs"}.issubset(columns):
            return False
        return (receipt.get("primaryKey") or {}).get("columnNames") == [
            "libraryId", "sourceId", "creditKey", "albumKey", "trackId"]
    except (ValueError, OSError, TypeError, KeyError, AttributeError):
        return False


def git(root: pathlib.Path, *args: str, input_bytes: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(["git", "-C", str(root), *args], input=input_bytes,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)


def result(name: str, ok: bool, detail: str) -> dict[str, Any]:
    return {"gate": name, "pass": ok, "detail": detail[:400]}


def patch_inventory(patch: pathlib.Path, *, primary: bool = True) -> tuple[bytes, list[str], list[dict[str, Any]]]:
    checks: list[dict[str, Any]] = []
    if not patch.is_file():
        return b"", [], [result("PATCH_EXISTS", False, "Patch file not found")]
    data = patch.read_bytes()
    if not (0 < len(data) <= MAX_PATCH_BYTES):
        checks.append(result("PATCH_BUDGET", False, f"Unexpected patch size: {len(data)} bytes"))
        return data, [], checks
    try:
        content = data.decode("utf-8")
    except UnicodeDecodeError:
        checks.append(result("PATCH_UTF8", False, "Patch is not UTF-8"))
        return data, [], checks
    entries = PATCH_HEADER.findall(content)
    paths = [a for a, b in entries]
    checks.append(result("PATCH_PATH_COUNT", 0 < len(paths) <= MAX_PATCH_FILES,
                         f"{len(paths)} changed paths; maximum {MAX_PATCH_FILES}"))
    checks.append(result("PATCH_UNIQUE_PATHS", len(paths) == len(set(paths)),
                         f"{len(paths) - len(set(paths))} duplicate diff headers"))
    valid_paths = all(a == b and (not primary or a.startswith(ALLOWED_PREFIXES)) and
                      not a.startswith("/") and "\\" not in a and
                      all(bit not in ("", ".", "..") for bit in a.split("/"))
                      for a, b in entries)
    checks.append(result("PATCH_ALLOWED_PATHS", valid_paths,
                         "All paths must be identical a/b and inside app/, data/, core/, tools/"))
    binary_section = re.search(r"(?m)^(?:GIT binary patch|Binary files .+ differ)$", content)
    checks.append(result("PATCH_TEXT_ONLY", binary_section is None,
                         "No binary patch sections in diff headers"))
    checks.append(result("PATCH_STRUCTURE", len(entries) == len(re.findall(r"(?m)^diff --git ", content)),
                         "Each diff entry has one canonical Git diff header"))
    sections = patch_sections(content)
    section_ok = True
    for path, section in sections.items():
        markers = re.findall(r"(?m)^--- (.*)$", section)
        targets = re.findall(r"(?m)^\+\+\+ (.*)$", section)
        if len(markers) != 1 or len(targets) != 1:
            section_ok = False
            break
        if markers[0] not in ("a/" + path, "/dev/null") or targets[0] not in ("b/" + path, "/dev/null"):
            section_ok = False
            break
        if markers[0] == "/dev/null" and targets[0] == "/dev/null":
            section_ok = False
            break
        modes = re.findall(r"(?m)^(?:new file mode|deleted file mode) (\d+)$", section)
        if any(mode not in SAFE_MODES for mode in modes) or FORBIDDEN_METADATA.search(section):
            section_ok = False
            break
        if not hunk_ranges(section):
            section_ok = False
            break
    checks.append(result("PATCH_SAFE_SECTIONS", section_ok and len(sections) == len(entries),
                         "Canonical /dev/null and a/b markers, text hunks, no symlinks, rename/copy or mode changes"))
    checks.append(result("PATCH_SHA256", True, hashlib.sha256(data).hexdigest()))
    return data, paths, checks


def inspect_repo(root: pathlib.Path, expected_head: str) -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    markers = [p for p in ROOT_MARKERS if not (root / p).is_file()]
    checks.append(result("REPOSITORY_MARKERS", not markers,
                         "Missing: " + ", ".join(markers) if markers else "AGENTS, Gradle and settings present"))
    top = git(root, "rev-parse", "--show-toplevel")
    top_path = top.stdout.decode(errors="replace").strip()
    is_root = top.returncode == 0 and pathlib.Path(top_path).resolve() == root.resolve()
    checks.append(result("CHECKOUT_ROOT", is_root, top_path or "Not a Git working tree"))
    if is_root:
        head = git(root, "rev-parse", "HEAD")
        actual = head.stdout.decode(errors="replace").strip()
        checks.append(result("BASE_HEAD", head.returncode == 0 and actual == expected_head,
                             f"actual={actual}; expected={expected_head}"))
    if (root / "gradle/libs.versions.toml").is_file():
        text = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8", errors="replace")
        version_ok = bool(re.search(r'(?m)^room\s*=\s*"2\.7\.1"', text)) and bool(
            re.search(r'(?m)^ksp\s*=\s*"2\.1\.10-1\.0\.31"', text))
        checks.append(result("ROOM_KSP_BASELINE", version_ok, "Expected Room 2.7.1 and KSP 2.1.10-1.0.31"))
    return checks


def audit(stage: str, root: pathlib.Path, patch: pathlib.Path, expected_head: str,
          peer_patches: tuple[pathlib.Path, ...] = ()) -> dict[str, Any]:
    data, paths, checks = patch_inventory(patch)
    collisions = []
    # Peer patches may legitimately touch docs/ and .github/. They need path
    # security validation, not the Artist Enrichment scope restriction.
    for peer in peer_patches:
        peer_data, peer_paths, peer_checks = patch_inventory(peer, primary=False)
        peer_ok = all(c["pass"] for c in peer_checks)
        receipt = collision_receipt("artist_enrichment", peer.name, data, peer_data)
        collisions.append(receipt)
        overlaps = receipt["shared_paths"]
        checks.append(result("PEER_PATCH_COMPATIBILITY", peer_ok and not overlaps,
                             ("Shared paths: " + ", ".join(overlaps[:12])) if overlaps
                             else (f"Peer valid, disjoint: {peer.name}" if peer_ok else
                                   f"Invalid peer patch: {peer.name}")))
    if stage != "inventory":
        checks.extend(inspect_repo(root, expected_head))
    initial_checks_ok = all(item["pass"] for item in checks)
    if stage == "inventory":
        phase = "STATIC_INVENTORY_GO" if checks and all(c["pass"] for c in checks) else "BLOCKED_CONCURRENT_PATCH_OVERLAP"
    elif stage == "preapply":
        if initial_checks_ok:
            status = git(root, "status", "--porcelain", "--untracked-files=all")
            clean = status.returncode == 0 and not status.stdout.strip()
            checks.append(result("CLEAN_CHECKOUT", clean,
                                 "Clean tree" if clean else "Checkout is dirty/untracked files present"))
            if clean:
                applied = git(root, "apply", "--check", "--whitespace=error", "-", input_bytes=data)
                checks.append(result("APPLY_CHECK", applied.returncode == 0,
                                     applied.stderr.decode(errors="replace") or "git apply --check passed"))
        phase = "STATIC_PRE_APPLY_GO" if checks and all(c["pass"] for c in checks) else "BLOCKED_PRE_APPLY"
    else:
        # Postapply proves the exact patch can be reversed; no changes are made.
        if initial_checks_ok:
            missing = [p for p in paths if not (root / p).is_file()]
            checks.append(result("PATCH_PATHS_PRESENT", not missing,
                                 "Missing: " + ", ".join(missing[:5]) if missing else "All patch paths exist"))
            if not missing:
                reverse_args = ["apply", "--reverse", "--check"]
                for allowed in POST_APPLY_ALLOWLIST:
                    reverse_args.append(f"--exclude={allowed}")
                reverse = git(root, *reverse_args, "-", input_bytes=data)
                reverse_detail = (
                    "git apply --reverse --check passed; allowlisted post-apply edits: " +
                        ", ".join(POST_APPLY_ALLOWLIST)
                    if reverse.returncode == 0
                    else reverse.stderr.decode(errors="replace")
                )
                checks.append(result("REVERSE_APPLY_CHECK", reverse.returncode == 0, reverse_detail))
                whitespace = git(root, "diff", "--check")
                checks.append(result("PATCH_WHITESPACE", whitespace.returncode == 0,
                                     whitespace.stdout.decode(errors="replace") or "git diff --check passed"))
                if set(MERGED_SOURCE_INVARIANTS).issubset(set(paths)):
                    checks.append(merged_source_contract(root))
                if {"app/src/main/java/org/michimusic/mobile/artist/artwork/ArtistArtworkAutoScheduler.kt",
                    "app/src/main/java/org/michimusic/mobile/ui/screens/ArtistArtworkAutoSettings.kt"}.issubset(set(paths)):
                    checks.append(no_implicit_backfill_contract(root))
                if {"app/src/main/java/org/michimusic/mobile/artist/artwork/CommonsArtworkProviders.kt",
                    "app/src/main/java/org/michimusic/mobile/artist/artwork/CommonsCategoryArtworkProvider.kt",
                    "app/src/main/java/org/michimusic/mobile/artist/artwork/CommonsDepictionBatchPolicy.kt"}.issubset(set(paths)):
                    checks.append(commons_batch_contract(root))
            schema = root / "data/schemas/org.michimusic.data.artist.ArtistEnrichmentCacheDatabase/11.json"
            schema_ok = room_review_schema_shape(schema)
            checks.append(result("ROOM_V11_SCHEMA_SHAPE_ONLY", schema_ok,
                                 "Room 11.json includes review receipt columns/PK; KSP provenance and migration NOT certified"
                                 if schema_ok else "BLOCKED: Room 11.json lacks receipt/PK or is missing; run actual KSP"))
            if {"app/src/main/java/org/michimusic/mobile/artist/ArtistCreditReviewReceiptPersistence.kt",
                "data/src/main/java/org/michimusic/data/artist/ArtistCreditReviewReceiptRecord.kt"}.issubset(set(paths)):
                checks.append(review_receipt_contract(root))
        phase = "STATIC_POST_APPLY_GO" if checks and all(c["pass"] for c in checks) else "BLOCKED_POST_APPLY"
    return {"phase": phase, "android_certified": False, "patch_sha256": hashlib.sha256(data).hexdigest(),
            "path_count": len(paths), "checks": checks, "peer_collisions": collisions,
            "notes": "Static inspection only. Use OpenCode Chris Banes gradle-run plus Room/Robolectric and device evidence."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("inventory", "preapply", "postapply"))
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path.cwd())
    parser.add_argument("--patch", type=pathlib.Path, required=True)
    parser.add_argument("--expected-head", default=BASE_HEAD)
    parser.add_argument("--peer-patch", type=pathlib.Path, action="append", default=[],
                        help="Optional concurrent metadata/genres diff; fail on any shared path")
    options = parser.parse_args()
    summary = audit(options.stage, options.root.resolve(), options.patch.resolve(),
                    options.expected_head, tuple(p.resolve() for p in options.peer_patch))
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if summary["phase"].endswith("_GO") else 2


if __name__ == "__main__":
    sys.exit(main())
