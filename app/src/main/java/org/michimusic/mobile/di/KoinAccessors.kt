package org.michimusic.mobile.di

import org.koin.java.KoinJavaComponent
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.cache.ReplayGainDao

val trackDao: TrackDao? by lazy {
    runCatching { KoinJavaComponent.get<TrackDao>(TrackDao::class.java) }.getOrNull()
}

val playlistDao: PlaylistDao? by lazy {
    runCatching { KoinJavaComponent.get<PlaylistDao>(PlaylistDao::class.java) }.getOrNull()
}

val appDao: AppDao? by lazy {
    runCatching { KoinJavaComponent.get<AppDao>(AppDao::class.java) }.getOrNull()
}

val replayGainDao: ReplayGainDao? by lazy {
    runCatching { KoinJavaComponent.get<ReplayGainDao>(ReplayGainDao::class.java) }.getOrNull()
}
