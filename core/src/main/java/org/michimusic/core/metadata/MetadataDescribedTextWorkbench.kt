package org.michimusic.core.metadata

/**
 * ME-10E: deliberate, per-entry text editing. Never collapses multilingual COMM/USLT
 * frames into a plain COMMENT/LYRICS field. This is a review contract, not a writer.
 */
object MetadataDescribedTextWorkbench {
    data class Identity(
        val kind: MetadataId3DescribedTagCodec.Kind,
        val language: String,
        val description: String,
    )
    data class Item(val identity: Identity, val text: String)
    sealed interface Change {
        val identity: Identity
        data class Replace(override val identity: Identity, val text: String) : Change
        data class Clear(override val identity: Identity) : Change
    }
    data class Difference(val identity: Identity, val before: String?, val after: String?)
    enum class Reason {
        UNVERIFIED_SOURCE, INVALID_IDENTITY, DUPLICATE_ORIGINAL, DUPLICATE_OPERATION,
        UNKNOWN_ENTRY, AMBIGUOUS_CREATE, INVALID_TEXT, TOO_MANY_ENTRIES, TOO_MANY_OPERATIONS
    }
    sealed interface Result {
        data class Ready(val values: List<Item>, val differences: List<Difference>) : Result
        data class Blocked(val reason: Reason) : Result
    }

    fun review(verifiedSource: Boolean, original: List<Item>, changes: List<Change>): Result {
        if (!verifiedSource) return Result.Blocked(Reason.UNVERIFIED_SOURCE)
        if (original.size > 64) return Result.Blocked(Reason.TOO_MANY_ENTRIES)
        if (changes.size > 64) return Result.Blocked(Reason.TOO_MANY_OPERATIONS)
        if (original.any { !validIdentity(it.identity) || !validText(it.identity, it.text) })
            return Result.Blocked(Reason.INVALID_IDENTITY)
        if (original.map { it.identity }.distinct().size != original.size)
            return Result.Blocked(Reason.DUPLICATE_ORIGINAL)
        if (changes.map { it.identity }.distinct().size != changes.size)
            return Result.Blocked(Reason.DUPLICATE_OPERATION)
        if (changes.any { !validIdentity(it.identity) }) return Result.Blocked(Reason.INVALID_IDENTITY)
        val existing = original.associateBy { it.identity }
        val values = LinkedHashMap<Identity, Item>()
        original.forEach { values[it.identity] = it }
        val diffs = mutableListOf<Difference>()
        for (change in changes) {
            val before = existing[change.identity]?.text
                ?: return Result.Blocked(Reason.UNKNOWN_ENTRY)
            val after = when (change) {
                is Change.Replace -> {
                    if (!validText(change.identity, change.text))
                        return Result.Blocked(Reason.INVALID_TEXT)
                    change.text
                }
                is Change.Clear -> null
            }
            if (after == before) continue
            if (after == null) values.remove(change.identity)
            else values[change.identity] = Item(change.identity, after)
            diffs += Difference(change.identity, before, after)
        }
        return Result.Ready(values.values.toList(), diffs)
    }

    /** Creating an unobserved descriptor requires a separate, certified creation flow. */
    fun proposeNew(identity: Identity, text: String): Result =
        if (!validIdentity(identity)) Result.Blocked(Reason.INVALID_IDENTITY)
        else if (!validText(identity, text)) Result.Blocked(Reason.INVALID_TEXT)
        else Result.Blocked(Reason.AMBIGUOUS_CREATE)

    private fun validIdentity(id: Identity): Boolean =
        id.language.matches(Regex("[a-z]{3}")) && id.description.length <= 128 &&
            id.description.none { it.isISOControl() } && '\ufffd' !in id.description

    private fun validText(id: Identity, value: String): Boolean =
        value.isNotBlank() && value.length <= id.kind.maxChars &&
            '\u0000' !in value && '\ufffd' !in value &&
            value.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
}
