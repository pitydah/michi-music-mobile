package org.michimusic.mobile.ui.coverflow

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yarolegovich.discretescrollview.DiscreteScrollView
import org.michimusic.mobile.library.coverflow.AlbumCoverAdapter
import org.michimusic.mobile.library.coverflow.CoverFlowAlbum
import org.michimusic.mobile.library.coverflow.MichiCoverTransformer

@Composable
fun MichiCoverFlowHost(
    albums: List<CoverFlowAlbum>,
    onCurrentChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val adapter = remember { AlbumCoverAdapter() }

    AndroidView(
        modifier = modifier,
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
}
