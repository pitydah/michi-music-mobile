package org.michimusic.mobile.ui.screens

/**
 * K02 OFFLINE editorial sample. All descriptions are original Spanish paraphrases;
 * references are source provenance, NOT content copied under an assumed license.
 *
 * Exact UI7 canonical keys only. No fuzzy search, artist inference, web calls,
 * database writes, or automatic upstream MusicBrainz identities.
 */
internal data class AtlasMilestone(
    val period: String,
    val title: String,
    val description: String,
    val source: AtlasSource,
)

internal data class AtlasMusicScene(
    val place: String,
    val period: String,
    val context: String,
    val source: AtlasSource,
)

internal data class AtlasEditorialPage(
    val genreId: String,
    val displayName: String,
    val overview: String,
    val overviewSource: AtlasSource,
    val milestones: List<AtlasMilestone>,
    val scenes: List<AtlasMusicScene>,
    val relations: List<AtlasVerifiedRelation>,
)

/** Bounded, constant-size, lazy registry loaded only when a genre Atlas opens. */
internal object MichiAtlasEditorialCatalog {
    private val pop1984 = AtlasSource(
        "Rock & Roll Hall of Fame — 1984", "https://rockhall.com/exhibitions/1984/",
    )
    private val duran = AtlasSource(
        "Rock & Roll Hall of Fame — Duran Duran", "https://rockhall.com/inductees/duran-duran/",
    )
    private val newOrder = AtlasSource(
        "Rock & Roll Hall of Fame — Joy Division / New Order",
        "https://rockhall.com/es/inductees/joy-division-new-order/",
    )
    private val wuerzburg = AtlasSource(
        "Universidad de Würzburg — Krautrock",
        "https://www.uni-wuerzburg.de/en/news-and-events/einblick/single/news/krautrock/",
    )
    private val michigan = AtlasSource(
        "University of Michigan Press — Krautrock: German Music in the Seventies",
        "https://press.umich.edu/Books/K/Krautrock2",
    )
    private val abc = AtlasSource(
        "ABC Double J — Guía de hip-hop de los noventa: trip-hop",
        "https://www.abc.net.au/listen/doublej/music-reads/features/beginners-guide-to-90s-hip-hop-j-files-feature/102674300",
    )
    private val cambridge = AtlasSource(
        "Cambridge University Press — Just Don't Call it Trip Hop",
        "https://www.cambridge.org/core/journals/organised-sound/article/abs/just-dont-call-it-trip-hop-reconciling-the-bristol-sound-style-with-the-trip-hop-genre/B4944FEFB7C30977DA7CF0CB3AC07465",
    )

    private val zdfNdw = AtlasSource(
        "ZDFinfo — Neue Deutsche Welle",
        "https://presseportal.zdf.de/pressemappe/neue-deutsche-welle",
    )
    private val sageDark = AtlasSource(
        "SAGE Encyclopedia of Music and Culture — Darkwave",
        "https://doi.org/10.4135/9781483317731.n218",
    )
    private val ebscoArt = AtlasSource(
        "EBSCO Research — Art Rock",
        "https://www.ebsco.com/research-starters/music/art-rock",
    )
    private val folkwaysSon = AtlasSource(
        "Smithsonian Folkways — Sextetos Cubanos: Sones 1930",
        "https://folkways.si.edu/sextetos-cubanos/world/music/album/smithsonian",
    )
    private val folkwaysSalsa = AtlasSource(
        "Smithsonian Folkways — Folk Music of Cuba",
        "https://folkways.si.edu/folk-music-of-cuba/world/music/album/smithsonian",
    )

    private val pages: Map<String, AtlasEditorialPage> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        listOf(
            AtlasEditorialPage(
                genreId = "pop",
                displayName = "Pop",
                overview = "Pop es un campo amplio y cambiante: sus expresiones atraviesan géneros y formatos, " +
                    "por lo que no tiene una única genealogía. En los años ochenta, el videoclip ayudó " +
                    "a transformar su circulación internacional.",
                overviewSource = pop1984,
                milestones = listOf(AtlasMilestone(
                    "1984", "La expansión del videoclip",
                    "La programación musical televisiva contribuyó a difundir artistas pop y propuestas new wave.", pop1984,
                )),
                scenes = emptyList(),
                relations = emptyList(), // Deliberately NO fabricated universal Pop roots.
            ),
            AtlasEditorialPage(
                genreId = "new wave",
                displayName = "New Wave",
                overview = "New Wave identifica varias propuestas de fines de los setenta y los ochenta. " +
                    "Agrupó artistas que mezclaron canciones pop con recursos electrónicos y una nueva cultura visual.",
                overviewSource = duran,
                milestones = listOf(AtlasMilestone(
                    "Década de 1980", "New Wave y cultura del videoclip",
                    "Duran Duran destacó por un lenguaje audiovisual que acompañó su difusión internacional.", duran,
                )),
                scenes = listOf(AtlasMusicScene(
                    "Reino Unido", "Años ochenta",
                    "Duran Duran se desarrolló dentro del entorno británico asociado a New Wave.", duran,
                )),
                relations = listOf(
                    AtlasVerifiedRelation(
                        fromGenreId = "new wave", toGenreId = "synth-pop",
                        kind = AtlasRelationKind.ASSOCIATED_WITH, sources = listOf(duran),
                        targetDisplayName = "Synth-Pop",
                        explanation = "Ambas denominaciones convivieron en bandas que combinaron canción pop " +
                            "y sintetizadores; esto no afirma que sean géneros idénticos.",
                    ),
                    AtlasVerifiedRelation(
                        fromGenreId = "new wave", toGenreId = "darkwave",
                        kind = AtlasRelationKind.ASSOCIATED_WITH, sources = listOf(sageDark),
                        targetDisplayName = "Dark Wave",
                        explanation = "La bibliografía describe Dark Wave como una corriente de post-punk " +
                            "que combinó recursos de New Wave con una estética más introspectiva.",
                    ),
                ),
            ),
            AtlasEditorialPage(
                genreId = "synth-pop",
                displayName = "Synth-Pop",
                overview = "Synth-Pop sitúa los sintetizadores y la producción electrónica en el centro " +
                    "de propuestas de canción pop. New Order es una referencia relevante de su expansión en los ochenta.",
                overviewSource = newOrder,
                milestones = listOf(AtlasMilestone(
                    "Década de 1980", "New Order y el lenguaje electrónico",
                    "Después de Joy Division, New Order incorporó sintetizadores y ritmos orientados al baile.", newOrder,
                )),
                scenes = listOf(AtlasMusicScene(
                    "Manchester, Reino Unido", "Años ochenta",
                    "La trayectoria de New Order conectó post-punk, electrónica y cultura de clubes.", newOrder,
                )),
                relations = listOf(AtlasVerifiedRelation(
                    fromGenreId = "synth-pop", toGenreId = "new wave",
                    kind = AtlasRelationKind.ASSOCIATED_WITH, sources = listOf(duran),
                    targetDisplayName = "New Wave",
                    explanation = "Algunas bandas new wave incorporaron sintetizadores; las etiquetas " +
                        "pueden cruzarse sin establecer una relación de ascendencia universal.",
                )),
            ),
            AtlasEditorialPage(
                genreId = "krautrock",
                displayName = "Krautrock",
                overview = "Krautrock reúne escenas experimentales de Alemania Occidental desde fines de " +
                    "los años sesenta. Es una denominación amplia y discutida, no un único sonido homogéneo.",
                overviewSource = wuerzburg,
                milestones = listOf(AtlasMilestone(
                    "1967–1979", "Experimentación en Alemania Occidental",
                    "El término agrupa prácticas psicodélicas, electrónicas y de rock experimental.", wuerzburg,
                )),
                scenes = listOf(AtlasMusicScene(
                    "Alemania Occidental", "Finales de 1960–década de 1970",
                    "La escena incluyó proyectos experimentales como Can, Faust y Kraftwerk.", michigan,
                )),
                relations = listOf(AtlasVerifiedRelation(
                    fromGenreId = "krautrock", toGenreId = "techno",
                    kind = AtlasRelationKind.INFLUENCES, sources = listOf(michigan),
                    targetDisplayName = "Techno",
                    explanation = "La investigación histórica identifica influencia de estos experimentos " +
                        "alemanes en corrientes electrónicas posteriores, incluido el techno.",
                )),
            ),
            AtlasEditorialPage(
                genreId = "trip-hop",
                displayName = "Trip-Hop",
                overview = "Trip-Hop se asocia a una estética de producción lenta, samples y ritmos " +
                    "emparentados con el hip-hop. Su etiqueta se consolidó alrededor de la escena " +
                    "de Bristol durante los noventa, aunque parte de los músicos la cuestionó.",
                overviewSource = cambridge,
                milestones = listOf(
                    AtlasMilestone(
                        "1991", "Blue Lines, de Massive Attack",
                        "El álbum se convirtió en una referencia temprana de la escena asociada a Bristol.", abc,
                    ),
                    AtlasMilestone(
                        "1994", "Difusión del término Trip-Hop",
                        "La crítica musical británica empezó a popularizar esta denominación.", abc,
                    ),
                ),
                scenes = listOf(AtlasMusicScene(
                    "Bristol, Reino Unido", "Década de 1990",
                    "Escena vinculada a Massive Attack, Portishead y Tricky.", abc,
                )),
                relations = listOf(AtlasVerifiedRelation(
                    fromGenreId = "trip-hop", toGenreId = "hip-hop",
                    kind = AtlasRelationKind.INFLUENCED_BY, sources = listOf(abc),
                    targetDisplayName = "Hip-Hop",
                    explanation = "El trip-hop tomó recursos de producción, sampling y ritmos del hip-hop; " +
                        "no equivale a afirmar que todos sus artistas tengan el mismo origen.",
                )),
            ),
            AtlasEditorialPage(
                genreId = "ndw", displayName = "NDW",
                overview = "Neue Deutsche Welle fue una escena de música popular alemana de fines " +
                    "de los setenta y comienzos de los ochenta. Reunió propuestas de origen punk " +
                    "y expresiones comerciales posteriores; no constituyó un único estilo homogéneo.",
                overviewSource = zdfNdw,
                milestones = listOf(
                    AtlasMilestone("Finales de 1970", "Escena inicial",
                        "En Düsseldorf y otras ciudades surgieron grupos de espíritu punk, crítico " +
                            "y experimental, anteriores a la expansión comercial.", zdfNdw),
                    AtlasMilestone("Comienzos de 1980", "Difusión masiva",
                        "El movimiento alcanzó nuevos públicos; la propia etiqueta NDW agrupó " +
                            "artistas muy distintos.", zdfNdw),
                ),
                scenes = listOf(AtlasMusicScene(
                    "Düsseldorf y otras ciudades alemanas", "Finales de 1970–años ochenta",
                    "La serie documental de ZDF distingue pioneros y fases comerciales posteriores.", zdfNdw,
                )),
                relations = listOf(AtlasVerifiedRelation(
                    fromGenreId = "ndw", toGenreId = "punk",
                    kind = AtlasRelationKind.INFLUENCED_BY, sources = listOf(zdfNdw),
                    targetDisplayName = "Punk",
                    explanation = "ZDF documenta la influencia del punk en la primera etapa de NDW; " +
                        "no todos los artistas posteriores compartieron la misma trayectoria.",
                )),
            ),
            AtlasEditorialPage(
                genreId = "darkwave", displayName = "Dark Wave",
                overview = "Dark Wave se desarrolló desde el post-punk a finales de los años setenta " +
                    "y principios de los ochenta. No fue un género único en todos los países; " +
                    "algunas variantes combinaron New Wave con temáticas introspectivas.",
                overviewSource = sageDark,
                milestones = listOf(AtlasMilestone(
                    "Finales de 1970–inicios de 1980", "Primeras escenas post-punk",
                    "Los usos tempranos de la denominación se vincularon a transformaciones " +
                        "del post-punk y New Wave; la terminología evolucionó después.", sageDark,
                )),
                scenes = emptyList(),
                relations = listOf(AtlasVerifiedRelation(
                    fromGenreId = "darkwave", toGenreId = "new wave",
                    kind = AtlasRelationKind.ASSOCIATED_WITH, sources = listOf(sageDark),
                    targetDisplayName = "New Wave",
                    explanation = "La referencia académica sitúa recursos New Wave entre las influencias " +
                        "estéticas de parte de Dark Wave, sin equiparar las dos etiquetas.",
                )),
            ),
            AtlasEditorialPage(
                genreId = "art rock", displayName = "Art Rock",
                overview = "Art Rock reúne prácticas de rock que incorporaron recursos de la " +
                    "vanguardia y de la música académica. Su frontera con el rock progresivo " +
                    "es discutida y depende de la clasificación utilizada.",
                overviewSource = ebscoArt,
                milestones = listOf(AtlasMilestone(
                    "Finales de 1960–década de 1970", "Expansión de propuestas artísticas",
                    "Músicos del rock incorporaron técnicas de composición y formatos " +
                        "experimentales que trascendían la estructura convencional de canción.", ebscoArt,
                )),
                scenes = emptyList(),
                relations = listOf(AtlasVerifiedRelation(
                    fromGenreId = "art rock", toGenreId = "progressive rock",
                    kind = AtlasRelationKind.ASSOCIATED_WITH, sources = listOf(ebscoArt),
                    targetDisplayName = "Rock progresivo",
                    explanation = "Las fuentes emplean en ocasiones Art Rock y Progressive Rock como " +
                        "términos superpuestos; no se presenta una genealogía universal.",
                )),
            ),
            AtlasEditorialPage(
                genreId = "son cubano", displayName = "Son Cubano",
                overview = "El son cubano surgió en el este de Cuba durante el siglo XIX, " +
                    "combinando tradiciones musicales diversas. Su instrumentación y los " +
                    "formatos de agrupación cambiaron a medida que se extendió hacia La Habana.",
                overviewSource = folkwaysSon,
                milestones = listOf(AtlasMilestone(
                    "Finales del siglo XIX–década de 1930", "Del oriente cubano al sexteto",
                    "Desde el ámbito oriental, el son se difundió y en los años veinte " +
                        "se consolidaron formaciones como el sexteto.", folkwaysSon,
                )),
                scenes = listOf(AtlasMusicScene(
                    "Oriente, Cuba", "Finales del siglo XIX",
                    "Smithsonian Folkways sitúa allí el desarrollo temprano del son.", folkwaysSon,
                )),
                relations = listOf(AtlasVerifiedRelation(
                    fromGenreId = "son cubano", toGenreId = "salsa",
                    kind = AtlasRelationKind.INFLUENCES, sources = listOf(folkwaysSalsa),
                    targetDisplayName = "Salsa",
                    explanation = "La tradición del son aportó recursos musicales a la salsa; " +
                        "la salsa también incorpora otras tradiciones caribeñas.",
                )),
            ),
        ).associateBy(AtlasEditorialPage::genreId)
    }

    /** Exact canonical key. Never `contains`, fuzzy aliases, guesses, or title matching. */
    fun find(genreId: String): AtlasEditorialPage? = pages[genreId]?.takeIf(::atlasPageIsValid)

    /** For deterministic offline QA only; this is not library genre discovery. */
    fun curatedKeys(): Set<String> = pages.keys
}

/** Refuse any incomplete entry atomically: never publish a half-sourced historical page. */
internal fun atlasPageIsValid(page: AtlasEditorialPage): Boolean {
    if (page.genreId.isBlank() || page.displayName.isBlank() || page.overview.isBlank() ||
        !atlasSourceIsSafe(page.overviewSource)) return false
    if (page.milestones.size > 16 || page.scenes.size > 12 || page.relations.size > 12) return false
    if (page.milestones.any { it.period.isBlank() || it.title.isBlank() || it.description.isBlank() || !atlasSourceIsSafe(it.source) }) return false
    if (page.scenes.any { it.place.isBlank() || it.period.isBlank() || it.context.isBlank() || !atlasSourceIsSafe(it.source) }) return false
    return atlasAcceptedRelations(page.genreId, page.relations).size == page.relations.size
}

internal fun atlasPageSources(page: AtlasEditorialPage): List<AtlasSource> =
    (listOf(page.overviewSource) + page.milestones.map { it.source } +
        page.scenes.map { it.source } + page.relations.flatMap { it.sources })
        .distinctBy(AtlasSource::url).take(16)
