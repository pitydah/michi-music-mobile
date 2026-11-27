package org.michimusic.mobile.library

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only a user-selected, exact, readable file can be confirmed; never HTTP. */
internal class ArtistTrackSourceProofReader(private val context: Context) {
    suspend fun read(claim: ArtistExactOccurrenceClaim): ArtistTrackByteProof? =
        withContext(Dispatchers.IO) {
            val identity = claim.trackIdentity.split('\u0000', limit = 3)
            if (identity.size != 3 || identity[1] != claim.trackId ||
                identity[2].isBlank() || identity[2].length > 4096) return@withContext null
            val locator = identity[2]
            val uri = Uri.parse(locator)
            val cancellation = currentCoroutineContext()
            val proof = when {
                uri.scheme.equals("content", ignoreCase = true) && !uri.authority.isNullOrBlank() ->
                    ArtistTrackSourceProof.digest(onChunk = { cancellation.ensureActive() }) {
                        context.contentResolver.openInputStream(uri)
                    }
                locator.startsWith('/') || uri.scheme.equals("file", ignoreCase = true) -> {
                    val file = if (locator.startsWith('/')) File(locator) else File(uri.path.orEmpty())
                    val path = file.toPath()
                    val before = try { Files.readAttributes(path, BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS) } catch (_: Exception) { return@withContext null }
                    if (!before.isRegularFile || before.isSymbolicLink || before.fileKey() == null ||
                        before.size() !in 1L..ArtistTrackSourceProof.MAX_BYTES)
                        return@withContext null
                    val proof = ArtistTrackSourceProof.digest(
                        onChunk = { cancellation.ensureActive() },
                    ) { FileInputStream(file) }
                    val after = try { Files.readAttributes(path, BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS) } catch (_: Exception) { return@withContext null }
                    if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
                        before.lastModifiedTime() != after.lastModifiedTime()) null else proof
                }
                else -> null
            }
            proof?.takeIf { ArtistTrackSourceProof.consistentWithCatalog(
                it, claim.localContentSignature,
            ) }
        }
}
