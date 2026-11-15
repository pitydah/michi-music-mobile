package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Late, strictly bounded P935 Commons gallery fallback. A gallery is a discovery
 * location, never a licence or proof of artist identity: each file must pass
 * MediaInfo P180 == verified QID and its own Commons imageinfo rights check.
 * K42: up to three file-level P180 batches across two pages, only while
 * no eligible candidate exists; no recursive walks or eager multi-page browsing.
 */
internal class CommonsGalleryArtworkProvider(
    private val http: ArtworkHttp,
    private val wikidata: WikidataArtworkLookup,
    private val commons: CommonsImageInfo,
) : ArtistArtworkProvider {
    override val source: ArtworkSource = ArtworkSource.COMMONS_GALLERY

    private data class GalleryPage(
        val files: List<String>,
        val continuation: CommonsGalleryContinuationPolicy.Tokens?,
    )

    override suspend fun findCandidates(
        anchor: ArtistIdentityAnchor,
        slot: ArtworkSlot,
    ): ProviderArtworkResult {
        val qid = anchor.verifiedQid?.takeIf(::validWikidataQid)
            ?: return ProviderArtworkResult.NoMatch
        val gallery = try {
            wikidata.snapshot(qid)?.commonsGallery?.takeIf(::validCommonsGalleryName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(error)
        } ?: return ProviderArtworkResult.NoMatch

        val visited = HashSet<String>()
        var continuation: CommonsGalleryContinuationPolicy.Tokens? = null
        var lastFailure: Exception? = null
        var batchesUsed = 0
        for (pageIndex in 0 until CommonsGalleryContinuationPolicy.MAX_PAGES) {
            val page = try {
                readPage(gallery, pageIndex, continuation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return failure(error)
            }
            // MediaWiki delivers up to 24 images on a gallery page. K40 silently
            // ignored files 13–24 even when the first dozen had no P180 assertion.
            // Validate and deduplicate all 24, then probe in 12-file batches.
            // Across both pages at most 3 P180 batches (36 images) are allowed.
            val files = CommonsDepictionBatchPolicy.selectFileTitles(
                page.files, CommonsGalleryContinuationPolicy.MAX_FILES_PER_PAGE,
            ).filter { file -> visited.add(file.replace('_', ' ').lowercase(java.util.Locale.ROOT)) }
            for (chunk in files.chunked(CommonsDepictionBatchPolicy.MAX_PROBE_FILES)) {
                if (batchesUsed >= CommonsGalleryContinuationPolicy.MAX_PROBE_BATCHES) break
                batchesUsed++
                val verified = try {
                    commons.depictsBatch(chunk, qid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return failure(error)
                }
                val accepted = mutableListOf<ArtistArtworkCandidate>()
                for (file in chunk) {
                    if (file !in verified) continue
                    try {
                        commons.read(file)
                            .toCandidate(anchor, source, ArtworkEvidence.VERIFIED_QID_REFERENCE)
                            ?.let(accepted::add)
                        if (accepted.size >= 2) break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        lastFailure = error
                    }
                }
                // One verified image can already serve both THUMB/HERO slots;
                // never pay for another probe or page once it is available.
                if (accepted.isNotEmpty()) return ProviderArtworkResult.Available(accepted)
            }
            if (batchesUsed >= CommonsGalleryContinuationPolicy.MAX_PROBE_BATCHES) break
            continuation = page.continuation
            if (continuation == null) break
        }
        return lastFailure?.let(::failure) ?: ProviderArtworkResult.NoMatch
    }

    private suspend fun readPage(
        gallery: String,
        pageIndex: Int,
        previous: CommonsGalleryContinuationPolicy.Tokens?,
    ): GalleryPage {
        val endpoint = requireNotNull("https://commons.wikimedia.org/w/api.php".toHttpUrlOrNull())
            .newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "images")
            .addQueryParameter("titles", gallery)
            .addQueryParameter("imlimit", "24")
            .addQueryParameter("format", "json")
        if (previous != null) {
            endpoint.addQueryParameter("imcontinue", previous.image)
                .addQueryParameter("continue", previous.generic)
        }
        val body = http.readJson(endpoint.build().toString(), setOf("commons.wikimedia.org"))
        val pages = (body["query"] as? JsonObject)?.get("pages") as? JsonObject
        val files = mutableListOf<String>()
        pages?.values.orEmpty().forEach pageLoop@ { value ->
            val page = value as? JsonObject ?: return@pageLoop
            val namespace = (page["ns"] as? JsonPrimitive)?.contentOrNull
            val title = (page["title"] as? JsonPrimitive)?.contentOrNull
            if (namespace != "0" || title == null ||
                !CommonsGalleryPagePolicy.matchesRequestedGallery(gallery, title)) return@pageLoop
            val images = page["images"] as? JsonArray ?: return@pageLoop
            images.forEach imageLoop@ { member ->
                val item = member as? JsonObject ?: return@imageLoop
                if ((item["ns"] as? JsonPrimitive)?.contentOrNull != "6") return@imageLoop
                val fileTitle = (item["title"] as? JsonPrimitive)?.contentOrNull
                    ?: return@imageLoop
                if (!fileTitle.startsWith("File:")) return@imageLoop
                files.add(fileTitle.removePrefix("File:"))
            }
        }
        // No untrusted token may be used as a URL or as a new page title. Tokens
        // are accepted only when a matching gallery page returned the images.
        val cursor = (body["continue"] as? JsonObject)
        val next = if (pages?.values.orEmpty().any { value ->
                val obj = value as? JsonObject
                obj?.let { (it["ns"] as? JsonPrimitive)?.contentOrNull == "0" &&
                    CommonsGalleryPagePolicy.matchesRequestedGallery(
                        gallery, (it["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()) } == true
            }) CommonsGalleryContinuationPolicy.next(
                (cursor?.get("imcontinue") as? JsonPrimitive)?.contentOrNull,
                (cursor?.get("continue") as? JsonPrimitive)?.contentOrNull,
                pageIndex, previous,
            ) else null
        return GalleryPage(files, next)
    }
}
