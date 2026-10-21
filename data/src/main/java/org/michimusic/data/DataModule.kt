package org.michimusic.data

import androidx.room.Room
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.PlaylistRepository
import org.michimusic.data.repository.SyncedTrackRepository

val dataModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            MichiDatabase::class.java,
            "michi-sync.db",
        ).addMigrations(
            MichiDatabase.MIGRATION_1_2,
            MichiDatabase.MIGRATION_2_3,
            MichiDatabase.MIGRATION_3_4,
        ).build()
    }
    single { get<MichiDatabase>().trackDao() }
    single { get<MichiDatabase>().playlistDao() }
    single { get<MichiDatabase>().replayGainDao() }
    single { get<MichiDatabase>().appDao() }
    single { get<MichiDatabase>().libraryDao() }
    single { get<MichiDatabase>().trackIdentityDao() }
    single { get<MichiDatabase>().trackLocationDao() }
    single { get<MichiDatabase>().libraryMembershipDao() }
    single { get<MichiDatabase>().normalizedPlaylistDao() }
    single { get<MichiDatabase>().syncProfileDao() }
    single { get<MichiDatabase>().syncManifestDao() }
    single<org.michimusic.data.repository.MusicLibraryRepository> {
        org.michimusic.data.repository.DefaultMusicLibraryRepository(get(), get(), get(), get())
    }
    single { SyncedTrackRepository(get(), get()) }
    single { LocalMediaRepository(androidContext(), get()) }
    single<org.michimusic.data.repository.FavoritesRepository> {
        org.michimusic.data.repository.DefaultFavoritesRepository(get())
    }
    single { PlaylistRepository(get(), get(), get()) }
    single { org.michimusic.data.repository.SafMediaRepository(androidContext(), get()) }
}
