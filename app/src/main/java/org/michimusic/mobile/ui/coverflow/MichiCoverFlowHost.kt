package org.michimusic.mobile.ui.coverflow

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yarolegovich.discretescrollview.DiscreteScrollView
import org.michimusic.mobile.library.coverflow.AlbumCoverAdapter
import org.michimusic.mobile.library.coverflow.CoverFlowAlbum
import org.michimusic.mobile.library.coverflow.MichiCoverTransformer
import org.michimusic.mobile.ui.theme.GlowPink

@Composable
fun MichiCoverFlowHost(
    albums: List<CoverFlowAlbum>,
    onCurrentChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val adapter = remember { AlbumCoverAdapter() }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val dsv = DiscreteScrollView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setItemTransformer(MichiCoverTransformer())
                    setOffscreenItems(3)
                    setSlideOnFling(true)
                    isNestedScrollingEnabled = true
                    addOnItemChangedListener { view, adapterPosition ->
                        if (adapterPosition in 0 until adapter.itemCount) {
                            onCurrentChanged(adapterPosition)
                        }
                    }
                }
                dsv.adapter = adapter
                dsv
            },
            update = { view ->
                adapter.submitList(albums)
                if (view.currentItem >= albums.size) {
                    view.smoothScrollToPosition(0)
                }
            },
        )

        // sutil glow en bordes laterales
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                GlowPink.copy(alpha = 0.3f),
                                GlowPink.copy(alpha = 0.3f),
                                Color.Transparent,
                            ),
                            startX = 0f,
                            endX = size.width,
                        ),
                        cornerRadius = CornerRadius(14.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                },
        )
    }
}
