package org.michimusic.mobile

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModelOf
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import org.michimusic.data.dataModule
import org.michimusic.link.linkModule
import org.michimusic.mobile.remote.RemoteViewModel
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.screens.SearchViewModel
import org.michimusic.mobile.screens.SyncedTracksViewModel
import org.michimusic.mobile.sync.SyncViewModel

val appModule = module {
    includes(syncModule, remoteModule, dataModule, playerModule, linkModule)

    single<CoroutineDispatcher> { Dispatchers.IO }

    viewModel {
        SyncViewModel(
            context = get(),
            linkDiscovery = get(),
            registry = get(),
            connectionManager = get(),
            identity = get(),
            trackRepository = get()
        )
    }
    viewModelOf(::AlbumsViewModel)
    viewModelOf(::SyncedTracksViewModel)
    viewModelOf(::PlaylistsViewModel)
    viewModelOf(::SearchViewModel)
}
