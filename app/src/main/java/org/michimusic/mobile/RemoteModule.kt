package org.michimusic.mobile

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.michimusic.mobile.remote.RemoteViewModel

val remoteModule = module {
    viewModelOf(::RemoteViewModel)
}
