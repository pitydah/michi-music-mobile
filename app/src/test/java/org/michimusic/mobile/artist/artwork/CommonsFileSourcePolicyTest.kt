package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonsFileSourcePolicyTest {
    @Test fun fileTitleAndDescriptionAreBoundToTheExactRequest() {
        assertTrue(CommonsFileSourcePolicy.matchesRequestedFile(
            "Actual_artist.jpg", "File:Actual artist.jpg", "6",
            "https://commons.wikimedia.org/wiki/File:Actual_artist.jpg"))
        assertTrue(CommonsFileSourcePolicy.matchesRequestedFile(
            "Björk.jpg", null, null,
            "https://commons.wikimedia.org/wiki/File:Bj%C3%B6rk.jpg"))
    }

    @Test fun foreignOrContradictoryPagesNeverAuthoriseArtwork() {
        val source = "https://commons.wikimedia.org/wiki/File:Actual_artist.jpg"
        assertFalse(CommonsFileSourcePolicy.matchesRequestedFile("Actual_artist.jpg", "File:Other.jpg", "6", source))
        assertFalse(CommonsFileSourcePolicy.matchesRequestedFile("Actual_artist.jpg", "File:Actual_artist.jpg", "0", source))
        assertFalse(CommonsFileSourcePolicy.matchesRequestedFile("Actual_artist.jpg", null, null, null))
        assertFalse(CommonsFileSourcePolicy.matchesRequestedFile("Actual_artist.jpg", "File:Actual_artist.jpg", "6", "https://commons.wikimedia.org/wiki/File:Other.jpg"))
    }

    @Test fun dangerousOrNonCanonicalSourceUrlsAreRejected() {
        val name = "Actual_artist.jpg"
        for (url in listOf(
            "http://commons.wikimedia.org/wiki/File:Actual_artist.jpg",
            "https://commons.wikimedia.org.evil.example/wiki/File:Actual_artist.jpg",
            "https://commons.wikimedia.org/wiki/File:Actual_artist.jpg?uselang=en",
            "https://commons.wikimedia.org/wiki/File:Actual_artist.jpg#anchor",
            "https://commons.wikimedia.org/wiki/File:Actual_artist.jpg/extra",
            "https://other.example/wiki/File:Actual_artist.jpg",
        )) assertFalse(CommonsFileSourcePolicy.matchesRequestedFile(name, null, null, url))
    }
}
