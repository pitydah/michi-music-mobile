package org.michimusic.mobile

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.michimusic.data.dataModule
import org.michimusic.link.linkModule
import org.michimusic.mobile.remote.RemoteViewModel
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.screens.SearchViewModel
import org.michimusic.mobile.screens.SyncedTracksViewModel
import org.michimusic.mobile.sync.SyncViewModel

val appModule = module {
    includes(syncModule, remoteModule, dataModule, playerModule, linkModule)

    viewModelOf(::SyncViewModel)
    viewModelOf(::AlbumsViewModel)
    viewModelOf(::RemoteViewModel)
    viewModelOf(::SyncedTracksViewModel)
    viewModelOf(::SearchViewModel)
}
