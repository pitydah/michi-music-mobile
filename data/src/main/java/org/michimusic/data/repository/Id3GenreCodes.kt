package org.michimusic.data.repository

/** Published first 80 ID3v1 genre indices; no guess is made for unassigned codes. */
internal object Id3GenreCodes {
    private val names = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
        "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae",
        "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
        "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz & Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game",
        "Sound Clip", "Gospel", "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space",
        "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
        "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk",
        "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave",
        "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka",
        "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )
    fun nameForIndex(index: Int): String? = names.getOrNull(index)?.takeUnless { it == "Other" }

    /** TCON numeric-only or (n) uses ID3 genre codes; other strings stay verbatim. */
    fun tconValues(text: String): List<String> = text.split('\u0000').mapNotNull { raw ->
        val part = raw.trim()
        if (part.isEmpty()) null else {
            val bracketed = Regex("^\\(([0-9]{1,3})\\)$").matchEntire(part)
            val digits = bracketed?.groupValues?.get(1) ?: part.takeIf { it.matches(Regex("[0-9]{1,3}")) }
            if (digits != null) nameForIndex(digits.toInt()) else part
        }
    }
}
