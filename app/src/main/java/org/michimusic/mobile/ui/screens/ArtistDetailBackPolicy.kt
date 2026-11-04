package org.michimusic.mobile.ui.screens

/** Explicit, testable back-stack behavior for the full-screen artist route. */
internal enum class ArtistDetailBackDestination { OVERVIEW, LIBRARY }

internal fun artistDetailBackDestination(enrichmentVisible: Boolean): ArtistDetailBackDestination =
    if (enrichmentVisible) ArtistDetailBackDestination.OVERVIEW
    else ArtistDetailBackDestination.LIBRARY
