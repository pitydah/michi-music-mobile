#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def add_imports(rel: str, imports: list[str]) -> None:
    text = read(rel)
    missing = [item for item in imports if f"import {item}" not in text]
    if not missing:
        return
    lines = text.splitlines()
    package_index = next(i for i, line in enumerate(lines) if line.startswith("package "))
    insertion = package_index + 1
    lines[insertion:insertion] = [""] + [f"import {item}" for item in missing]
    write(rel, "\n".join(lines) + ("\n" if text.endswith("\n") else ""))


def replace(rel: str, old: str, new: str, required: bool = True) -> None:
    text = read(rel)
    if old not in text:
        if required:
            raise RuntimeError(f"No se encontró el bloque esperado en {rel}: {old[:100]!r}")
        return
    write(rel, text.replace(old, new))


# Coil 3.1 compatibility. This changes only cache/image-loader wiring, not UI composition.
michi_app = "app/src/main/java/org/michimusic/mobile/MichiApp.kt"
add_imports(michi_app, ["okio.Path.Companion.toOkioPath"])
replace(
    michi_app,
    '''    private fun configureImageLoader() {
        val imageLoader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
        SingletonImageLoader.setSafe(this) { imageLoader }
    }
''',
    '''    private fun configureImageLoader() {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("coil").toOkioPath())
                        .maxSizeBytes(50L * 1024 * 1024)
                        .build()
                }
                .crossfade(true)
                .build()
        }
    }
''',
)

# Navigation contract after NowPlayingScreen refactor.
replace(
    "app/src/main/java/org/michimusic/mobile/navigation/MichiNavHost.kt",
    '''                composable("nowplaying") {
                    NowPlayingScreen(
                        onNavigateToSettings = { navController.navigate("settings") },
                        onNavigateToAudioRoute = { navController.navigate("audio-route") },
                    )
                }
''',
    '''                composable("nowplaying") {
                    NowPlayingScreen(onBack = { navController.popBackStack() })
                }
''',
)

# Suspend refresh must run in the ViewModel scope.
replace(
    "app/src/main/java/org/michimusic/mobile/remote/RemoteViewModel.kt",
    "        refreshState()\n        startPollingFallback(linkClient)",
    "        viewModelScope.launch { refreshState() }\n        startPollingFallback(linkClient)",
)

# WorkManager's Flow emits nullable WorkInfo.
replace(
    "app/src/main/java/org/michimusic/mobile/sync/SyncViewModel.kt",
    '''                .collect { info ->
                    val progress = info.progress
''',
    '''                .collect { info ->
                    if (info == null) return@collect
                    val progress = info.progress
''',
)

# Premium UI file lost its Compose imports during refactoring.
add_imports(
    "app/src/main/java/org/michimusic/mobile/ui/components/PremiumUi.kt",
    [
        "androidx.compose.foundation.background",
        "androidx.compose.foundation.border",
        "androidx.compose.foundation.clickable",
        "androidx.compose.foundation.layout.Arrangement",
        "androidx.compose.foundation.layout.BoxScope",
        "androidx.compose.material.icons.Icons",
        "androidx.compose.material.icons.rounded.GraphicEq",
        "androidx.compose.material.icons.rounded.MusicNote",
        "androidx.compose.material3.Button",
        "androidx.compose.material3.ButtonDefaults",
        "androidx.compose.material3.CircularProgressIndicator",
        "androidx.compose.material3.Icon",
        "androidx.compose.material3.IconButton",
        "androidx.compose.material3.IconButtonDefaults",
        "androidx.compose.ui.draw.shadow",
        "androidx.compose.ui.graphics.Path",
        "androidx.compose.ui.graphics.drawscope.Stroke",
        "androidx.compose.ui.graphics.vector.ImageVector",
        "androidx.compose.ui.res.painterResource",
        "androidx.compose.ui.unit.Dp",
    ],
)

# PremiumScreen exposes a BoxScope receiver; these screens omitted the import.
for screen in [
    "AlbumsScreen.kt",
    "AudioRouteScreen.kt",
    "DiagnosticsScreen.kt",
    "HomeScreen.kt",
    "QueueScreen.kt",
    "SearchScreen.kt",
    "SettingsScreen.kt",
    "SyncedTracksScreen.kt",
]:
    add_imports(
        f"app/src/main/java/org/michimusic/mobile/ui/screens/{screen}",
        ["androidx.compose.foundation.layout.BoxScope"],
    )

# Retry action uses the existing premium button contract.
add_imports(
    "app/src/main/java/org/michimusic/mobile/ui/screens/HomeScreen.kt",
    ["androidx.compose.material.icons.rounded.Refresh"],
)
replace(
    "app/src/main/java/org/michimusic/mobile/ui/screens/HomeScreen.kt",
    '''                PremiumButton(
                    text = "Reintentar",
                    onClick = { viewModel.loadMedia(); viewModel.clearError() },
''',
    '''                PremiumButton(
                    text = "Reintentar",
                    icon = Icons.Rounded.Refresh,
                    onClick = { viewModel.loadMedia(); viewModel.clearError() },
''',
)

# StateFlow collection import was omitted; the remaining PlayerState errors are cascading.
add_imports(
    "app/src/main/java/org/michimusic/mobile/ui/screens/NowPlayingScreen.kt",
    ["androidx.compose.runtime.collectAsState"],
)

# Sync screen contracts after SyncUiState refactor.
add_imports(
    "app/src/main/java/org/michimusic/mobile/ui/screens/SyncScreen.kt",
    ["androidx.compose.foundation.layout.weight"],
)
replace(
    "app/src/main/java/org/michimusic/mobile/ui/screens/SyncScreen.kt",
    "                        name = uiState.sourceName,",
    "                        name = uiState.connectedPeer?.alias ?: \"Servidor Michi\",",
)
replace(
    "app/src/main/java/org/michimusic/mobile/ui/screens/SyncScreen.kt",
    "                        onRetry = { viewModel.retry() },",
    "                        onRetry = { viewModel.disconnect(); viewModel.startDiscovery() },",
)

# Now-playing component imports/API changes in current Compose and Coil.
now_components = "app/src/main/java/org/michimusic/mobile/ui/screens/nowplaying/NowPlayingComponents.kt"
add_imports(
    now_components,
    [
        "androidx.compose.runtime.remember",
        "androidx.compose.ui.geometry.Offset",
        "androidx.compose.ui.geometry.Size",
        "androidx.compose.ui.platform.LocalDensity",
        "coil3.request.crossfade",
    ],
)
replace(now_components, "import coil3.request.size\n", "", required=False)
replace(now_components, "                    .size(480)\n", "", required=False)
replace(now_components, "basicMarquee(iterations = 30, delayMillis = 2000)", "basicMarquee(iterations = 30, initialDelayMillis = 2000)")

# Settings component import omitted.
add_imports(
    "app/src/main/java/org/michimusic/mobile/ui/screens/settings/SettingsComponents.kt",
    ["androidx.compose.foundation.layout.Column"],
)

# Sync components use aliases/current field names from the domain model.
sync_components = "app/src/main/java/org/michimusic/mobile/ui/screens/sync/SyncComponents.kt"
add_imports(sync_components, ["androidx.compose.material.icons.filled.Close"])
replace(sync_components, "peer.name", "peer.alias", required=False)
replace(sync_components, "progress.downloaded", "progress.completed", required=False)
replace(
    sync_components,
    '''                    is SyncProgress.Complete -> {
                        Text("${syncProgress.downloaded} canciones sincronizadas", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onSync() }) { Text("Sincronizar de nuevo") }
                    }
''',
    '''                    is SyncProgress.Complete -> {
                        Text("${syncProgress.downloaded} canciones sincronizadas", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onSync() }) { Text("Sincronizar de nuevo") }
                    }
                    is SyncProgress.Error -> {
                        Text(syncProgress.message, style = MaterialTheme.typography.bodyMedium, color = AccentPink)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onSync) { Text("Reintentar") }
                    }
''',
)

print("Temporary runtime compatibility patches applied successfully.")
