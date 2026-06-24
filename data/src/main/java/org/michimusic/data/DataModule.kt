package org.michimusic.data

import androidx.room.Room
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository

val dataModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            MichiDatabase::class.java,
            "michi-sync.db",
        ).build()
    }
    single { get<MichiDatabase>().trackDao() }
    single { SyncedTrackRepository(get()) }
    single { LocalMediaRepository(androidContext()) }
}
