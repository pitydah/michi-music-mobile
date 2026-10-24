package org.michimusic.mobile.di

import org.koin.java.KoinJavaComponent
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.cache.ReplayGainDao

val trackDao: TrackDao?
    get() = runCatching { KoinJavaComponent.get<TrackDao>(TrackDao::class.java) }.getOrNull()

val playlistDao: PlaylistDao?
    get() = runCatching { KoinJavaComponent.get<PlaylistDao>(PlaylistDao::class.java) }.getOrNull()

val appDao: AppDao?
    get() = runCatching { KoinJavaComponent.get<AppDao>(AppDao::class.java) }.getOrNull()

val replayGainDao: ReplayGainDao?
    get() = runCatching { KoinJavaComponent.get<ReplayGainDao>(ReplayGainDao::class.java) }.getOrNull()

val pairedDeviceRegistry: org.michimusic.link.PairedDeviceRegistry?
    get() = runCatching { KoinJavaComponent.get<org.michimusic.link.PairedDeviceRegistry>(org.michimusic.link.PairedDeviceRegistry::class.java) }.getOrNull()

val tokenStore: org.michimusic.link.TokenStore?
    get() = runCatching { KoinJavaComponent.get<org.michimusic.link.TokenStore>(org.michimusic.link.TokenStore::class.java) }.getOrNull()

val musicLibraryRepository: org.michimusic.data.repository.MusicLibraryRepository?
    get() = runCatching { KoinJavaComponent.get<org.michimusic.data.repository.MusicLibraryRepository>(org.michimusic.data.repository.MusicLibraryRepository::class.java) }.getOrNull()

val transferCheckpointRepository: org.michimusic.core.models.TransferCheckpointRepository?
    get() = runCatching { KoinJavaComponent.get<org.michimusic.core.models.TransferCheckpointRepository>(org.michimusic.core.models.TransferCheckpointRepository::class.java) }.getOrNull()

val airCatTransferClient: org.michimusic.mobile.devices.aircat.AirCatTransferClient?
    get() = runCatching { KoinJavaComponent.get<org.michimusic.mobile.devices.aircat.AirCatTransferClient>(org.michimusic.mobile.devices.aircat.AirCatTransferClient::class.java) }.getOrNull()

val approvedSyncPlanRepository: org.michimusic.core.models.ApprovedSyncPlanRepository?
    get() = runCatching { KoinJavaComponent.get<org.michimusic.core.models.ApprovedSyncPlanRepository>(org.michimusic.core.models.ApprovedSyncPlanRepository::class.java) }.getOrNull()


