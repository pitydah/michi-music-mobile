package org.michimusic.mobile

import org.koin.dsl.module
import org.michimusic.mobile.devices.aircat.AirCatTransferClient
import org.michimusic.mobile.devices.aircat.DefaultAirCatTransferClient

val syncModule = module {
    single<AirCatTransferClient> {
        DefaultAirCatTransferClient(
            transferManager = get(),
            pairedDeviceRegistry = get(),
            libraryRepository = getOrNull(),
        )
    }
}

