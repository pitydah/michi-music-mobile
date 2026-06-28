package org.michimusic.player

import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.ReplayGainDao

object PlayerDependencies {
    @JvmStatic
    var replayGainDao: ReplayGainDao? = null
    @JvmStatic
    var appDao: AppDao? = null
}
