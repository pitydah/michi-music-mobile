#!/usr/bin/env python3
"""UI7 Settings architectural regression gate. Run from any working directory.

Pure source inspection: does not build Android or claim to validate runtime behavior.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
SCREENS = ROOT / "app/src/main/java/org/michimusic/mobile/ui/screens"
SYNC = ROOT / "app/src/main/java/org/michimusic/mobile/sync"

PANELS = (
    "SettingsPlaybackPanel.kt", "SettingsProcessingPanel.kt", "SettingsOutputPanel.kt",
    "SettingsLibraryPanel.kt", "SettingsEcosystemPanel.kt", "SettingsAppearancePanel.kt",
    "SettingsAccessibilityPanel.kt", "SettingsPrivacyPanel.kt", "SettingsSystemPanel.kt",
)
# The existing Processing/Output destinations share SettingsFunctionalPanels.kt.
ACTUAL_PANELS = tuple(p for p in PANELS if p not in (
    "SettingsProcessingPanel.kt", "SettingsOutputPanel.kt")) + ("SettingsFunctionalPanels.kt",)

def error(message: str) -> None:
    raise SystemExit(f"SETTINGS ARCHITECTURE FAIL: {message}")


def code(name: str) -> str:
    path = SCREENS / name
    if not path.exists():
        error(f"missing {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def run() -> None:
    screen = code("SettingsScreen.kt")
    if len(screen.splitlines()) > 180:
        error("SettingsScreen.kt grew beyond 180 lines: extract a destination")
    shell_code = re.sub(r"/\*.*?\*/", "", screen, flags=re.S)
    shell_code = re.sub(r"//[^\n]*", "", shell_code)
    shell_forbidden = (
        "koinInject", "SharedPreferences", "AppDao", "MusicRootAuthority",
        "AutoSyncScheduler", "PlayerDependencies", "WorkManager", "AudioModeManager",
        "VisualPreferencesRepository", "MediaStore", "rememberLauncherForActivityResult",
    )
    for symbol in shell_forbidden:
        if re.search(r"\b" + re.escape(symbol) + r"\b", shell_code):
            error(f"root navigation directly references domain service {symbol}")
    for panel in ACTUAL_PANELS:
        source = code(panel)
        if len(source.splitlines()) > 450:
            error(f"{panel} grew beyond 450 lines; split its UI from its controller")
        if "@Composable" not in source:
            error(f"{panel} no longer exposes a Compose destination")
    shared = code("SettingsSharedWidgets.kt")
    for forbidden in ("koinInject", "AppDao", "PlayerDependencies", "AutoSyncScheduler"):
        if re.search(r"\b" + forbidden + r"\b", shared):
            error(f"shared UI primitive contains {forbidden}")
    if "observeWorkTruth()" not in (SYNC / "AutoSyncScheduler.kt").read_text():
        error("scheduling truth is not available from the established scheduler")
    if "SettingsReplayGainGateway" not in code("SettingsPlaybackPanel.kt"):
        error("playback stopped using the shared ReplayGain preference bridge")
    if "SettingsReplayGainGateway" not in code("SettingsHubLanding.kt"):
        error("hub summaries stopped observing the same ReplayGain keys")
    if "AUDIO_QUALITY, SettingsSection.PLAYBACK" not in screen:
        error("old AUDIO_QUALITY route lost compatibility")
    preview = code("SettingsSeekerPreview.kt")
    if len(preview.splitlines()) > 150:
        error("seeker preview becoming a monolith")
    if "AeroSeekerTrack" not in preview:
        error("seeker demo must reuse the existing presentation renderer")
    for forbidden in ("LocalWaveformDecoder", "MediaMetadataRetriever",
        "WorkManager", "infiniteRepeatable", "LaunchedEffect", "pointerInput"):
        if forbidden in preview:
            error(f"seeker demo must not decode, loop or own gestures: {forbidden}")
    appearance = code("SettingsAppearancePanel.kt")
    if "SettingsSeekerPreview(choice)" not in appearance or         "settings_seeker_demo_disclosure" not in appearance:
        error("five style examples must be visible and marked non-measured")
    PLAYBACK = ROOT / "app/src/main/java/org/michimusic/mobile/playback"
    transaction = (PLAYBACK / "SettingsReplayGainTransaction.kt").read_text()
    if "SettingsReplayGainTransactionPort" not in transaction or         "SettingsReplayGainSaveStatus.ROLLED_BACK" not in transaction:
        error("ReplayGain transaction must expose tested rollback semantics")
    transaction_body = re.sub(r"/\*.*?\*/", "", transaction, flags=re.S)
    transaction_body = re.sub(r"//[^\n]*", "", transaction_body)
    if any(x in transaction_body for x in ("PlayerDependencies", "SharedPreferences", "@Composable")):
        error("ReplayGain transaction must remain a pure domain boundary")
    gateway = (PLAYBACK / "SettingsReplayGainGateway.kt").read_text()
    if "transaction.save(edit)" not in gateway:
        error("ReplayGain actions bypass transactional persistence")
    signal = (ROOT / "app/src/main/java/org/michimusic/mobile/ui/components/SignalTruthReplayGainControls.kt").read_text()
    if "ReplayGainPreferenceModeObserver" not in signal:
        error("Signal Truth lost shared ReplayGain preference observation")
    # One actual ReplayGain writer, not two differently ordered writes.
    if "org.michimusic.mobile.playback.SettingsReplayGainGateway" not in signal:
        error("Signal Truth must import the domain gateway, not depend on UI screens")
    if "SettingsReplayGainGateway.shared(context)" not in signal:
        error("Signal Truth is not using the singleton transaction gateway")
    if "SignalTruthReplayGainCoordinator(" in signal or ".edit().putString(" in signal:
        error("Signal Truth still writes ReplayGain using the legacy independent path")
    if "SettingsReplayGainGateway.shared(context)" not in code("SettingsPlaybackPanel.kt"):
        error("Settings Playback bypasses the shared ReplayGain transaction")
    if "SettingsReplayGainGateway.shared(context)" not in code("SettingsHubLanding.kt"):
        error("Settings hub no longer shares ReplayGain preference observation")
    legacy_policy = (PLAYBACK / "SettingsReplayGainPolicy.kt").read_text()
    if "raw: Any?" not in legacy_policy or "is Int" not in legacy_policy:
        error("legacy integer ReplayGain mode compatibility was lost")
    if "prefs.all[\"replaygain_mode\"]" not in gateway:
        error("gateway may throw on a legacy Int replaygain_mode preference")
    details = code("SettingsLibraryDetails.kt")
    controller = code("SettingsLibraryPanel.kt")
    if len(controller.splitlines()) > 320 or len(details.splitlines()) > 260:
        error("Settings Library split regressed to an oversized controller/view")
    for forbidden in ("musicRootAuthority.", "rememberLauncherForActivityResult",
            "scope.launch", "withContext(", "WorkManager"):
        if forbidden in details:
            error(f"Library presentation layer owns side effect {forbidden}")
    scheduler = (SYNC / "AutoSyncScheduler.kt").read_text()
    for required in ("cancelAll(devices) // No usable peer", "cancelAll(devices) // No signing identity",
        "shouldPrunePeriodicSync", "refreshMutex.withLock"):
        if required not in scheduler:
            error(f"stale periodic work handling missing {required}")
    # Legacy coordinator is retained for source-compatible regression tests only.
    # No production Kotlin file may instantiate the historical independent writer.
    source_root = ROOT / "app/src/main/java"
    legacy_path = source_root / "org/michimusic/mobile/playback/SignalTruthReplayGainCoordinator.kt"
    if legacy_path.exists() and "@Deprecated" not in legacy_path.read_text():
        error("legacy ReplayGain coordinator is not explicitly retired")
    for source_path in source_root.rglob("*.kt"):
        if source_path == legacy_path:
            continue
        source = source_path.read_text(encoding="utf-8")
        source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
        source = re.sub(r"//[^\n]*", "", source)
        if "SignalTruthReplayGainCoordinator(" in source:
            error(f"legacy ReplayGain writer reactivated: {source_path.relative_to(ROOT)}")
    sync_source = (SYNC / "AutoSyncScheduler.kt").read_text()
    if "samePeriodicSyncPeer(" not in sync_source or "enqueueUniquePeriodicWork(" not in sync_source:
        error("sync scheduler lost the post-await peer authority gate")
    if ".result.get()" not in sync_source:
        error("sync scheduler no longer waits for WorkManager operation completion")
    system = code("SettingsSystemPanel.kt")
    if "settingsWizardReminders(" not in system:
        error("Wizard reminder state no longer uses the pure policy")

    # Settings keeps per-destination scroll/search state and a single Back contract.
    for required in ("rememberSaveableStateHolder()", "SaveableStateProvider(currentSection.name)",
            "verticalScroll(rememberScrollState())", "BackHandler", "settings_back_button"):
        if required not in screen:
            error(f"Settings destination state contract missing {required}")
    hub = code("SettingsHubIndex.kt")
    for required in ("rememberSaveable", "settings_search_input"):
        if required not in hub:
            error(f"Settings search lost its saved local state: {required}")

    # Genres: the screen consumes the ViewModel; only the ViewModel/repository owns Room.
    albums = code("AlbumsScreen.kt")
    for forbidden in ("TrackDao", "AppDao", "observeGenreEvidenceSnapshot",
            "buildGenreScopedIndex", "GenreLibraryRepository(", "MichiDatabase.getInstance",
            "Room."):
        if forbidden in albums:
            error(f"AlbumsScreen reclaimed genre IO from its owner: {forbidden}")
    if "GenreLibraryViewModel" not in albums:
        error("AlbumsScreen no longer delegates genre indexing to GenreLibraryViewModel")
    genre_vm = (ROOT / "app/src/main/java/org/michimusic/mobile/screens/GenreLibraryViewModel.kt").read_text()
    if len(genre_vm.splitlines()) > 160:
        error("GenreLibraryViewModel grew towards a monolith")
    for required in ("viewModelScope", "collectLatest", "generation",
            "GenreIndexLoad.Ready(", "fun clear()", "Dispatchers.Default"):
        if required not in genre_vm:
            error(f"genre scope/isolation contract missing {required}")
    if not re.search(r"requestGeneration\s*!=\s*generation", genre_vm):
        error("genre results no longer reject stale generations")

    # Playlists: one balanced row, with the compact policy in the shared visual policy.
    actions = code("PlaylistPrimaryActions.kt")
    if len(actions.splitlines()) > 90:
        error("PlaylistPrimaryActions regressed into a larger surface")
    if actions.count("Row(") != 1 or "Column(" in actions:
        error("Playlist actions must stay in exactly one row")
    for required in ("playlistPrimaryActionWeights(", "weight(playWeight)",
            "weight(shuffleWeight)", "spacedBy(10.dp)",
            "playlistPrimaryActionsCompact(", "playlist_detail_play_all",
            "playlist_detail_shuffle_all", "Reproducir playlist", "Mezclar playlist"):
        if required not in actions:
            error(f"Playlist action row contract missing {required}")
    visual = code("PlaylistVisualPolicy.kt")
    if "playlistPrimaryActionsCompact(" not in visual:
        error("compact playlist action policy disappeared")
    if "playlistPrimaryActionWeights(" not in visual:
        error("playlist action weight policy disappeared")
    for weight in ("0.62f", "0.38f", "0.56f", "0.44f"):
        if weight not in visual:
            error(f"playlist action weight contract missing {weight}")
    print("SETTINGS ARCHITECTURE PASS:", len(screen.splitlines()),
          "root lines,", len(ACTUAL_PANELS), "domain panels, no forbidden root dependencies")
    print("GENRES/PLAYLISTS ARCHITECTURE PASS: single genre owner, one playlist row")


if __name__ == "__main__":
    run()
