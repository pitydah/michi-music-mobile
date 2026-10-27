package org.michimusic.mobile.navigation

/**
 * Root-screen identity and chrome presentation are explicit UI policy, not
 * inferred from a flat NavGraph or an active audio session.
 * The argument form returned by Navigation Compose can be a route template.
 */
enum class MichiScreenLevel { PRIMARY, DETAIL, IMMERSIVE }

data class MichiNavigationPresentation(
    val level: MichiScreenLevel,
    val selectedPrimaryTab: String?,
    val showDock: Boolean,
    val showMiniPlayer: Boolean,
)

fun navigationPresentationFor(route: String?): MichiNavigationPresentation {
    val primary = when (route) {
        "home" -> "home"
        "library" -> "library"
        "search" -> "search"
        "devices" -> "devices"
        else -> null
    }
    val immersive = route == "nowplaying" ||
        route == "sync_wizard/{deviceId}" || route?.startsWith("sync_wizard/") == true
    val section = when {
        primary != null -> primary
        route == "settings" || route == "diagnostics" -> "home"
        route == "playlist" || route == "playlist/{playlistId}" ||
            route?.startsWith("playlist/") == true || route == "queue" -> "library"
        route == "synced" || route == "remote" || route == "sync" ||
            route == "audio-route" || route == "sync_wizard/{deviceId}" ||
            route?.startsWith("sync_wizard/") == true -> "devices"
        else -> null
    }
    return MichiNavigationPresentation(
        level = if (immersive) MichiScreenLevel.IMMERSIVE else if (primary != null) MichiScreenLevel.PRIMARY else MichiScreenLevel.DETAIL,
        selectedPrimaryTab = section,
        showDock = !immersive,
        showMiniPlayer = !immersive,
    )
}
