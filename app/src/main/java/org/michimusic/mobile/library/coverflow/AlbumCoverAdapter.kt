package org.michimusic.mobile.library.coverflow

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class AlbumCoverAdapter : RecyclerView.Adapter<AlbumCoverViewHolder>() {

    private var albums: List<CoverFlowAlbum> = emptyList()

    fun submitList(newList: List<CoverFlowAlbum>) {
        albums = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumCoverViewHolder =
        AlbumCoverViewHolder.from(parent)

    override fun onBindViewHolder(holder: AlbumCoverViewHolder, position: Int) {
        holder.bind(albums[position])
    }

    override fun getItemCount(): Int = albums.size
}
