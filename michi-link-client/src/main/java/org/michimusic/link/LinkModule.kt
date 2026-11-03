package org.michimusic.link

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val linkModule = module {
    single { LinkDiscovery(androidContext()) }

    single { LinkTransferManager(androidContext(), getOrNull()) }
    single { LinkCoverCache(androidContext()) }
    single { TokenStore(androidContext()) }
    // Resolving the identity opens the Keystore, which must never happen while the
    // first frame is being composed. The authority publishes Loading/Ready/Failed
    // and callers await it instead of blocking the main thread.
    single { org.michimusic.link.identity.MichiIdentityAuthority(androidContext()) }
    single { PairedDeviceRegistry(androidContext(), get()) }
    single { ConnectionManager(get(), get()) }
    single { LinkPackageImporter(androidContext()) }
}
