package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class ArtistArtworkStagePolicyTest {
    private fun run(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { failure = result.exceptionOrNull() }
        })
        failure?.let { throw it }
    }

    @Test fun bothSlotsReadyAtP18NeverOpenP180OrWikipedia() = run {
        val queried = mutableListOf<String>()
        var missing = true
        ArtistArtworkStagePolicy.visitUntilComplete(listOf("P18", "P180", "Wikipedia", "P373", "P935"),
            hasMissing = { missing }) { provider ->
            queried += provider
            if (provider == "P18") missing = false
        }
        assertEquals(listOf("P18"), queried)
    }

    @Test fun missingHeroContinuesOnlyUntilAnotherStageFillsIt() = run {
        val queried = mutableListOf<String>()
        var missing = true
        ArtistArtworkStagePolicy.visitUntilComplete(listOf("P18", "P180", "Wikipedia", "P373"),
            hasMissing = { missing }) { provider ->
            queried += provider
            if (provider == "P180") missing = false
        }
        assertEquals(listOf("P18", "P180"), queried)
    }

    @Test fun exhaustedSourcesAreBoundedAndNoEmptyCandidateIsDownloaded() = run {
        var count = 0
        ArtistArtworkStagePolicy.visitUntilComplete((1..5).toList(), { true }) { count++ }
        assertEquals(5, count)
        assertFalse(ArtistArtworkStagePolicy.canTry(emptySet(), ""))
    }

    @Test fun failedEarlyProviderDoesNotStarveVerifiedFallback() {
        val all = linkedSetOf("p18-1", "p18-2", "p18-3")
        val stage = linkedSetOf<String>()
        assertTrue(ArtistArtworkStagePolicy.canTryAcrossStages(all, stage, "p180-1"))
        stage.add("p180-1")
        all.add("p180-1")
        assertFalse(ArtistArtworkStagePolicy.canTryAcrossStages(all, stage, "p180-1"))
        assertTrue(ArtistArtworkStagePolicy.canTryAcrossStages(all, stage, "wikipedia-1"))
    }

    @Test fun stageAndGlobalAttemptCapsAreIndependent() {
        val all = (1..6).map { "old-$it" }.toMutableSet()
        val stage = (1..3).map { "new-$it" }.toMutableSet()
        assertFalse(ArtistArtworkStagePolicy.canTryAcrossStages(all, stage, "new-4"))
        stage.clear()
        assertTrue(ArtistArtworkStagePolicy.canTryAcrossStages(all, stage, "new-4"))
        all.addAll((7..9).map { "old-$it" })
        assertFalse(ArtistArtworkStagePolicy.canTryAcrossStages(all, stage, "new-4"))
    }

    @Test fun eachSlotGetsAtMostThreeDistinctAttempts() {
        val seen = mutableSetOf<String>()
        for (id in listOf("a", "a", "b", "c", "d")) {
            if (ArtistArtworkStagePolicy.canTry(seen, id)) seen += id
        }
        assertEquals(setOf("a", "b", "c"), seen)
        assertFalse(ArtistArtworkStagePolicy.canTry(seen, "d"))
        assertTrue(ArtistArtworkStagePolicy.canTry(emptySet(), "asset"))
    }
}
