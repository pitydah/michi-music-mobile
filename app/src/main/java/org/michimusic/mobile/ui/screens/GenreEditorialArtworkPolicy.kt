package org.michimusic.mobile.ui.screens

import org.michimusic.core.intelligence.MichiGenreAbsencePolicy

/**
 * Source artwork inventory is not a music taxonomy. It never creates genre
 * groups or changes Track.genre; a missing/unfinished asset leaves Compose intact.
 * Names correspond to the 312 individual WebP source cards in UI7/Editorial/recortadas.
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

    // UI7 lamina 28-39 (indices 217-312): user-approved complete 2026-11-14.
    "afro_cuban_jazz", "anatolian_rock", "arabic_pop", "art_pop", "art_rock", "azerbaijani_mugham",
    "balkan_brass", "bard_song", "baul", "bhajan", "bhangra", "bitpop",
    "bollywood", "calypso", "candombe", "cantopop", "carnatic_classical", "celtic",
    "cha_cha_cha", "chacarera", "chamame", "changui", "chiptune", "coldwave",
    "dabke", "dangdut", "danzon", "dark_ambient", "darkwave", "desert_blues",
    "desi_hip_hop", "dhrupad", "ebm", "electro_tango", "enka", "ethio_jazz",
    "folk_punk", "folktronica", "gamelan", "ghazal", "glitch", "gnawa",
    "gqom", "guajira_cubana", "guaracha", "gypsy_jazz", "highlife", "hindustani_classical",
    "indian_electronic", "jazz_funk", "jazztronica", "khaliji", "kizomba", "klezmer",
    "kompa", "kuduro", "kurdish_folk", "mahraganat", "makossa", "mambo",
    "mandopop", "mbalax", "minimal_wave", "modal_jazz", "ndw", "no_wave",
    "noise_rock", "nu_jazz", "pansori", "persian_classical", "polka", "post_metal",
    "post_punk_revival", "qawwali", "rai", "rebetiko", "rumba_cubana", "russian_chanson",
    "russian_folk", "russian_post_punk", "russian_rock", "russian_romance", "sevdalinka", "soca",
    "soukous", "soul_jazz", "soviet_pop", "sovietwave", "spiritual_jazz", "tarab",
    "thai_luk_thung", "thumri", "timba", "turkish_classical", "visual_kei", "zouk",

    // UI7-665 (catalog positions 313-672, minus the seven withdrawn ones): imported
    // from the pending batch on 2026-11-17. Bytes, dimensions and SHA-256 were verified
    // against the integration CSV; art review is still pending, so every one of these
    // rows carries IMPORTED_PENDING_VISUAL_APPROVAL in the pack manifest.
    "aak", "abhang", "aboriginal_australian_music", "abstract_hip_hop", "acid_rock", "acid_techno", "acousmatic", "afro_brazilian", "afro_peruvian",
    "albanian_iso_polyphony", "albazo", "alegrias", "anarcho_punk", "andalusian_classical", "apala", "arabic_classical", "ars_nova", "ars_subtilior",
    "assiko", "avant_garde_metal", "axe", "baiao", "bal_musette", "baltimore_club", "bambuco", "bangladeshi_rock", "barber_beats",
    "bassline", "batuque_cabo_verdiano", "bhojpuri_pop", "big_room_house", "bikutsi", "blackgaze", "bleep_techno", "bomba", "bongo_flava",
    "boogie_woogie", "bouyon", "brega_funk", "breton_folk", "broken_beat", "buleria", "bulgarian_horo", "bullerengue", "burmese_classical",
    "cakewalk", "cambodian_rock", "campursari", "cante_alentejano", "canterbury_scene", "canzone_napoletana", "caporales", "carimbo", "carnavalito",
    "celtic_punk", "chaabi", "chakacha", "chalga", "chamber_jazz", "charleston", "chicago_footwork", "chicha_peruana", "chilena_guerrerense",
    "chimurenga", "choro", "cocek", "coladeira", "copla_espanola", "coupe_decale", "crunk", "crust_punk", "csardas",
    "currulao", "dandiya", "dark_cabaret", "dark_jazz", "d_beat", "descarga", "desert_rock", "digital_hardcore", "djent",
    "dungeon_synth", "egyptian_belly_dance", "electroacoustic", "electronic", "emoviolence", "entekhno", "estampie", "ethno_jazz", "ethnotronica",
    "eurobeat", "experimental_hip_hop", "extratone", "fado_de_coimbra", "fandango", "finnish_humppa", "foxtrot", "freak_folk", "free_funk",
    "free_improvisation", "frevo", "fuji", "funana", "funeral_doom_metal", "future_funk", "future_garage", "gaana", "gabber",
    "gagaku", "gaita_zuliana", "gangsta_rap", "garage_house", "garba", "gato_argentino", "georgian_polyphonic_singing", "ghetto_house", "global_bass",
    "goa_trance", "go_go", "gong_kebyar", "gregorian_chant", "grindcore", "guaguanco", "gufeng", "gugak", "gumbe",
    "hapa_haole", "happy_hardcore", "hardanger_fiddle_music", "hardbass", "hardgroove_techno", "hawaiian_slack_key", "hiplife", "honky_tonk", "hsaing_waing",
    "huapango_arribeno", "huaylarsh", "huayno_con_arpa", "hula_chant", "hyphy", "iraqi_maqam", "irish_traditional", "isicathamiya", "jaipongan",
    "jangle_pop", "jazz_house", "jeongak", "jiangnan_sizhu", "joropo", "jota", "juju", "juke", "kacapi_suling",
    "kaneka", "kansas_city_jazz", "kayokyoku", "kazakh_kui", "keroncong", "khayal", "koplo", "kulintang", "kurdish_pop",
    "kuthu", "kwaito", "kyrgyz_kuu", "laiko", "lavani", "lied", "lo_fi_hip_hop", "lo_fi_house", "lovers_rock",
    "luk_krung", "madchester", "madrigal", "mahori", "makina", "malambo", "malhun", "mande_music", "manele",
    "maori_waiata", "mapale", "maracatu", "maskandi", "memphis_rap", "mento", "merecumbe", "merengue_tipico", "merseybeat",
    "miami_bass", "microhouse", "milonga", "minimalism", "minnesang", "minyo", "mizrahi_music", "mongolian_khoomei", "moombahton",
    "morin_khuur_music", "mor_lam", "morna", "motet", "muneira", "murga_uruguaya", "musica_de_quena", "musica_de_sikus", "musica_sefardi",
    "musica_vasca", "musique_concrete", "nanyin", "ndombolo", "neoperreo", "neo_psychedelia", "nepali_folk", "neurofunk", "new_orleans_bounce",
    "new_orleans_jazz", "nwobhm", "oi", "old_time", "orchestral_jazz", "organic_house", "oriental_fusion", "outlaw_country", "ozgun_muzik",
    "pacific_reggae", "paisley_underground", "pakistani_pop", "palm_wine", "palo_de_mayo", "parranda_garifuna", "partido_alto", "pasacalle_andino", "pasillo_ecuatoriano",
    "pasodoble", "persian_dastgah", "persian_pop", "piedmont_blues", "pinoy_hip_hop", "pinoy_rock", "pinpeat", "plena", "polynesian_pop",
    "porro_colombiano", "post_bop", "powerviolence", "proto_punk", "psychobilly", "punta_garifuna", "quickstep", "rabindra_sangeet", "radif",
    "raga_rock", "ragga_jungle", "refalosa", "riot_grrrl", "rkt", "rock_andaluz", "rock_in_opposition", "romanian_doina", "rumba_catalana",
    "sajuriana", "sakara", "salsa_dura", "samba_cancao", "samba_de_roda", "samba_enredo", "samba_rock", "sami_joik", "samulnori",
    "sanjuanito", "sardana", "saya_afroboliviana", "schlager", "schottische", "schranz", "scottish_folk", "sea_shanty", "sega",
    "seguiriya", "semba", "serialism", "sevillanas", "shaabi", "shangaan_electro", "shashmaqom", "shibuya_kei", "shidaiqu",
    "sin_clasificar", "singeli", "sirilla_chilota", "ska_punk", "slowcore", "sludge_metal", "solea", "songo", "son_huasteco",
    "son_istmeno", "son_jarocho", "son_montuno", "southern_hip_hop", "southern_rock", "space_rock", "spectralism", "speedcore", "speed_metal",
    "suomisaundi", "sutartines", "swedish_polska", "taarab", "tappa", "taquirari", "tarana", "tarantella", "technical_death_metal",
    "techstep", "tecnobrega", "texas_blues", "third_stream", "tinku", "tizita", "tondero", "t_pop", "trap_soul",
    "tropicalia", "trot", "tsonga_disco", "tsugaru_jamisen", "turbo_folk", "turkish_arabesque", "turkish_folk", "turkish_pop", "uk_drill",
    "uk_funky", "uk_hardcore", "ukrainian_dumy", "urtiin_duu", "uyghur_muqam", "vals_chilote", "vocal_jazz", "v_pop", "welsh_folk",
    "west_african_griot", "west_coast_jazz", "western_swing", "witch_house", "xaxado", "xote", "yaravi", "yayue", "zamba_argentina",
    "zarzuela", "zeuhl",
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

/** The only poster the absence bucket can ever own. It is not a music genre. */
private const val absenceEditorialSlug = "sin_clasificar"

/** Exact approved slug used by both complete posters and derived Editorial art. */
internal fun editorialArtworkSlug(genreTitle: String): String? {
    // The unclassified bucket owns a poster, but it is not a genre: MI-26 resolves its
    // label to no key at all, so the binding is named here instead of being derived from
    // words that describe an absence. No musical style can ever claim this slug.
    // Only the absence identity itself owns this poster: the bare internal key or the
    // visible bucket label. Other absence markers (empty, "Unknown", "N/A") are not a
    // genre either, but letting them all borrow this crop would make the bucket look
    // like a style that every unlabelled value falls into.
    if (genreTitle.trim() == MichiGenreAbsencePolicy.UNCLASSIFIED_KEY ||
        MichiGenreAbsencePolicy.fold(genreTitle) ==
        MichiGenreAbsencePolicy.fold(MichiGenreAbsencePolicy.UNCLASSIFIED_LABEL)
    ) {
        return absenceEditorialSlug.takeIf { it in editorialSourceKeys }
    }
    val id = genreGroupingKey(genreTitle)
    if (id.isBlank()) return null
    val slug = when (id) {
        "r&b" -> "rn_b"
        "edm" -> "e_d_m"
        "hi-nrg", "hi nrg" -> "hi_n_r_g"
        "drum bass" -> "drum_bass"
        else -> {
            // Do not turn unapproved punctuation into somebody else's artwork.
            if (!productionGroupingPattern.matches(id)) return null
            id.replace(' ', '_').replace('-', '_')
        }
    }
    return slug.takeIf { it in editorialSourceKeys }
}

/** This path targets ONLY offline-corrected, approved, text-free WebP derivatives. */
internal fun editorialProductionAssetPath(
    genreTitle: String,
    treatment: GenreEditorialTreatment,
    columns: Int,
): String? {
    require(columns >= 1)
    val slug = editorialArtworkSlug(genreTitle) ?: return null
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
