package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiHomeCardPreferencePolicyTest {
    private val policy = MichiHomeCardPreferencePolicy
    private val kinds = MichiHomeIntelligencePolicy.CardKind.entries
    private val cards = kinds.map {
        MichiHomeIntelligencePolicy.Card(it, listOf(MichiIntelligencePolicy.TrackKey("local", it.name)),
            MichiIntelligencePolicy.Reason.ARTIST_AFFINITY)
    }

    @Test fun pinMovesExistingCardAheadWithoutInventingNewCards() {
        val result = policy.arrange(cards, mapOf(kinds.last() to MichiHomeCardPreferencePolicy.Choice.PINNED))
        assertEquals(listOf(kinds.last(), kinds[0], kinds[1]), result.map { it.kind })
    }

    @Test fun hiddenCardNeverAppearsInProposalsOrFallback() {
        val result = policy.arrange(cards, mapOf(kinds[0] to MichiHomeCardPreferencePolicy.Choice.HIDDEN))
        assertEquals(listOf(kinds[1], kinds[2], kinds[3]), result.map { it.kind })
        assertTrue(policy.arrange(cards, kinds.associateWith { MichiHomeCardPreferencePolicy.Choice.HIDDEN }).isEmpty())
    }

    @Test fun pinnedCardsMaintainOriginalRelativeOrder() {
        val result = policy.arrange(cards, mapOf(kinds[3] to MichiHomeCardPreferencePolicy.Choice.PINNED,
            kinds[1] to MichiHomeCardPreferencePolicy.Choice.PINNED))
        assertEquals(listOf(kinds[1], kinds[3], kinds[0]), result.map { it.kind })
    }

    @Test fun pinsDoNotBypassEmptyProposal() {
        assertTrue(policy.arrange(emptyList(), mapOf(kinds[1] to MichiHomeCardPreferencePolicy.Choice.PINNED)).isEmpty())
    }

    @Test fun invalidStatesFailToDefaultAndDefaultRequiresNoDatabaseRow() {
        assertEquals(MichiHomeCardPreferencePolicy.Choice.DEFAULT, policy.decode("pin-me-forever"))
        assertEquals(MichiHomeCardPreferencePolicy.Choice.DEFAULT, policy.decode(null))
        assertEquals(MichiHomeCardPreferencePolicy.Choice.PINNED, policy.decode(policy.encode(MichiHomeCardPreferencePolicy.Choice.PINNED)))
        assertEquals(MichiHomeCardPreferencePolicy.Choice.HIDDEN, policy.decode(policy.encode(MichiHomeCardPreferencePolicy.Choice.HIDDEN)))
        assertEquals(null, policy.encode(MichiHomeCardPreferencePolicy.Choice.DEFAULT))
    }

    @Test fun outputCapCannotBeLiftedByPinning() {
        assertEquals(3, policy.arrange(cards, kinds.associateWith { MichiHomeCardPreferencePolicy.Choice.PINNED }, 500).size)
        assertTrue(policy.arrange(cards, emptyMap(), -1).isEmpty())
    }
}
