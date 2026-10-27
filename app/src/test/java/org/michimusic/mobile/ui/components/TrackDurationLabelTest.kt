package org.michimusic.mobile.ui.components
import org.junit.Test
import org.junit.Assert.assertEquals
class TrackDurationLabelTest {
 @Test fun unknownIsNotZero(){assertEquals("—",trackDurationLabel(0L));assertEquals("—",trackDurationLabel(-50L))}
 @Test fun standardTrack(){assertEquals("3:07",trackDurationLabel(187_000L))}
 @Test fun hoursAreHandled(){assertEquals("1:01:01",trackDurationLabel(3_661_000L))}
 @Test fun subsecondValuesDoNotPretendToBeUnknown(){assertEquals("0:00",trackDurationLabel(750L))}
 @Test fun giantDurationsDoNotOverflow(){val value=trackDurationLabel(Long.MAX_VALUE);assertEquals(true,value.contains(":"))}
}
