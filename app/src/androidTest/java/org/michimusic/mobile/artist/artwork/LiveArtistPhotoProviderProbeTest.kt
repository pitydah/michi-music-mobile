package org.michimusic.mobile.artist.artwork

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---------------------------------------------------------------------------------
    // Per-provider isolation probes.
    //
    // Why they exist: the ladder stops as soon as both slots are READY, and P18 exists
    // for essentially every notable artist, so the later stages were never exercised
    // against the network. An aggregate ladder probe cannot prove them: it proves only
    // that the FIRST stage that answers works. Each stage is therefore driven alone,
    // with a real artist whose Commons/Wikidata data satisfies that stage's own
    // precondition, and the resulting candidate must be downloadable.
    // ---------------------------------------------------------------------------------

    private data class ProbeArtist(
        val credit: String,
        val creditKey: String,
        val mbid: String,
        val qid: String?,
    )

    private fun anchor(artist: ProbeArtist, suffix: String): ArtistIdentityAnchor {
        val run = arguments.getString("michiProbeRun") ?: "r1"
        return ArtistIdentityAnchor(
            libraryId = "lib_live_probe_$run$suffix",
            creditKey = artist.creditKey,
            albumGroupKey = "live|${artist.creditKey}|$run$suffix",
            confirmedMbid = artist.mbid,
            verifiedQid = artist.qid,
            displayCredit = artist.credit,
        )
    }

    /**
     * Drives ONE provider against the live endpoints and requires a real photograph.
     * Metadata alone is not proof: the candidate is downloaded and its bytes, MIME type
     * and decoded dimensions must be real.
     */
    private fun assertProviderBringsRealPhoto(
        label: String,
        provider: ArtistArtworkProvider,
        artist: ProbeArtist,
        expectedSource: ArtworkSource,
        expectedEvidence: ArtworkEvidence,
        suffix: String,
    ) {
        val anchor = anchor(artist, suffix)
        val startedAt = SystemClock.elapsedRealtime()
        val result = runBlocking { provider.findCandidates(anchor, ArtworkSlot.ARTIST_THUMB) }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        val candidates = when (result) {
            is ProviderArtworkResult.Available -> result.items
            is ProviderArtworkResult.NoMatch ->
                throw AssertionError("$label returned NoMatch for ${artist.credit} (${artist.qid})")
            is ProviderArtworkResult.Failure ->
                throw AssertionError("$label failed for ${artist.credit}: ${result.type}")
            is ProviderArtworkResult.Disabled ->
                throw AssertionError("$label is disabled: ${result.reason}")
        }
        assertTrue("$label must return at least one candidate", candidates.isNotEmpty())

        val candidate = candidates.first()
        assertTrue(
            "$label must report its own source, got ${candidate.source}",
            candidate.source == expectedSource,
        )
        assertTrue(
            "$label must carry its own evidence, got ${candidate.evidence}",
            candidate.evidence == expectedEvidence,
        )
        assertTrue(
            "$label must bind the candidate to the artist MBID, got ${candidate.confirmedMbid}",
            candidate.confirmedMbid == artist.mbid,
        )
        assertTrue(
            "$label must bind the candidate to the verified QID, got ${candidate.verifiedQid}",
            candidate.verifiedQid == artist.qid,
        )
        assertTrue(
            "$label must only return allowed rights, got ${candidate.rights}",
            candidate.rights == ArtworkRights.VERIFIED_ALLOWED,
        )
        assertTrue(
            "$label must carry a licence, got ${candidate.licenseId}",
            !candidate.licenseId.isNullOrBlank(),
        )
        assertTrue(
            "$label must carry an attribution, got ${candidate.attribution}",
            !candidate.attribution.isNullOrBlank(),
        )

        val download = runCatching { runBlocking { ArtworkDownloader(context).download(candidate) } }
            .getOrElse { error ->
                throw AssertionError("$label candidate ${candidate.stableAssetId} could not download", error)
            }
        assertTrue(
            "$label must download real pixels, got ${download.width}x${download.height}",
            download.width >= 320 && download.height >= 320,
        )
        assertTrue(
            "$label must download an image, got ${download.mimeType}",
            download.mimeType.startsWith("image/"),
        )
        val bytes = File(context.filesDir, download.relativePath).length()
        assertTrue("$label must download real bytes, got $bytes", bytes > 1024L)

        write(
            "provider-$suffix.tsv",
            "provider\t${provider.source.name}\n" +
                "artist\t${artist.credit}\n" +
                "qid\t${artist.qid}\n" +
                "mbid\t${artist.mbid}\n" +
                "elapsed_ms\t$elapsedMs\n" +
                "candidates\t${candidates.size}\n" +
                "asset\t${candidate.stableAssetId}\n" +
                "licence\t${candidate.licenseId}\n" +
                "attribution\t${candidate.attribution}\n" +
                "download\t${download.width}x${download.height}\t${download.mimeType}\t$bytes bytes\n" +
                "source_page\t${candidate.canonicalSourcePage}\n",
        )
        Log.i(
            ProbeTag,
            "provider=${provider.source.name} artist=${artist.credit} candidates=${candidates.size} " +
                "elapsed_ms=$elapsedMs download=${download.width}x${download.height} bytes=$bytes",
        )
    }

    /** Wikidata P18: the artist's own declared image. */
    @Test
    fun theP18ProviderReturnsTheArtistsDeclaredImage() {
        requireLive()
        val http = ArtworkHttp()
        val provider = CommonsP18Provider(http, WikidataArtworkLookup(http), CommonsImageInfo(http))
        assertProviderBringsRealPhoto(
            label = "P18",
            provider = provider,
            artist = midnightOil,
            expectedSource = ArtworkSource.COMMONS_P18,
            expectedEvidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
            suffix = "p18",
        )
    }

    /** Commons structured data: files whose own P180 statement is the verified QID. */
    @Test
    fun theP180ProviderReturnsFilesThatDepictTheArtist() {
        requireLive()
        val http = ArtworkHttp()
        val provider = CommonsP180Provider(http, WikidataArtworkLookup(http), CommonsImageInfo(http))
        assertProviderBringsRealPhoto(
            label = "P180",
            provider = provider,
            artist = midnightOil,
            expectedSource = ArtworkSource.COMMONS_P180,
            expectedEvidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
            suffix = "p180",
        )
    }

    /** Wikipedia PageImages, accepted only through the QID's own sitelink article. */
    @Test
    fun theWikipediaProviderReturnsTheSitelinkLeadImage() {
        requireLive()
        val http = ArtworkHttp()
        val provider = WikipediaPageImageProvider(http, WikidataArtworkLookup(http), CommonsImageInfo(http))
        assertProviderBringsRealPhoto(
            label = "Wikipedia PageImages",
            provider = provider,
            artist = charlyGarcia,
            expectedSource = ArtworkSource.WIKIPEDIA_PAGEIMAGE,
            expectedEvidence = ArtworkEvidence.VERIFIED_ARTICLE_LINK,
            suffix = "wikipedia",
        )
    }

    /** P373 category is discovery; each member must prove the QID through its own P180. */
    @Test
    fun theCategoryProviderReturnsDepictedCategoryMembers() {
        requireLive()
        val http = ArtworkHttp()
        val provider = CommonsCategoryArtworkProvider(http, WikidataArtworkLookup(http), CommonsImageInfo(http))
        assertProviderBringsRealPhoto(
            label = "P373 category",
            provider = provider,
            artist = mercedesSosa,
            expectedSource = ArtworkSource.COMMONS_CATEGORY,
            expectedEvidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
            suffix = "category",
        )
    }

    /** P935 gallery is discovery; each image must prove the QID through its own P180. */
    @Test
    fun theGalleryProviderReturnsDepictedGalleryImages() {
        requireLive()
        val http = ArtworkHttp()
        val provider = CommonsGalleryArtworkProvider(http, WikidataArtworkLookup(http), CommonsImageInfo(http))
        assertProviderBringsRealPhoto(
            label = "P935 gallery",
            provider = provider,
            artist = bobDylan,
            expectedSource = ArtworkSource.COMMONS_GALLERY,
            expectedEvidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
            suffix = "gallery",
        )
    }

    /**
     * The ladder must FALL THROUGH when P18 has nothing. Paco Amoroso has no P18 at all,
     * which is the only real-world condition that lets the later stages run; without this
     * test the failover would be an untested claim.
     */
    @Test
    fun theLadderFallsThroughWhenTheArtistHasNoDeclaredImage() {
        requireLive()
        val anchor = anchor(pacoAmoroso, suffix = "-failover")
        val coordinator = ArtistArtworkService.from(context)

        val startedAt = SystemClock.elapsedRealtime()
        val report = runBlocking { coordinator.resolve(anchor) }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        val rows = report.outcomes.joinToString("\n") { outcome ->
            outcome.source.name + "\t" + outcome.status.name + "\t" +
                (outcome.reason ?: "") + "\t" + outcome.candidateCount
        }
        write(
            "ladder-failover.tsv",
            "artist\t${pacoAmoroso.credit}\n" +
                "qid\t${pacoAmoroso.qid}\n" +
                "elapsed_ms\t$elapsedMs\n" +
                "status\t${report.status}\n" +
                "source\tstatus\treason\tcandidates\n" + rows + "\n",
        )
        report.outcomes.forEach { outcome ->
            Log.i(
                ProbeTag,
                "failover provider=${outcome.source.name} status=${outcome.status.name} " +
                    "candidates=${outcome.candidateCount}",
            )
        }

        val bySource = report.outcomes.associateBy { it.source }
        assertTrue(
            "P18 must report no match for an artist without P18, got ${bySource[ArtworkSource.COMMONS_P18]?.status}",
            bySource[ArtworkSource.COMMONS_P18]?.status == ArtworkProviderStatus.NO_MATCH,
        )
        val laterStage = report.outcomes.firstOrNull {
            it.source in setOf(
                ArtworkSource.COMMONS_P180,
                ArtworkSource.WIKIPEDIA_PAGEIMAGE,
                ArtworkSource.COMMONS_CATEGORY,
                ArtworkSource.COMMONS_GALLERY,
            ) && it.status == ArtworkProviderStatus.AVAILABLE
        }
        assertTrue(
            "the ladder must reach a later provider when P18 has nothing, outcomes=" +
                report.outcomes.joinToString { "${it.source}=${it.status}" },
            laterStage != null,
        )
        assertTrue(
            "the fall-through photo must be saved, status=${report.status}",
            report.selections.values.any { it.status == ArtworkLoadStatus.READY },
        )
        val saved = report.selections.values.first { it.status == ArtworkLoadStatus.READY }
        val savedFile = File(requireNotNull(saved.localImagePath))
        assertTrue("the fall-through photo must exist, found ${saved.localImagePath}", savedFile.exists())
        assertTrue("the fall-through photo must have bytes", savedFile.length() > 1024L)
        assertTrue(
            "the fall-through photo must come from the later stage that answered, got ${saved.source}",
            saved.source == laterStage?.source,
        )
    }

    /**
     * TheAudioDB is manual-only: it publishes photos and no licence. This probe checks
     * both halves of that sentence — the catalogue answers a real search, and what it
     * produces is saved with the licence still declared as undeclared.
     */
    @Test
    fun theAudioDbCatalogueAnswersAManualSearchWithoutClaimingALicence() {
        requireLive()
        runBlocking {
            val artist = midnightOil
            val target = anchor(artist, "-theaudiodb")
            val coordinator = ArtistArtworkService.from(context)

            val result = coordinator.searchManual(target, artist.credit)
            val catalogue = (result as? ProviderArtworkResult.Available)?.items.orEmpty()
                .filter { it.source == ArtworkSource.THEAUDIODB }
            write(
                "provider-theaudiodb.tsv",
                buildString {
                    appendLine("provider\tTHEAUDIODB")
                    appendLine("artist\t${artist.credit}")
                    appendLine("candidates\t${catalogue.size}")
                    catalogue.forEach {
                        appendLine("asset\t${it.stableAssetId}")
                        appendLine("image\t${it.imageUrl}")
                        appendLine("page\t${it.canonicalSourcePage}")
                        appendLine("licence\t${it.licenseId ?: "NO DECLARADA"}")
                        appendLine("rights\t${it.rights}")
                    }
                },
            )
            assertTrue("TheAudioDB must answer the manual search, got $result", catalogue.isNotEmpty())
            assertTrue(
                "every catalogue candidate is review-only",
                catalogue.all { it.rights == ArtworkRights.REQUIRES_REVIEW || it.rights == ArtworkRights.UNDECLARED_LICENSE },
            )
            assertTrue("no candidate may state a licence", catalogue.all { it.licenseId == null && it.licenseUrl == null })

            val chosen = catalogue.first { it.imageUrl.contains("/thumb/") || catalogue.size == 1 }
            val report = coordinator.chooseManual(target, chosen, ArtworkSlot.ARTIST_THUMB)
            val saved = report.selections[ArtworkSlot.ARTIST_THUMB]
            write(
                "choosetheaudiodb.tsv",
                buildString {
                    appendLine("status\t${report.status}")
                    appendLine("slot\t${saved?.slot}")
                    appendLine("asset\t${saved?.assetId}")
                    appendLine("source\t${saved?.source}")
                    appendLine("licence\t${saved?.licenseId ?: "NO DECLARADA"}")
                    appendLine("attribution\t${saved?.attribution ?: "SIN AUTOR DECLARADO"}")
                    appendLine("bytes\t${saved?.localImagePath?.let { File(it).length() }}")
                },
            )

            assertEquals("the chosen catalogue photo must be saved", ArtworkLoadStatus.READY, saved?.status)
            assertEquals(ArtworkSource.THEAUDIODB, saved?.source)
            // The store normalises a missing licence to an empty string; what matters is
            // that nothing was invented, and that the slot has no attribution either.
            assertTrue(
                "the provider declares no licence and none may be invented, got ${saved?.licenseId}",
                saved?.licenseId.isNullOrBlank(),
            )
            assertTrue(saved?.licenseUrl.isNullOrBlank())
            assertTrue("the photo must really have bytes", File(requireNotNull(saved?.localImagePath)).length() > 1024L)
        }
    }

    private fun requireLive() {
        assumeTrue(
            "live provider probe is opt-in: pass -e michiLiveProviders true",
            arguments.getString("michiLiveProviders") == "true",
        )
    }

    private companion object {
        const val ProbeTag = "MichiLiveProviders"

        val midnightOil = ProbeArtist(
            credit = "Midnight Oil",
            creditKey = "midnight oil",
            mbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
            qid = "Q48284",
        )

        /** Lead image of the enwiki article carries P180 == the artist QID. */
        val charlyGarcia = ProbeArtist(
            credit = "Charly García",
            creditKey = "charly garcia",
            mbid = "dd999ced-1dd4-44b8-ab67-ee5ec23e52b7",
            qid = "Q467940",
        )

        /** P373 category whose members carry file-level P180 for the singer. */
        val mercedesSosa = ProbeArtist(
            credit = "Mercedes Sosa",
            creditKey = "mercedes sosa",
            mbid = "324b9329-7be6-491f-9ee1-65bfa10c7577",
            qid = "Q216450",
        )

        /** Declares a P935 gallery whose images carry file-level P180. */
        val bobDylan = ProbeArtist(
            credit = "Bob Dylan",
            creditKey = "bob dylan",
            mbid = "72c536dc-7137-4477-a521-567eeb840fa8",
            qid = "Q392",
        )

        /** No P18 on Wikidata: the only real condition that forces the ladder onward. */
        val pacoAmoroso = ProbeArtist(
            credit = "Paco Amoroso",
            creditKey = "paco amoroso",
            mbid = "d069bf52-d213-49ac-8f9c-31841405bc9d",
            qid = "Q130739234",
        )
    }
}
