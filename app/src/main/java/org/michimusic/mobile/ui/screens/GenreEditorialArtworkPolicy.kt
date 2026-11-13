package org.michimusic.mobile.ui.screens

/**
 * Source artwork inventory is not a music taxonomy. It never creates genre
 * groups or changes Track.genre; a missing/unfinished asset leaves Compose intact.
 * Names correspond to 176 individual WebP source cards in UI7/Editorial/recortadas.
 */
internal val editorialSourceKeys: Set<String> = setOf(
    "acid_house", "acid_jazz", "afro_house", "afrobeat", "afrobeats", "afropop", "alternative_rock", "alternativo", "amapiano",
    "ambient", "americana", "bachata", "balada", "banda", "bandas_sonoras", "bebop", "big_band", "big_beat",
    "black_metal", "bluegrass", "bluegrass_gospel", "blues", "bolero", "boom_bap", "bossa_nova", "breakbeat", "britpop",
    "cancion_de_autor", "canto_nuevo", "chanson", "chillout", "chillwave", "city_pop", "clasica", "cloud_rap", "cool_jazz",
    "corridos_tumbados", "country", "cueca", "cumbia", "dance_pop", "dancehall", "death_metal", "deathcore", "deep_house",
    "delta_blues", "detroit_techno", "disco", "dixieland", "doo_wop", "doom_metal", "dream_pop", "drill", "drum_bass",
    "dub", "dubstep", "e_d_m", "electro_swing", "electroclash", "electronica", "electropop", "emo", "eurodance",
    "fado", "flamenco", "folk", "free_jazz", "freestyle", "funk", "funk_carioca", "future_bass", "g_funk",
    "garage_rock", "glam_rock", "gospel", "gospel_blues", "grime", "grunge", "hard_rock", "hardcore", "hardstyle",
    "heavy_metal", "hi_n_r_g", "hip_hop", "house", "huayno", "hyperpop", "indie", "indie_pop", "industrial",
    "italo_disco", "j_pop", "jazz", "jazz_fusion", "jersey_club", "jump_blues", "jungle", "k_pop", "krautrock",
    "latin", "latin_jazz", "latin_pop", "lo_fi", "mariachi", "merengue", "metal", "metalcore", "minimal_techno",
    "motown", "musica_barroca", "neo_soul", "new_jack_swing", "new_romantic", "new_wave", "norteno", "nu_disco", "nu_metal",
    "nueva_cancion_chilena", "nueva_ola_chilena", "opera", "pasillo", "philly_soul", "phonk", "pop", "pop_punk", "pop_rock",
    "post_punk", "post_rock", "power_metal", "power_pop", "progresivo", "progressive_house", "progressive_metal", "psychedelic_rock", "psychedelic_soul",
    "psytrance", "punk", "ragtime", "ranchera", "rap", "reggae", "reggaeton", "rn_b", "rock",
    "rock_roll", "rockabilly", "rocksteady", "romantica", "salsa", "samba", "sertanejo", "shoegaze", "ska",
    "soft_rock", "son_cubano", "soul", "stoner_rock", "stride_piano", "surf_rock", "swing", "symphonic_metal", "synth_pop",
    "synthwave", "tango", "techno", "thrash_metal", "trance", "trap", "trip_hop", "trova", "uk_garage",
    "vallenato", "vals_criollo", "vaporwave", "yacht_rock", "ye_ye",

    "blues_rock",
    "breakcore",
    "champeta",
    "chicago_blues",
    "country_rock",
    "cuarteto",
    "cueca_brava",
    "cumbia_chilena",
    "cumbia_peruana",
    "cumbia_villera",
    "dembow",
    "downtempo",
    "electro_house",
    "folk_metal",
    "folk_rock",
    "forro",
    "french_house",
    "gothic_metal",
    "gothic_rock",
    "groove_metal",
    "hard_bop",
    "idm",
    "industrial_metal",
    "j_rock",
    "jazz_rap",
    "liquid_drum_bass",
    "math_rock",
    "melodic_death_metal",
    "melodic_techno",
    "mpb",
    "musica_de_camara",
    "neoclasica",
    "pagode",
    "post_hardcore",
    "rock_en_espanol",
    "salsa_romantica",
    "smooth_jazz",
    "tech_house",
    "tonada_chilena",
    "trap_latino",
)

private val approvedSlugPattern = Regex("[a-z0-9]+(?:_[a-z0-9]+)*")
private val sha256Pattern = Regex("[0-9a-f]{64}")
private val productionGroupingPattern = Regex("[a-z0-9]+(?:[ _-][a-z0-9]+)*")
private val sourceDriveIdPattern = Regex("[A-Za-z0-9_-]{10,}")
private const val manifestVersion = "# UI7_EDITORIAL_PRODUCTION_MANIFEST_V1"
private const val maxManifestChars = 250_000
private const val maxArtworkPixels = 3_686_400L

/** Accepted *composition* proportions. They do not alter Compose's dp dimensions. */
internal fun editorialProductionGeometryValid(variant: String, width: Int, height: Int): Boolean {
    if (width !in 320..4096 || height !in 240..4096 ||
        width.toLong() * height.toLong() > maxArtworkPixels) return false
    val ratio = width.toDouble() / height.toDouble()
    return when (variant) {
        "cover" -> ratio in 1.40..2.20
        "portrait_left", "portrait_right" -> ratio in 0.58..0.85
        "single" -> ratio in 1.25..2.10
        else -> false
    }
}

internal enum class EditorialProductionVariant(val suffix: String) {
    COVER("cover"), PORTRAIT_LEFT("portrait_left"),
    PORTRAIT_RIGHT("portrait_right"), SINGLE_COLUMN("single")
}

/** This path targets ONLY offline-corrected, approved, text-free WebP derivatives. */
internal fun editorialProductionAssetPath(
    genreTitle: String,
    treatment: GenreEditorialTreatment,
    columns: Int,
): String? {
    require(columns >= 1)
    val id = genreGroupingKey(genreTitle)
    if (id.isBlank() || id == "sin clasificar" || id == "unknown" || id == "<unknown>") return null
    val slug = when (id) {
        "r&b" -> "rn_b"
        "edm" -> "e_d_m"
        "hi-nrg", "hi nrg" -> "hi_n_r_g"
        "drum bass" -> "drum_bass"
        else -> {
            // Do not coerce arbitrary punctuation into another genre's artwork.
            if (!productionGroupingPattern.matches(id)) return null
            id.replace(' ', '_').replace('-', '_')
        }
    }
    if (slug !in editorialSourceKeys) return null
    val variant = if (columns == 1) EditorialProductionVariant.SINGLE_COLUMN
        else when (treatment) {
            GenreEditorialTreatment.COVER -> EditorialProductionVariant.COVER
            GenreEditorialTreatment.PORTRAIT_LEFT -> EditorialProductionVariant.PORTRAIT_LEFT
            GenreEditorialTreatment.PORTRAIT_RIGHT -> EditorialProductionVariant.PORTRAIT_RIGHT
        }
    return "ui7/editorial/production/genre_${slug}_${variant.suffix}.webp"
}

/** Offline-only publication manifest. No entries => no images. Any malformed line fails closed.
 * Format: genre_key, variant, filename, sha256, width_px, height_px,
 * source_drive_file_id, approval, text_free, frame_free, safe_layout (TAB separated).
 * SHA and pixel dimensions are verified by tools/validate_ui7_editorial_assets.py.
 */
internal fun editorialApprovedAssetNames(manifest: String, installed: Set<String>): Set<String> {
    if (manifest.length > maxManifestChars) return emptySet()
    val approved = linkedSetOf<String>()
    val seen = hashSetOf<String>()
    val allowedVariants = EditorialProductionVariant.entries.map { it.suffix }.toSet()
    var headerSeen = false
    for (line in manifest.lineSequence()) {
        if (!headerSeen) {
            if (line != manifestVersion) return emptySet()
            headerSeen = true
            continue
        }
        if (line.isBlank() || line.startsWith('#')) continue
        val columns = line.split('\t')
        if (columns.size != 11) return emptySet()
        val slug = columns[0]; val variant = columns[1]; val filename = columns[2]
        val sha = columns[3]; val width = columns[4]; val height = columns[5]
        val source = columns[6]; val approval = columns[7]; val textFree = columns[8]
        val frameFree = columns[9]; val safe = columns[10]
        val w = width.toIntOrNull() ?: return emptySet()
        val h = height.toIntOrNull() ?: return emptySet()
        if (slug !in editorialSourceKeys || !approvedSlugPattern.matches(slug) ||
            variant !in allowedVariants || filename != "genre_${slug}_${variant}.webp" ||
            !sha256Pattern.matches(sha) || !editorialProductionGeometryValid(variant, w, h) ||
            !sourceDriveIdPattern.matches(source) || approval != "APPROVED" ||
            textFree != "YES" || frameFree != "YES" || safe != "YES" ||
            !seen.add(filename)
        ) return emptySet()
        if (filename !in installed) return emptySet()
        approved.add(filename)
    }
    if (!headerSeen) return emptySet()
    // Runtime also fails closed for stray packaged WebPs; not just for bad rows.
    if (installed.any { it.endsWith(".webp") && it !in seen }) return emptySet()
    return approved
}
