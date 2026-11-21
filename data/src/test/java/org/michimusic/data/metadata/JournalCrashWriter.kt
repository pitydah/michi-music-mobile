package org.michimusic.data.metadata

import java.io.File
import kotlinx.coroutines.runBlocking
import org.michimusic.core.metadata.MetadataRecoveryPhase

/**
 * ME-11R restart witness. Publishes one durable journal record and then halts the JVM
 * immediately, without unwinding scopes or closing anything. A fresh instance in the
 * parent JVM must still decode exactly that record, which is what "recovery after a
 * restart" means for this journal.
 */
object JournalCrashWriter {
    const val ID = "c0ffee00-1111-4222-8333-444455556666"
    const val SOURCE_ID = "crash-source"
    const val TRACK_ID = "crash-track"
    const val URI = "content://media/external/audio/media/4242"
    const val BYTES = 512L
    const val MODIFIED_AT = 4242L

    @JvmStatic
    fun main(args: Array<String>) {
        val directory = File(args[0])
        runBlocking {
            val journal = MetadataRecoveryJournalV1(directory)
            journal.exclusive {
                journal.start(
                    MetadataRecoveryJournalV1.Entry(
                        ID,
                        MetadataRecoveryPhase.SNAPSHOTTING,
                        SOURCE_ID,
                        TRACK_ID,
                        URI,
                        BYTES,
                        MODIFIED_AT,
                    )
                )
            }
        }
        Runtime.getRuntime().halt(0)
    }
}
