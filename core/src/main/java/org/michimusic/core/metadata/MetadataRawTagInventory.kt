package org.michimusic.core.metadata

/**
 * ME-11B: bounded read-only inventory of raw/unknown tag evidence for ADVANCED.
 * A raw entry is not proof that a format-specific writer can preserve or edit
 * it. No delimiter split, silent value normalization, network or file IO.
 */
object MetadataRawTagInventory {
    const val MAX_KEYS=48
    const val MAX_VALUES_PER_KEY=4
    const val MAX_DISPLAY_CHARS=180
    data class Item(val key: String,val values: List<String>,
        val totalValues: Int,val abbreviated: Boolean)
    data class Inventory(val items: List<Item>, val totalKeys: Int,
        val hasUnshownKeys: Boolean, val editingCertified: Boolean = false)

    fun inspect(snapshot: MetadataSnapshot): Inventory {
        val keys=snapshot.opaque.entries.sortedBy { it.key }
        val visible=keys.take(MAX_KEYS).map { (key,values) ->
            Item(key=display(key), values=values.take(MAX_VALUES_PER_KEY).map(::display),
                totalValues=values.size,
                abbreviated=values.size > MAX_VALUES_PER_KEY ||
                    values.take(MAX_VALUES_PER_KEY).any { it.length > MAX_DISPLAY_CHARS })
        }
        return Inventory(visible,keys.size,keys.size>MAX_KEYS)
    }
    private fun display(value:String):String {
        val raw=value.take(MAX_DISPLAY_CHARS)
        return raw.map { c -> if (c.code < 32 && c != '\n' && c != '\t') '�' else c }
            .joinToString("") + if (value.length>MAX_DISPLAY_CHARS) "…" else ""
    }
}
