package org.michimusic.link

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val linkModule = module {
    single { LinkDiscovery(androidContext()) }
    single { LinkSession() }
    single { LinkTransferManager(androidContext()) }
    single { LinkCoverCache(androidContext()) }
    single { TokenStore(androidContext()) }
    single { LinkPackageImporter(androidContext()) }
}
