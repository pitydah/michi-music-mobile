package org.michimusic.mobile

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.michimusic.link.LinkTransferManager
import org.michimusic.mobile.devices.aircat.AirCatLeaseCoordinator
import org.michimusic.mobile.devices.aircat.AirCatTransferClient
import org.michimusic.mobile.devices.aircat.DefaultAirCatTransferClient
import org.michimusic.mobile.devices.aircat.AirCatHomeAuthorityProvider
import org.michimusic.mobile.devices.aircat.AirCatSignedGrantSecurity

val syncModule = module {
    // The file executor is owned by :aircat-transfer, not by :michi-link-client.
    // Koin gets exactly one instance, shared by SyncWorker and AirCat.
    single { LinkTransferManager(androidContext(), getOrNull()) }
    single { AirCatLeaseCoordinator(androidContext()) }
    // Empty until explicit, operator-confirmed administrative QR enrollment.
    // Trust never comes from UDP, mDNS, /server/info or legacy device tokens.
    single { org.michimusic.mobile.devices.aircat.AirCatHomeEnrollmentStore(androidContext()) }
    single<AirCatHomeAuthorityProvider> {
        get<org.michimusic.mobile.devices.aircat.AirCatHomeEnrollmentStore>()
    }
    single { AirCatSignedGrantSecurity(get()) }
    single<AirCatTransferClient> {
        DefaultAirCatTransferClient(
            transferManager = get(),
            pairedDeviceRegistry = get(),
            libraryRepository = getOrNull(),
            checkpointRepository = getOrNull(),
            resumeLease = get(),
            signedGrantSecurity = get(),
        )
    }
}

