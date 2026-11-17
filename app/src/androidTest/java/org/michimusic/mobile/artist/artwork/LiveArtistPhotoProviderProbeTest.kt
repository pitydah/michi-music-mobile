package org.michimusic.mobile.artist.artwork

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in probe that exercises the REAL automatic provider ladder: real Wikidata, real
 * Wikimedia Commons, real HTTP, real download and real persistence through
 * [ArtistArtworkService]. It is not a release benchmark and it never runs in CI: every
 * test here skips unless the runner passes `-e michiLiveProviders true`.
 *
 * Why this exists: the provider *policies* (QID gate, licence consistency, continuation,
 * ranking) are unit tested, but the policies alone never prove that the ladder brings a
 * real photograph for a real artist. That gap is what this probe closes.
 */
@RunWith(AndroidJUnit4::class)
class LiveArtistPhotoProviderProbeTest {

    private val arguments: android.os.Bundle get() = InstrumentationRegistry.getArguments()
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Midnight Oil: MusicBrainz artist MBID + Wikidata QID, both real identifiers. */
    private val midnightOilMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val midnightOilQid = "Q48284"

    /**
     * Every run gets its own anchor key. The store is keyed by library + credit + album
     * group (not by QID, correctly: identity evidence is not part of the slot identity),
     * so reusing a key would turn the second run into a cache read and the ladder would
     * never be consulted again. A fresh key keeps this probe an honest network measurement.
     */
    private fun liveAnchor(qid: String?, suffix: String): ArtistIdentityAnchor {
        val run = arguments.getString("michiProbeRun") ?: "r1"
        return ArtistIdentityAnchor(
            libraryId = "lib_live_probe_$run$suffix",
            creditKey = "midnight oil",
            albumGroupKey = "live|midnight oil|$run$suffix",
            confirmedMbid = midnightOilMbid,
            verifiedQid = qid,
            displayCredit = "Midnight Oil",
        )
    }

    private fun evidenceDir(): File =
        File(context.getExternalFilesDir(null), "r12-live-providers").apply { mkdirs() }

    private fun write(file: String, text: String) {
        File(evidenceDir(), file).writeText(text)
        Log.i(ProbeTag, "wrote $file (${text.length} chars)")
    }

    @Test
    fun theAutomaticLadderBringsRealPhotosForAVerifiedArtist() {
        assumeTrue(
            "live provider probe is opt-in: pass -e michiLiveProviders true",
            arguments.getString("michiLiveProviders") == "true",
        )
        val anchor = liveAnchor(midnightOilQid, suffix = "-qid")
        val coordinator = ArtistArtworkService.from(context)

        val startedAt = SystemClock.elapsedRealtime()
        val report = runBlocking { coordinator.resolve(anchor) }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        val outcomes = report.outcomes.joinToString("\n") { outcome ->
            outcome.source.name + "\t" + outcome.status.name + "\t" +
                (outcome.reason ?: "") + "\t" + outcome.candidateCount
        }
        write(
            "ladder-midnight-oil.tsv",
            "providers\t${report.outcomes.size}\telapsed_ms\t$elapsedMs\n" +
                "from_cache\t${report.fromCache}\n" +
                "message\t${report.message ?: ""}\n" +
                "source\tstatus\treason\tcandidates\n" + outcomes + "\n",
        )
        report.outcomes.forEach { outcome ->
            Log.i(
                ProbeTag,
                "provider=${outcome.source.name} status=${outcome.status.name} " +
                    "candidates=${outcome.candidateCount} reason=${outcome.reason ?: "-"}",
            )
        }

        val selectionRows = mutableListOf<String>()
        report.selections.forEach { (slot, selection) ->
            val path = selection.localImagePath
            val bytes = path?.let { runCatching { File(it).length() }.getOrDefault(-1L) } ?: -1L
            selectionRows += listOf(
                slot.name,
                selection.status.name,
                selection.source?.name ?: "-",
                path ?: "-",
                bytes.toString(),
                selection.licenseId ?: "-",
                selection.attribution ?: "-",
                selection.sourcePageUrl ?: "-",
                selection.confirmedMbid,
            ).joinToString("\t")
            Log.i(
                ProbeTag,
                "slot=${slot.name} status=${selection.status.name} source=${selection.source?.name} " +
                    "bytes=$bytes licence=${selection.licenseId} attribution=${selection.attribution}",
            )
        }
        write(
            "selections-midnight-oil.tsv",
            "slot\tstatus\tsource\tpath\tbytes\tlicence\tattribution\tsource_page\tmbid\n" +
                selectionRows.joinToString("\n") + "\n",
        )

        // The ladder ran against real endpoints, so the report has to name the providers
        // it consulted. An empty outcomes list would mean nothing was asked at all.
        assertTrue(
            "the ladder must report every provider it consulted, got ${report.outcomes.size}",
            report.outcomes.isNotEmpty(),
        )
        assertTrue(
            "a verified artist with a QID must come back with at least one saved photo, " +
                "status=${report.status} message=${report.message}",
            report.selections.values.any { it.status == ArtworkLoadStatus.READY },
        )
        val saved = report.selections.values.first { it.status == ArtworkLoadStatus.READY }
        val savedFile = File(requireNotNull(saved.localImagePath))
        assertTrue("the saved photo must exist at ${saved.localImagePath}", savedFile.exists())
        assertTrue(
            "a saved photo must carry real bytes, found ${savedFile.length()}",
            savedFile.length() > 1024L,
        )
        assertTrue(
            "a saved photo must carry its verified licence, found ${saved.licenseId}",
            !saved.licenseId.isNullOrBlank(),
        )
        assertTrue(
            "the photo must be bound to the artist's own MBID, found ${saved.confirmedMbid}",
            saved.confirmedMbid.equals(midnightOilMbid, ignoreCase = true),
        )
    }

    @Test
    fun withoutAQidTheLadderAsksNoProviderAndSavesNothing() {
        assumeTrue(
            "live provider probe is opt-in: pass -e michiLiveProviders true",
            arguments.getString("michiLiveProviders") == "true",
        )
        val anchor = liveAnchor(qid = null, suffix = "-noqid")
        val coordinator = ArtistArtworkService.from(context)

        val startedAt = SystemClock.elapsedRealtime()
        val report = runBlocking { coordinator.resolve(anchor) }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        write(
            "ladder-without-qid.tsv",
            "elapsed_ms\t$elapsedMs\n" +
                "selections\t${report.selections.size}\n" +
                report.outcomes.joinToString("\n") { outcome ->
                    outcome.source.name + "\t" + outcome.status.name + "\t" +
                        (outcome.reason ?: "") + "\t" + outcome.candidateCount
                } + "\n",
        )
        report.outcomes.forEach { outcome ->
            Log.i(
                ProbeTag,
                "no-qid provider=${outcome.source.name} status=${outcome.status.name} " +
                    "candidates=${outcome.candidateCount} reason=${outcome.reason ?: "-"}",
            )
        }

        assertTrue(
            "without a QID no provider may return a candidate, got " +
                report.outcomes.filter { it.candidateCount > 0 },
            report.outcomes.all { it.candidateCount == 0 },
        )
        assertTrue(
            "without a QID nothing may be saved, found ${report.selections.size} selections",
            report.selections.isEmpty(),
        )
    }

    private companion object {
        const val ProbeTag = "MichiLiveProviders"
    }
}
