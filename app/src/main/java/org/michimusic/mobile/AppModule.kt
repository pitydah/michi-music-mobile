package org.michimusic.mobile

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.michimusic.data.dataModule
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.sync.SyncViewModel

val appModule = module {
    includes(syncModule, dataModule)

    viewModelOf(::SyncViewModel)
    viewModelOf(::AlbumsViewModel)
}
