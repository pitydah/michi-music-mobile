package org.michimusic.mobile

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.michimusic.data.dataModule
import org.michimusic.mobile.remote.RemoteViewModel
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.sync.SyncViewModel

val appModule = module {
    includes(syncModule, remoteModule, dataModule)

    viewModelOf(::SyncViewModel)
    viewModelOf(::AlbumsViewModel)
    viewModelOf(::RemoteViewModel)
}
