package org.michimusic.mobile.ui.coverflow

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
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
    onAlbumClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val currentChangedState = rememberUpdatedState(onCurrentChanged)
    val albumClickState = rememberUpdatedState(onAlbumClick)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DiscreteScrollView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                adapter = AlbumCoverAdapter(albums) { adapterPosition ->
                    if (adapterPosition != currentItem) {
                        scrollToPosition(adapterPosition)
                        currentChangedState.value(adapterPosition)
                    } else {
                        albumClickState.value(adapterPosition)
                    }
                }
                setItemTransformer(MichiCoverTransformer())
                setOffscreenItems(3)
                setSlideOnFling(true)
                isNestedScrollingEnabled = true
                addOnItemChangedListener { _, adapterPosition ->
                    currentChangedState.value(adapterPosition)
                }
            }
        },
        update = { view ->
            (view.adapter as? AlbumCoverAdapter)?.submitList(albums)
            if (albums.isNotEmpty() && view.currentItem !in albums.indices) {
                val safeIndex = albums.lastIndex
                view.scrollToPosition(safeIndex)
                currentChangedState.value(safeIndex)
            }
        },
    )
}
