package org.michimusic.mobile.ui.screens

import org.michimusic.mobile.ui.theme.AeroSeekerStyle

/** Fixed DEMONSTRATION amplitudes; never audio measurements. One immutable
 * small allocation for the whole process, not a new array per composition.
 */
private val DEMO_LEVELS: List<Float> = listOf(
    .08f, .17f, .29f, .47f, .68f, .52f, .22f, .12f,
    .36f, .55f, .90f, .60f, .27f, .16f, .24f, .46f,
    .78f, .96f, .69f, .35f, .18f, .42f, .71f, .59f,
    .33f, .21f, .48f, .82f, .62f, .39f, .19f, .10f,
)

internal fun seekerPreviewSamples(): List<Float> = DEMO_LEVELS
internal fun seekerPreviewUsesExample(style: AeroSeekerStyle): Boolean =
    style == AeroSeekerStyle.WAVEFORM_BARS ||
        style == AeroSeekerStyle.WAVEFORM_CONTINUOUS
