package org.michimusic.mobile.ui.components

/** Legacy entrypoint kept for callers; delegates to the canonical time policy. */
fun trackDurationLabel(durationMillis: Long): String = mediaDurationLabel(durationMillis)
