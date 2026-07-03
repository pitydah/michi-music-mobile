package org.michimusic.mobile.library.coverflow

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class AlbumCoverAdapter(
    albums: List<CoverFlowAlbum>,
    private val onAlbumClick: (Int) -> Unit = {},
) : RecyclerView.Adapter<AlbumCoverViewHolder>() {
    private var albums: List<CoverFlowAlbum> = albums

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumCoverViewHolder =
        AlbumCoverViewHolder.from(parent)

    override fun onBindViewHolder(holder: AlbumCoverViewHolder, position: Int) {
        holder.bind(albums[position])
        holder.itemView.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) {
                onAlbumClick(adapterPosition)
            }
        }
    }

    override fun getItemCount(): Int = albums.size

    override fun getItemId(position: Int): Long = albums[position].id.hashCode().toLong()

    fun submitList(newAlbums: List<CoverFlowAlbum>) {
        val oldAlbums = albums
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldAlbums.size

            override fun getNewListSize(): Int = newAlbums.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldAlbums[oldItemPosition].id == newAlbums[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldAlbums[oldItemPosition] == newAlbums[newItemPosition]
        })
        albums = newAlbums
        diff.dispatchUpdatesTo(this)
    }
}
