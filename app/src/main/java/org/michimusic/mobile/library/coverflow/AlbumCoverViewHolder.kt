package org.michimusic.mobile.library.coverflow

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil3.asImage
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import org.michimusic.mobile.R

class AlbumCoverViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(
    LayoutInflater.from(parentView.context).inflate(R.layout.item_album_cover, parentView, false)
) {
    private val coverArt: ImageView = itemView.findViewById(R.id.cover_art)
    private val titleText: TextView = itemView.findViewById(R.id.cover_title)
    private val artistText: TextView = itemView.findViewById(R.id.cover_artist)
    private val metaText: TextView = itemView.findViewById(R.id.cover_meta)

    fun bind(album: CoverFlowAlbum) {
        titleText.text = album.title
        artistText.text = album.artist
        metaText.text = "${album.year} · ${album.trackCount} canciones"
        itemView.contentDescription = "${album.title}, ${album.artist}"
        if (album.coverUri.isNotEmpty()) {
            val placeholderImage = ContextCompat
                .getDrawable(itemView.context, R.drawable.coverflow_placeholder)
                ?.asImage()
            coverArt.setPadding(0, 0, 0, 0)
            coverArt.scaleType = ImageView.ScaleType.CENTER_CROP
            coverArt.load(album.coverUri) {
                crossfade(300)
                placeholder(placeholderImage)
                fallback(placeholderImage)
                error(placeholderImage)
            }
        } else {
            coverArt.dispose()
            val logoPadding = itemView.resources.getDimensionPixelSize(R.dimen.coverflow_logo_padding)
            coverArt.setPadding(logoPadding, logoPadding, logoPadding, logoPadding)
            coverArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            coverArt.setBackgroundResource(R.drawable.coverflow_placeholder)
            coverArt.setImageResource(R.mipmap.michi_logo)
        }
    }

    companion object {
        fun from(parent: ViewGroup): AlbumCoverViewHolder = AlbumCoverViewHolder(parent)
    }
}
