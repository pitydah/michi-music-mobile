package org.michimusic.mobile.ui.seeker

import org.michimusic.mobile.ui.theme.AeroSeekerStyle

/** Presentation-only guard. Never creates or retains audio samples. */
internal fun waveformBarsPreviewVisible(
    style: AeroSeekerStyle,
    ownerMatches: Boolean,
    settled: Boolean,
): Boolean = ownerMatches && !settled && style == AeroSeekerStyle.WAVEFORM_BARS

/** Labels clarify state; none implies that an unmeasured window is silent. */
internal fun waveformLoadingDescription(hasMeasuredPreview: Boolean): String =
    if (hasMeasuredPreview) "Analizando forma de onda" else "Preparando forma de onda"
