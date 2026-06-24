package org.michimusic.mobile.library.coverflow

import android.view.View
import com.yarolegovich.discretescrollview.transform.DiscreteScrollItemTransformer

class MichiCoverTransformer : DiscreteScrollItemTransformer {

    companion object {
        private const val MAX_ROTATION = 60f
        private const val CENTER_SCALE = 1.05f
        private const val MIN_SCALE = 0.45f
        private const val SCALE_DECAY = 0.22f
        private const val SCALE_DEAD_ZONE = 0.2f
        private const val ALPHA_FADE_RATE = 0.12f
    }

    override fun transformItem(view: View, position: Float) {
        val absPos = kotlin.math.abs(position)
        val scale = CENTER_SCALE - kotlin.math.max(0f, absPos - SCALE_DEAD_ZONE) * SCALE_DECAY
        view.scaleX = scale.coerceIn(MIN_SCALE, CENTER_SCALE)
        view.scaleY = scale.coerceIn(MIN_SCALE, CENTER_SCALE)

        val rotation = when {
            absPos <= 0.10f -> 0f
            position < 0 -> -MAX_ROTATION.coerceAtMost(absPos * 50f)
            else -> MAX_ROTATION.coerceAtMost(absPos * 50f)
        }
        view.rotationY = rotation

        view.alpha = (1f - absPos * ALPHA_FADE_RATE).coerceIn(0f, 1f)
        view.elevation = (1f - absPos * 0.15f).coerceIn(0f, 1f)
    }
}
