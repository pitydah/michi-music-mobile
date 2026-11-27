package org.michimusic.data.metadata

/**
 * ME-13: pure decision policy for a post-write readback of the committed candidate.
 *
 * MediaStore publishes `SIZE` and `DATE_MODIFIED` through its own bookkeeping. The
 * provider often applies the new size only after the writing descriptor is closed,
 * so the announced stamp can move *while* a readback is streaming the already
 * committed bytes. That is a provider bookkeeping race, not a source mutation, and
 * the contract (T26, P0-B.6) only allows ignoring the announced size when the
 * physical bytes and the full SHA-256 are exactly the expected candidate.
 *
 * This policy therefore makes CONTENT the authority:
 * 1. a wrong physical length or SHA is always a refusal, whatever the stamps say;
 * 2. exact content with a settled stamp is accepted and reports announced lag;
 * 3. exact content with a stamp that moved during the read is re-proved by exactly
 *    one fresh, independent read; a persistently moving stamp fails closed.
 *
 * No sleep, no tolerance window and no inferred metadata: only exact bytes.
 */
internal object MetadataCommittedCandidateStability {
    /** Opaque provider announcement; `modifiedAt` keeps MediaStore's own units. */
    data class Announcement(val bytes: Long, val modifiedAt: Long)

    enum class Refusal { ACTUAL_SIZE_MISMATCH, SHA_MISMATCH }

    /** Result of one full attempt to stream the source bytes. */
    sealed interface BytesRead {
        data class Read(val bytes: Long, val sha256: String) : BytesRead
        data class Rejected(val reason: String) : BytesRead
    }

    /** One bracketed observation: announced stamp → full read → announced stamp. */
    data class Observation(
        val before: Announcement?,
        val read: BytesRead,
        val after: Announcement?,
    )

    sealed interface Verdict {
        /** Physical content is exactly the expected candidate. */
        data class Exact(
            val announced: Announcement,
            /** The announced size still disagrees with the physical bytes we read. */
            val announcedLag: Boolean,
        ) : Verdict

        /** Content is exact but the announcement moved; one fresh read decides. */
        data object AnnouncementMoved : Verdict

        data class Refused(val refusal: Refusal) : Verdict
    }

    sealed interface Outcome {
        data class Accepted(
            val bytes: Long,
            val sha256: String,
            val announced: Announcement,
            val announcedLag: Boolean,
            /** True when the accepted read needed the one permitted retry. */
            val retried: Boolean,
        ) : Outcome

        data class Rejected(val reason: String) : Outcome
    }

    /**
     * One retry only: a single post-close bookkeeping update is expected; a
     * persistently moving announcement is a concurrent-modification signal.
     */
    const val MAX_ATTEMPTS = 2

    fun assess(
        before: Announcement,
        after: Announcement,
        physicalBytes: Long,
        physicalSha256: String,
        expectedBytes: Long,
        expectedSha256: String,
    ): Verdict {
        if (physicalBytes != expectedBytes) return Verdict.Refused(Refusal.ACTUAL_SIZE_MISMATCH)
        if (!physicalSha256.equals(expectedSha256, ignoreCase = true))
            return Verdict.Refused(Refusal.SHA_MISMATCH)
        if (before != after) return Verdict.AnnouncementMoved
        return Verdict.Exact(announced = after, announcedLag = after.bytes != physicalBytes)
    }

    /**
     * Resolves a committed readback. [observe] must perform a complete, independent
     * attempt; it is called at most [MAX_ATTEMPTS] times.
     */
    fun resolve(
        expectedBytes: Long,
        expectedSha256: String,
        observe: () -> Observation,
    ): Outcome {
        repeat(MAX_ATTEMPTS) { attempt ->
            val observation = observe()
            val before = observation.before
                ?: return Outcome.Rejected("MISSING_STAMP")
            val read = observation.read
            if (read is BytesRead.Rejected) return Outcome.Rejected(read.reason)
            read as BytesRead.Read
            val after = observation.after
                ?: return Outcome.Rejected("MISSING_STAMP")
            when (val verdict = assess(
                before = before, after = after,
                physicalBytes = read.bytes, physicalSha256 = read.sha256,
                expectedBytes = expectedBytes, expectedSha256 = expectedSha256,
            )) {
                is Verdict.Exact -> return Outcome.Accepted(
                    read.bytes, read.sha256, verdict.announced, verdict.announcedLag,
                    retried = attempt > 0,
                )
                is Verdict.Refused -> return Outcome.Rejected(verdict.refusal.name)
                Verdict.AnnouncementMoved -> Unit
            }
        }
        return Outcome.Rejected("STAMP_CHANGED_DURING_READ")
    }
}
