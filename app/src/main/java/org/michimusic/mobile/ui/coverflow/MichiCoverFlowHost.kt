package org.michimusic.mobile.ui.coverflow

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.RecyclerView
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
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DiscreteScrollView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                adapter = AlbumCoverAdapter(albums)
                setItemTransformer(MichiCoverTransformer())
                setOffscreenItems(3)
                setSlideOnFling(true)
                isNestedScrollingEnabled = true
                addOnItemChangedListener { _, adapterPosition ->
                    onCurrentChanged(adapterPosition)
                }
            }
        },
    )
}
