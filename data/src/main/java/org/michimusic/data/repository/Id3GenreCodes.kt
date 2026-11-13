package org.michimusic.data.repository

/** Published ID3v1/Winamp indices 0..147; reserved and unassigned codes stay Unknown. */
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
        "Folk", "Folk-Rock", "National Folk", "Swing", "Fast-Fusion",
        "Bebop", "Latin", "Revival", "Celtic", "Bluegrass",
        "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock",
        "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic",
        "Humour", "Speech", "Chanson", "Opera", "Chamber Music",
        "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove",
        "Satire", "Slow Jam", "Club", "Tango", "Samba",
        "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House",
        "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore",
        "Terror", "Indie", "BritPop", "Afro-Punk", "Polsk Punk",
        "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
        "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal",
        "Anime", "JPop", "Synthpop",
    )
    fun nameForIndex(index: Int): String? = names.getOrNull(index)?.takeUnless { it == "Other" }

    /**
     * TCON numeric-only, (n) and consecutive (n)(m) use published ID3 codes.
     * A literal semicolon, slash or mixed user text is never split or inferred.
     */
    fun tconValues(text: String): List<String> {
        if (text.length > 8_192) return emptyList()
        val fields = text.split('\u0000')
        if (fields.size > 256) return emptyList()
        val one = Regex("[0-9]{1,3}")
        val bracket = Regex("\\(([0-9]{1,3})\\)")
        val adjacent = Regex("(?:\\([0-9]{1,3}\\)){2,32}")
        val expanded = fields.flatMap { raw ->
            val part = raw.trim()
            if (part.isEmpty()) emptyList() else if (adjacent.matches(part)) {
                // Reject the ENTIRE field on any unknown code, never a partial genre list.
                val indices = bracket.findAll(part).map { it.groupValues[1].toInt() }.toList()
                val names = indices.map(::nameForIndex)
                if (names.any { it == null }) emptyList() else names.filterNotNull()
            } else {
                val digits = bracket.matchEntire(part)?.groupValues?.get(1)
                    ?: part.takeIf(one::matches)
                if (digits == null) listOf(part)
                else listOfNotNull(nameForIndex(digits.toInt()))
            }
        }
        // A consumer may hold 32 independent genre fields, never an undiagnosed
        // truncated subset. Prevent large allocations and mismatched legacy values.
        if (expanded.size > 32 || expanded.sumOf(String::length) > 8_192 ||
            expanded.any { it.length > 2_048 || '\uFFFD' in it ||
                it.any(Char::isISOControl) }) return emptyList()
        return expanded
    }
}
