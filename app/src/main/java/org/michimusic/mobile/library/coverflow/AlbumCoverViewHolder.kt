package org.michimusic.mobile.library.coverflow

import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import org.michimusic.mobile.R
import org.michimusic.mobile.ui.theme.CoverPlaceholderColors

class AlbumCoverViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(
    LayoutInflater.from(parentView.context).inflate(R.layout.item_album_cover, parentView, false)
) {
    private val coverArt: ImageView = itemView.findViewById(R.id.cover_art)
    private val titleText: TextView = itemView.findViewById(R.id.cover_title)
    private val artistText: TextView = itemView.findViewById(R.id.cover_artist)

    private val placeholderColors = CoverPlaceholderColors

    fun bind(album: CoverFlowAlbum) {
        titleText.text = album.title
        artistText.text = album.artist
        val colorIndex = kotlin.math.abs(album.id.hashCode()) % placeholderColors.size
        val bgColor = placeholderColors[colorIndex]
        coverArt.setBackgroundColor(bgColor)

        if (album.coverUri.isNotEmpty()) {
            coverArt.load(album.coverUri) {
                crossfade(300)
            }
        }
    }

    companion object {
        fun from(parent: ViewGroup): AlbumCoverViewHolder = AlbumCoverViewHolder(parent)
    }
}