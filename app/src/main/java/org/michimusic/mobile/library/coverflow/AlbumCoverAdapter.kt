package org.michimusic.mobile.library.coverflow

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class AlbumCoverAdapter(
    private val albums: List<CoverFlowAlbum>,
) : RecyclerView.Adapter<AlbumCoverViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumCoverViewHolder =
        AlbumCoverViewHolder.from(parent)

    override fun onBindViewHolder(holder: AlbumCoverViewHolder, position: Int) {
        holder.bind(albums[position])
    }

    override fun getItemCount(): Int = albums.size
}
