package org.michimusic.mobile.devices.aircat

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.MusicLibrary
import org.michimusic.core.models.PlayableLocation
import org.michimusic.core.models.ResolvedTrack
import org.michimusic.core.models.TrackIdentity
import org.michimusic.core.models.TrackLocation
import org.michimusic.data.repository.MusicLibraryRepository

/**
 * A library that actually remembers what it was asked to register.
 *
 * A68-03: the AirCat commit receipt re-reads the persisted identity and location
 * after the transfer, so a relaxed mock cannot tell the difference between "the
 * library recorded this track" and "nobody answered". This fake stores the rows
 * and serves them back, which is the only way the receipt can be earned rather
 * than fabricated.
 */
internal class FakeMusicLibraryRepository(
    /** Set to a failure to simulate a real storage error on registration. */
    var registrationFailure: Exception? = null,
) : MusicLibraryRepository {

    data class Registration(
        val identity: TrackIdentity,
        val location: TrackLocation,
        val libraryId: String,
        val origin: String,
    )

    private val identities = mutableMapOf<String, TrackIdentity>()
    private val locations = mutableMapOf<String, MutableList<TrackLocation>>()
    val registrations = mutableListOf<Registration>()

    /** Seed a pre-existing identity, the way a previous import would have left it. */
    fun seedIdentity(identity: TrackIdentity) {
        identities[identity.id] = identity
    }

    /** Seed a pre-existing location, the way a previous import would have left it. */
    fun seedLocation(location: TrackLocation) {
        locations.getOrPut(location.trackId) { mutableListOf() }.add(location)
    }

    override suspend fun registerTrackWithLocation(
        identity: TrackIdentity,
        location: TrackLocation,
        libraryId: String,
        origin: String,
    ): Result<Unit> {
        registrationFailure?.let { return Result.failure(it) }
        registrations += Registration(identity, location, libraryId, origin)
        identities[identity.id] = identity
        locations.getOrPut(location.trackId) { mutableListOf() }.add(location)
        return Result.success(Unit)
    }

    override suspend fun getTrackIdentity(trackId: String): TrackIdentity? = identities[trackId]

    override suspend fun getLocationsForTrack(trackId: String): List<TrackLocation> =
        locations[trackId].orEmpty().toList()

    override suspend fun isLocationRegistered(uri: String): Boolean =
        locations.values.flatten().any { it.uri == uri }

    override fun observeLibraries(): Flow<List<MusicLibrary>> = flowOf(emptyList())

    override fun observeTracks(scope: LibraryScope): Flow<List<ResolvedTrack>> = flowOf(emptyList())

    override suspend fun createUserLibrary(name: String): MusicLibrary = notUsed()

    override suspend fun renameLibrary(id: String, newName: String): Result<Unit> = notUsed()

    override suspend fun deleteUserLibrary(id: String): Result<Unit> = notUsed()

    override suspend fun addTrackToLibrary(libraryId: String, trackId: String): Result<Unit> = notUsed()

    override suspend fun removeMembership(libraryId: String, trackId: String): Result<Unit> = notUsed()

    override suspend fun resolvePreferredLocation(trackId: String): PlayableLocation? = null

    private fun notUsed(): Nothing = throw IOException("FakeMusicLibraryRepository: unsupported operation")
}
