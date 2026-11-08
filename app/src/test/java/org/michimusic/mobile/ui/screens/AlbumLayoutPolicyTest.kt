package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumLayoutPolicyTest {
    @Test fun phoneWidthsKeepOneCompactActionRowAtNormalAndModerateFonts() {
        listOf(320, 360, 412).forEach { width ->
            listOf(1.0f, 1.3f).forEach { scale ->
                assertEquals(AlbumActionLayout.SINGLE_ROW, albumActionLayout(width, scale))
            }
        }
    }

    @Test fun veryLargeOrInvalidFontsUseAtMostTwoRows() {
        listOf(320, 360, 412, 600).forEach { width ->
            listOf(1.5f, 2.0f, Float.NaN).forEach { scale ->
                assertEquals(AlbumActionLayout.TWO_ROWS, albumActionLayout(width, scale))
            }
        }
    }

    @Test fun compactTwoHundredPercentHeroStacksToProtectTheFullTitleWidth() {
        assertEquals(AlbumHeroLayout.STACKED, albumHeroLayout(360, 2.0f))
        assertEquals(5, albumHeroTitleMaxLines(AlbumHeroLayout.STACKED))
    }

    @Test fun normalPhoneAndWideLargeTextKeepTheProportionalHorizontalHero() {
        assertEquals(AlbumHeroLayout.HORIZONTAL, albumHeroLayout(360, 1.5f))
        assertEquals(AlbumHeroLayout.HORIZONTAL, albumHeroLayout(600, 2.0f))
        assertEquals(3, albumHeroTitleMaxLines(AlbumHeroLayout.HORIZONTAL))
    }
}
