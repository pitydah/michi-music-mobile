package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRawTagInventoryTest {
    private fun snapshot(raw:Map<String,List<String>>)=MetadataSnapshot(
        MetadataTarget("","x","content://media/x","verified"),true,emptyMap(),opaque=raw)
    @Test fun preservesLiteralSemicolonAsOneValue() {
        val inventory=MetadataRawTagInventory.inspect(snapshot(mapOf("CUSTOM" to listOf("Jazz; Fusion"))))
        assertEquals(listOf("Jazz; Fusion"),inventory.items.single().values)
        assertEquals(1,inventory.items.single().totalValues)
    }
    @Test fun truncatesLargeRawPayloadForMobile() {
        val inventory=MetadataRawTagInventory.inspect(snapshot(mapOf("BIG" to listOf("A".repeat(4000)))))
        assertTrue(inventory.items.single().values.single().length <= 181)
        assertTrue(inventory.items.single().abbreviated)
    }
    @Test fun protectsAgainstTooManyOpaqueKeys() {
        val input=(0..100).associate { "K$it" to listOf(it.toString()) }
        val inventory=MetadataRawTagInventory.inspect(snapshot(input))
        assertEquals(48,inventory.items.size)
        assertEquals(101,inventory.totalKeys)
        assertTrue(inventory.hasUnshownKeys)
    }
    @Test fun neverGrantsEditingForOpaqueTags() {
        assertFalse(MetadataRawTagInventory.inspect(snapshot(emptyMap())).editingCertified)
    }
    @Test fun preservesOrderedDuplicatesInCountAndDisplay() {
        val inventory=MetadataRawTagInventory.inspect(snapshot(mapOf("X" to
            listOf("x","x","y","x","z"))))
        assertEquals(listOf("x","x","y","x"),inventory.items.single().values)
        assertEquals(5,inventory.items.single().totalValues)
    }
    @Test fun replacesDangerousNulFromDisplayOnly() {
        val inventory=MetadataRawTagInventory.inspect(snapshot(mapOf("X\u0000" to listOf("a\u0000b"))))
        assertFalse(inventory.items.single().key.contains('\u0000'))
        assertFalse(inventory.items.single().values.single().contains('\u0000'))
    }
}
