package org.michimusic.mobile

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.michimusic.sync.CoverCache
import org.michimusic.sync.DiscoveryClient
import org.michimusic.sync.SyncSession
import org.michimusic.sync.SyncTransferManager

val syncModule = module {
    single { DiscoveryClient(androidContext()) }
    single { SyncSession() }
    single { SyncTransferManager(androidContext()) }
    single { CoverCache(androidContext()) }
}
