package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Second, bounded P180 search page, invoked only when P18 and the first P180
 * page did not fill the missing slots. The search index is NOT proof of identity:
 * every file is checked against Commons MediaInfo P180 and its own imageinfo.
 * No additional scanner, background work, or unverified-name fallback.
 */
internal class CommonsP180ContinuationProvider(
    private val http: ArtworkHttp,
    private val commons: CommonsImageInfo,
) : ArtistArtworkProvider {
    override val source = ArtworkSource.COMMONS_P180

    override suspend fun findCandidates(
        anchor: ArtistIdentityAnchor,
        slot: ArtworkSlot,
    ): ProviderArtworkResult {
        val qid = anchor.verifiedQid?.takeIf(::validWikidataQid)
            ?: return ProviderArtworkResult.NoMatch
        val endpoint = requireNotNull("https://commons.wikimedia.org/w/api.php".toHttpUrlOrNull())
            .newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "search")
            .addQueryParameter("srsearch", "haswbstatement:P180=$qid")
            .addQueryParameter("srnamespace", "6")
            .addQueryParameter("srlimit", "12")
            .addQueryParameter("sroffset", "12")
            .addQueryParameter("format", "json")
            .build()
        val response = try {
            http.readJson(endpoint.toString(), setOf("commons.wikimedia.org"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(error)
        }
        val names = ((response["query"] as? JsonObject)?.get("search") as? JsonArray)
            .orEmpty().mapNotNull { item ->
                val row = item as? JsonObject ?: return@mapNotNull null
                if ((row["ns"] as? JsonPrimitive)?.contentOrNull?.let { it != "6" } == true) {
                    return@mapNotNull null
                }
                (row["title"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.startsWith("File:") }
                    ?.removePrefix("File:")
            }
        val files = CommonsDepictionBatchPolicy.selectFileTitles(names)
        if (files.isEmpty()) return ProviderArtworkResult.NoMatch
        val verified = try {
            commons.depictsBatch(files, qid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(error)
        }
        val candidates = mutableListOf<ArtistArtworkCandidate>()
        var lastFailure: Exception? = null
        // First four rejected metadata rows must not mask a valid fifth file.
        // At most eight imageinfo reads, stopping immediately after two usable
        // images; this stage remains bounded and never trusts search titles alone.
        for (file in CommonsP180MetadataBudget.selected(files, verified)) {
            try {
                commons.read(file)
                    .toCandidate(anchor, source, ArtworkEvidence.VERIFIED_QID_REFERENCE)
                    ?.let(candidates::add)
                if (candidates.size >= 2) break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        return if (candidates.isNotEmpty()) ProviderArtworkResult.Available(candidates)
            else lastFailure?.let(::failure) ?: ProviderArtworkResult.NoMatch
    }
}
