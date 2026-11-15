package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * P373 category is only a discovery entry. Every file must prove the verified artist
 * QID via MediaInfo P180, and then have its own independently verified rights.
 * Two listing pages and three P180 batches are hard ceilings, never a tree walk.
 */
internal class CommonsCategoryArtworkProvider(
    private val http: ArtworkHttp,
    private val wikidata: WikidataArtworkLookup,
    private val commons: CommonsImageInfo,
) : ArtistArtworkProvider {
    override val source: ArtworkSource = ArtworkSource.COMMONS_CATEGORY

    private data class MemberPage(val files: List<String>, val continuation: String?)

    override suspend fun findCandidates(
        anchor: ArtistIdentityAnchor,
        slot: ArtworkSlot,
    ): ProviderArtworkResult {
        val qid = anchor.verifiedQid?.takeIf(::validWikidataQid)
            ?: return ProviderArtworkResult.NoMatch
        val category = try {
            wikidata.snapshot(qid)?.commonsCategories?.firstOrNull()
                ?.takeIf(::validCommonsCategoryName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(error)
        } ?: return ProviderArtworkResult.NoMatch

        val accepted = mutableListOf<ArtistArtworkCandidate>()
        val visited = HashSet<String>()
        var lastFailure: Exception? = null
        var token: String? = null
        var batches = 0
        for (pageIndex in 0..1) {
            val page = try {
                listPage(category, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return failure(error)
            }
            val selected = page.files.asSequence().filter { file ->
                visited.add(file.replace('_', ' ').lowercase(Locale.ROOT))
            }.take((3 - batches) * CommonsDepictionBatchPolicy.MAX_PROBE_FILES).toList()
            for (chunk in selected.chunked(CommonsDepictionBatchPolicy.MAX_PROBE_FILES)) {
                if (batches++ >= 3) break
                val files = CommonsDepictionBatchPolicy.selectFileTitles(chunk)
                if (files.isEmpty()) continue
                val verified = try {
                    commons.depictsBatch(files, qid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return failure(error)
                }
                for (file in files) {
                    if (file !in verified) continue
                    try {
                        commons.read(file)
                            .toCandidate(anchor, source, ArtworkEvidence.VERIFIED_QID_REFERENCE)
                            ?.let(accepted::add)
                        if (accepted.size >= 2) return ProviderArtworkResult.Available(accepted)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        lastFailure = error
                    }
                }
            }
            if (accepted.isNotEmpty()) return ProviderArtworkResult.Available(accepted)
            if (batches >= 3 || pageIndex == 1) break
            token = CommonsCategoryContinuationPolicy.nextToken(page.continuation, pageIndex, batches)
            if (token == null) break
        }
        return when {
            accepted.isNotEmpty() -> ProviderArtworkResult.Available(accepted)
            lastFailure != null -> failure(lastFailure)
            else -> ProviderArtworkResult.NoMatch
        }
    }

    private suspend fun listPage(category: String, continuation: String?): MemberPage {
        val request = requireNotNull("https://commons.wikimedia.org/w/api.php".toHttpUrlOrNull())
            .newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "categorymembers")
            .addQueryParameter("cmtitle", "Category:$category")
            .addQueryParameter("cmtype", "file")
            .addQueryParameter("cmlimit", "24")
            .addQueryParameter("format", "json")
        if (continuation != null) request.addQueryParameter("cmcontinue", continuation)
        val body = http.readJson(request.build().toString(), setOf("commons.wikimedia.org"))
        val files = ((body["query"] as? JsonObject)?.get("categorymembers") as? JsonArray)
            .orEmpty().mapNotNull { member ->
                val record = member as? JsonObject ?: return@mapNotNull null
                if ((record["ns"] as? JsonPrimitive)?.contentOrNull != "6") return@mapNotNull null
                val title = (record["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                title.takeIf { it.startsWith("File:") }
                    ?.removePrefix("File:")?.takeIf(::validCommonsFileTitle)
            }
        val next = ((body["continue"] as? JsonObject)?.get("cmcontinue") as? JsonPrimitive)
            ?.contentOrNull
        return MemberPage(files, next)
    }
}
