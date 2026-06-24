package org.michimusic.mobile.library.coverflow

object MockData {
    val albums = listOf(
        CoverFlowAlbum("1", "Mad Love", "Mabel", 2019, 14),
        CoverFlowAlbum("2", "Sweetener", "Ariana Grande", 2018, 15),
        CoverFlowAlbum("3", "Solastalgia", "Missy Higgins", 2024, 12),
        CoverFlowAlbum("4", "Youngblood", "5 Seconds of Summer", 2018, 14),
        CoverFlowAlbum("5", "Golden Hour", "Kacey Musgraves", 2018, 13),
    )

    data class MockTrack(
        val title: String,
        val artist: String,
        val duration: Long,
    )

    val tracksByAlbum = mapOf(
        "1" to listOf(
            MockTrack("Mad Love", "Mabel", 234_000),
            MockTrack("Don't Call Me Up", "Mabel", 198_000),
            MockTrack("FML", "Mabel", 212_000),
            MockTrack("Bad Behaviour", "Mabel", 245_000),
            MockTrack("Selfish Love", "Mabel", 187_000),
            MockTrack("OK (Anxiety Anthem)", "Mabel", 221_000),
        ),
        "2" to listOf(
            MockTrack("raindrops", "Ariana Grande", 217_000),
            MockTrack("God is a woman", "Ariana Grande", 197_000),
            MockTrack("sweetener", "Ariana Grande", 207_000),
            MockTrack("successful", "Ariana Grande", 227_000),
            MockTrack("everytime", "Ariana Grande", 232_000),
            MockTrack("breathin", "Ariana Grande", 196_000),
            MockTrack("no tears left to cry", "Ariana Grande", 206_000),
        ),
        "3" to listOf(
            MockTrack("Solastalgia", "Missy Higgins", 242_000),
            MockTrack("The Difference", "Missy Higgins", 218_000),
            MockTrack("Eleven", "Missy Higgins", 205_000),
            MockTrack("Tiebreaker", "Missy Higgins", 231_000),
            MockTrack("Too Much", "Missy Higgins", 193_000),
        ),
        "4" to listOf(
            MockTrack("Youngblood", "5 Seconds of Summer", 203_000),
            MockTrack("Want You Back", "5 Seconds of Summer", 192_000),
            MockTrack("Lie to Me", "5 Seconds of Summer", 217_000),
            MockTrack("Valentine", "5 Seconds of Summer", 196_000),
            MockTrack("Talk Fast", "5 Seconds of Summer", 184_000),
            MockTrack("Moving Along", "5 Seconds of Summer", 207_000),
        ),
        "5" to listOf(
            MockTrack("Slow Burn", "Kacey Musgraves", 246_000),
            MockTrack("Lonely Weekend", "Kacey Musgraves", 192_000),
            MockTrack("Butterflies", "Kacey Musgraves", 211_000),
            MockTrack("Oh, What a World", "Kacey Musgraves", 227_000),
            MockTrack("Mother", "Kacey Musgraves", 106_000),
            MockTrack("Love Is a Wild Thing", "Kacey Musgraves", 194_000),
        ),
    )
}
