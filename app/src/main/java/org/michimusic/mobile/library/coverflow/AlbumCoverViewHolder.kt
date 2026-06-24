package org.michimusic.mobile.library.coverflow

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import org.michimusic.mobile.R

class AlbumCoverViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(
    LayoutInflater.from(parentView.context).inflate(R.layout.item_album_cover, parentView, false)
) {
    private val coverArt: View = itemView.findViewById(R.id.cover_art)
    private val titleText: TextView = itemView.findViewById(R.id.cover_title)
    private val artistText: TextView = itemView.findViewById(R.id.cover_artist)

    fun bind(album: CoverFlowAlbum) {
        titleText.text = album.title
        artistText.text = album.artist
        val colors = listOf(
            0xFF6B5B95.toInt(),
            0xFFE56399.toInt(),
            0xFF4A8FE7.toInt(),
            0xFF7ED321.toInt(),
            0xFFF5A623.toInt(),
        )
        coverArt.setBackgroundColor(colors[album.id.toIntOrNull()?.let { (it - 1) % colors.size } ?: 0])
    }

    companion object {
        fun from(parent: ViewGroup): AlbumCoverViewHolder = AlbumCoverViewHolder(parent)
    }
}
