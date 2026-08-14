package org.michimusic.link

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val linkModule = module {
    single { LinkDiscovery(androidContext()) }

    single { LinkTransferManager(androidContext()) }
    single { LinkCoverCache(androidContext()) }
    single { TokenStore(androidContext()) }
    single { org.michimusic.link.identity.MichiIdentity(androidContext()) }
    single { PairedDeviceRegistry(androidContext(), get()) }
    single { ConnectionManager(get()) }
    single { LinkPackageImporter(androidContext()) }
}
