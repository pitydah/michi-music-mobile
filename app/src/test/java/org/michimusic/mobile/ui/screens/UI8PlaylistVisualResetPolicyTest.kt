package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UI8PlaylistVisualResetPolicyTest {
    private fun needed(
        cover: Boolean = false, banner: Boolean = false,
        cx: Float = .5f, cy: Float = .5f, cs: Float = 1f,
        bx: Float = .5f, by: Float = .5f, bs: Float = 1f,
    ) = playlistVisualResetNeeded(cover, banner, cx, cy, cs, bx, by, bs)

    @Test fun cleanEditorDisablesReset() { assertFalse(needed()) }
    @Test fun existingOrNewImagesEnableReset() { assertTrue(needed(cover = true)); assertTrue(needed(banner = true)) }
    @Test fun orphanCoverCropCanBeResetEvenWithoutCoverArt() {
        assertTrue(needed(cx = .9f)); assertTrue(needed(cy = .1f)); assertTrue(needed(cs = 2.5f))
    }
    @Test fun orphanBannerCropCanBeResetEvenWithoutBannerArt() {
        assertTrue(needed(bx = .9f)); assertTrue(needed(by = .1f)); assertTrue(needed(bs = 1.2f))
    }
    @Test fun resetValuesDisableControlAfterStagingRemoval() { assertFalse(needed()) }
}
