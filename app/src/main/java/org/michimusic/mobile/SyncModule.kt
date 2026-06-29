package org.michimusic.mobile

import org.koin.dsl.module

val syncModule = module {
    // Sync module now delegates to linkModule from michi-link-client.
    // LinkDiscovery, LinkSession, LinkTransferManager, TokenStore, etc.
    // are registered in org.michimusic.link.linkModule.
}
