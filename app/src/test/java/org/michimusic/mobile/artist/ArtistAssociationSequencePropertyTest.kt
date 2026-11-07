package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ArtistAssociationSequencePropertyTest {
    private val seed = 0x4D49434849L
    private val credit = "Same Local Credit"
    private val mbids = listOf(
        "bd6d4f63-5cd0-4d78-a292-167997794c21",
        "05072650-4b05-4e8b-83d0-dba2576e306a",
    )
    private val albums = listOf("album-a", "album-b", "album-c")
    private val probes = listOf(
        emptyList(),
        listOf("album-a"), listOf("album-b"), listOf("album-c"),
        listOf("album-a", "album-b"), listOf("album-b", "album-a"),
        listOf("album-a", "album-c"), listOf("album-b", "album-c"),
        listOf("album-a", "album-b", "album-c"),
    )

    private data class Association(val mbid: String, val albums: MutableSet<String> = linkedSetOf())
    private data class Resolution(val associationId: String, val albums: Set<String>)
    private data class Model(
        val associations: MutableMap<String, Association> = linkedMapOf(),
        val bindings: MutableMap<String, String> = linkedMapOf(),
        val resolutions: MutableMap<String, Resolution> = linkedMapOf(),
    )

    @Test
    fun fiveHundredGeneratedSequencesMatchTheIndependentAssociationModel() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ArtistEnrichmentCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        var tick = 1L
        val store = RoomArtistEnrichmentSelectionStore(database) { tick++ }
        val random = Random(seed)
        try {
            repeat(500) { sequence ->
                val models = mutableMapOf("a" to Model(), "b" to Model())
                val history = mutableListOf<String>()
                val steps = 1 + random.nextInt(25)
                repeat(steps) { step ->
                    val suffix = if (random.nextBoolean()) "a" else "b"
                    val library = "property-$sequence-$suffix"
                    val model = requireNotNull(models[suffix])
                    when (random.nextInt(7)) {
                        0 -> confirm(random, store, library, model, history)
                        1 -> bind(random, store, library, model, history)
                        2 -> resolve(random, store, library, model, history)
                        3 -> refresh(random, store, library, model, history)
                        4 -> clear(random, store, library, model, history)
                        5 -> replace(random, store, library, model, history)
                        else -> history += "observe($library)"
                    }
                    assertModel(store, library, model, sequence, step, history)
                }
            }
        } finally {
            database.close()
        }
    }

    private suspend fun confirm(
        random: Random,
        store: RoomArtistEnrichmentSelectionStore,
        library: String,
        model: Model,
        history: MutableList<String>,
    ) {
        val album = albums[random.nextInt(albums.size)]
        val mbid = mbids[random.nextInt(mbids.size)]
        val associationId = group(library, listOf(album))
        val conflict = model.bindings[album]?.let { it != associationId } == true ||
            model.associations[associationId]?.mbid?.let { it != mbid } == true
        history += "confirm($library,$album,$mbid)"
        val result = runCatching { store.confirmNewIdentity(library, credit, listOf(album), mbid) }
        assertEquals("confirm acceptance mismatch: ${history.joinToString(" -> ")}", !conflict, result.isSuccess)
        if (!conflict) {
            model.associations.getOrPut(associationId) { Association(mbid) }.albums += album
            model.bindings[album] = associationId
        }
    }

    private suspend fun bind(
        random: Random,
        store: RoomArtistEnrichmentSelectionStore,
        library: String,
        model: Model,
        history: MutableList<String>,
    ) {
        val entry = model.associations.entries.randomOrNull(random) ?: return
        val album = albums[random.nextInt(albums.size)]
        val conflict = model.bindings[album]?.let { it != entry.key } == true
        history += "bind($library,$album,${entry.key})"
        val result = runCatching {
            store.bindAlbumsToAssociation(
                library, credit, listOf(album), entry.key, entry.value.mbid,
                ArtistMembershipEvidenceType.USER_EXPLICIT_BINDING,
            )
        }
        assertEquals("bind acceptance mismatch: ${history.joinToString(" -> ")}", !conflict, result.isSuccess)
        if (!conflict) {
            entry.value.albums += album
            model.bindings[album] = entry.key
        }
    }

    private suspend fun resolve(
        random: Random,
        store: RoomArtistEnrichmentSelectionStore,
        library: String,
        model: Model,
        history: MutableList<String>,
    ) {
        val context = probes.filter { it.size >= 2 }.randomOrNull(random) ?: return
        val candidates = context.mapNotNull(model.bindings::get).distinct()
        if (candidates.size <= 1) return
        val chosen = candidates[random.nextInt(candidates.size)]
        val mbid = requireNotNull(model.associations[chosen]).mbid
        history += "resolve($library,${context.joinToString("+")},$chosen)"
        store.resolveAssociation(library, credit, context, chosen, mbid)
        model.resolutions[group(library, context)] = Resolution(chosen, context.toSet())
    }

    private suspend fun refresh(
        random: Random,
        store: RoomArtistEnrichmentSelectionStore,
        library: String,
        model: Model,
        history: MutableList<String>,
    ) {
        val entry = model.associations.entries.randomOrNull(random) ?: return
        history += "refresh($library,${entry.key})"
        store.refreshProfile(
            library,
            credit,
            entry.key,
            ArtistExternalProfile(mbid = entry.value.mbid, name = "Profile ${entry.value.mbid}"),
        )
    }

    private suspend fun clear(
        random: Random,
        store: RoomArtistEnrichmentSelectionStore,
        library: String,
        model: Model,
        history: MutableList<String>,
    ) {
        val associationId = model.associations.keys.randomOrNull(random) ?: return
        history += "clear($library,$associationId)"
        store.clear(library, credit, listOf(albums.first()), associationId)
        model.associations.remove(associationId)
        model.bindings.entries.removeAll { it.value == associationId }
        model.resolutions.entries.removeAll { it.value.associationId == associationId }
    }

    private suspend fun replace(
        random: Random,
        store: RoomArtistEnrichmentSelectionStore,
        library: String,
        model: Model,
        history: MutableList<String>,
    ) {
        val album = model.bindings.keys.randomOrNull(random) ?: return
        val current = requireNotNull(model.bindings[album])
        val replacement = model.associations.keys.filterNot { it == current }.randomOrNull(random) ?: return
        val replacementIdentity = requireNotNull(model.associations[replacement])
        history += "replace($library,$album,$current->$replacement)"
        store.replaceAlbumBindings(
            libraryId = library,
            credit = credit,
            albumIds = listOf(album),
            expectedCurrentAssociationId = current,
            replacementAssociationId = replacement,
            replacementMbid = replacementIdentity.mbid,
        )
        model.associations[current]?.albums?.remove(album)
        replacementIdentity.albums += album
        model.bindings[album] = replacement
        model.resolutions.entries.removeAll { album in it.value.albums }
    }

    private suspend fun assertModel(
        store: RoomArtistEnrichmentSelectionStore,
        library: String,
        model: Model,
        sequence: Int,
        step: Int,
        history: List<String>,
    ) {
        val trace = "seed=$seed sequence=$sequence step=$step: ${history.joinToString(" -> ")}"
        probes.forEach { context ->
            if (context.isEmpty()) {
                assertNull(trace, store.load(library, credit, context))
                return@forEach
            }
            val exact = model.resolutions[group(library, context)]
            val candidates = context.mapNotNull(model.bindings::get).distinct()
            when {
                exact != null -> {
                    val loaded = store.load(library, credit, context)
                    assertNotNull(trace, loaded)
                    requireNotNull(loaded)
                    assertEquals(trace, model.associations[exact.associationId]?.mbid, loaded.profile.mbid)
                    assertEquals(trace, exact.associationId, loaded.associationGroupKey)
                }
                candidates.isEmpty() -> assertNull(trace, store.load(library, credit, context))
                candidates.size == 1 -> {
                    val associationId = candidates.single()
                    val loaded = store.load(library, credit, context)
                    assertNotNull(trace, loaded)
                    requireNotNull(loaded)
                    assertEquals(trace, model.associations[associationId]?.mbid, loaded.profile.mbid)
                    assertEquals(trace, associationId, loaded.associationGroupKey)
                    assertEquals(trace, context.any { model.bindings[it] == null }, loaded.needsConfirmation)
                }
                else -> assertTrue(
                    trace,
                    runCatching { store.load(library, credit, context) }.exceptionOrNull()
                        is ArtistAssociationAmbiguousException,
                )
            }
        }
    }

    private fun group(library: String, albumIds: List<String>): String =
        requireNotNull(scopedArtistCreditKey(library, credit, albumIds)).second

    private fun <T> Collection<T>.randomOrNull(random: Random): T? =
        if (isEmpty()) null else elementAt(random.nextInt(size))
}
