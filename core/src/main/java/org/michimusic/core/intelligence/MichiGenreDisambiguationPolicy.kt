package org.michimusic.core.intelligence

import java.text.Normalizer
import java.util.Locale

/**
 * MI-26: deterministic, curated identity for genre LABELS. This is neither a genre
 * classifier nor a tag editor. It reads no files, never contacts MusicBrainz and
 * never derives new styles from artist/album/filename or listening history.
 *
 * In particular, token permutations and edit-distance matching are forbidden:
 * only the explicitly approved Rock Pop <-> Pop Rock exception can reverse words.
 */
object MichiGenreDisambiguationPolicy {
    const val REGISTRY_VERSION = 1
    private const val MAX_LABEL_LENGTH = 96
    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")
    private val safeSeparators = Regex("[a-z0-9 _-]+")

    data class Resolution(
        val original: String,
        val key: String,
        val display: String,
        val approvedAlias: Boolean,
    )
    private data class Approved(val key: String, val display: String)

    /** Includes legacy UI7 aliases; do not change their canonical keys or poster lookup. */
    private val approved: Map<String, Approved> = buildMap {
        fun putAlias(form: String, entry: Approved) {
            val old = putIfAbsent(form, entry)
            check(old == null || old == entry) { "MI-26 conflicting curated alias" }
        }
        fun register(key: String, display: String, vararg aliases: String) {
            val entry = Approved(key, display)
            for (alias in listOf(key, display) + aliases) {
                val form = folded(alias)
                putAlias(form, entry)
                // A compact spelling is equivalent ONLY within this explicit registry.
                // Never compact slash, apostrophe, '&' or other punctuation generically.
                if (safeSeparators.matches(form)) {
                    putAlias(form.replace(" ", "").replace("-", "").replace("_", ""), entry)
                    putAlias(form.replace('-', ' ').replace('_', ' '), entry)
                }
            }
        }
        // User-approved exact synonym exception: reordered compounds are not
        // inferred for any other style (e.g. hard rock != rock hard).
        register("pop rock", "Pop Rock", "rock pop", "poprock", "rockpop")
        register("synth-pop", "Synth-Pop", "synth pop", "synthpop")
        register("new wave", "New Wave", "new-wave", "newwave")
        register("darkwave", "Dark Wave", "dark wave", "dark-wave")
        register("lo-fi", "Lo-Fi", "lo fi", "lofi")
        register("hip-hop", "Hip-Hop", "hip hop", "hiphop")
        register("k-pop", "K-Pop", "k pop", "kpop")
        register("j-pop", "J-Pop", "j pop", "jpop")
        register("r&b", "R&B", "rnb", "r and b", "r & b")
        register("drum bass", "Drum & Bass", "drum and bass", "drum & bass", "drum n bass")
        register("rock roll", "Rock & Roll", "rock 'n' roll", "rock n roll",
            "rock and roll", "rock & roll")
        register("italo disco", "Italo Disco", "italo-disco")
        // Conservatively curated additional compound spellings; no automatic
        // singular/plural, arbitrary word reversal, genre tree or fuzzy matching.
        register("post-rock", "Post-Rock", "post rock", "postrock")
        register("dream pop", "Dream Pop", "dreampop", "dream-pop")
        register("art rock", "Art Rock", "artrock", "art-rock")
        register("math rock", "Math Rock", "mathrock", "math-rock")
        register("electropop", "Electropop", "electro pop", "electro-pop")
        register("indie rock", "Indie Rock", "indierock", "indie-rock")
        register("hard rock", "Hard Rock", "hardrock", "hard-rock")
        register("progressive rock", "Progressive Rock", "progressiverock", "progressive-rock")
        register("alternative rock", "Alternative Rock", "alternativerock", "alternative-rock")
        register("heavy metal", "Heavy Metal", "heavymetal", "heavy-metal")
        register("black metal", "Black Metal", "blackmetal", "black-metal")
        register("death metal", "Death Metal", "deathmetal", "death-metal")
        register("power metal", "Power Metal", "powermetal", "power-metal")
        register("nu metal", "Nu Metal", "numetal", "nu-metal")
        register("trip-hop", "Trip-Hop", "trip hop", "triphop")
        register("jazz fusion", "Jazz Fusion", "jazzfusion", "jazz-fusion")
        register("acid jazz", "Acid Jazz", "acidjazz", "acid-jazz")
        register("smooth jazz", "Smooth Jazz", "smoothjazz", "smooth-jazz")
        register("bossa nova", "Bossa Nova", "bossanova", "bossa-nova")
        register("neo soul", "Neo Soul", "neosoul", "neo-soul")
        register("new age", "New Age", "newage", "new-age")
        // These labels already correspond to existing UI7 genre/poster identities;
        // their canonical keys intentionally use the currently approved spacing.
        register("pop punk", "Pop Punk", "poppunk", "pop-punk")
        register("post punk", "Post Punk", "postpunk", "post-punk")
        register("soft rock", "Soft Rock", "softrock", "soft-rock")
        register("power pop", "Power Pop", "powerpop", "power-pop")
        register("dance pop", "Dance Pop", "dancepop", "dance-pop")
        register("indie pop", "Indie Pop", "indiepop", "indie-pop")
        register("city pop", "City Pop", "citypop", "city-pop")
        register("hyperpop", "Hyperpop", "hyper-pop", "hyper pop")
        register("afro house", "Afro House", "afrohouse", "afro-house")
        register("deep house", "Deep House", "deephouse", "deep-house")
        register("acid house", "Acid House", "acidhouse", "acid-house")
        register("progressive house", "Progressive House", "progressivehouse", "progressive-house")
        register("minimal techno", "Minimal Techno", "minimaltechno", "minimal-techno")
        register("detroit techno", "Detroit Techno", "detroittechno", "detroit-techno")
        register("uk garage", "UK Garage", "ukgarage", "uk-garage")
        register("electro swing", "Electro Swing", "electroswing", "electro-swing")
        register("latin jazz", "Latin Jazz", "latinjazz", "latin-jazz")
        register("son cubano", "Son Cubano", "soncubano", "son-cubano")
        register("doom metal", "Doom Metal", "doommetal", "doom-metal")
        register("thrash metal", "Thrash Metal", "thrashmetal", "thrash-metal")
        register("symphonic metal", "Symphonic Metal", "symphonicmetal", "symphonic-metal")
        register("post-metal", "Post-Metal", "post metal", "postmetal")
        register("synthwave", "Synthwave", "synth wave", "synth-wave")
        register("big band", "Big Band", "big-band")
        register("vaporwave", "Vaporwave", "vapor wave", "vapor-wave")
        register("rocksteady", "Rocksteady", "rock steady", "rock-steady")
        register("new jack swing", "New Jack Swing", "new-jack-swing")
        register("hi nrg", "Hi-NRG", "hi-nrg")
        register("g funk", "G-Funk", "g-funk", "gfunk")
        register("chillwave", "Chillwave", "chill wave", "chill-wave")
        register("mpb", "MPB", "música popular brasileira", "musica popular brasileira")
        register("idm", "IDM", "intelligent dance music")
        register("j rock", "J-Rock", "j-rock", "jrock")
        register("ndw", "NDW", "neue deutsche welle")
        register("coldwave", "Coldwave", "cold wave", "cold-wave")
        register("minimal wave", "Minimal Wave", "minimal-wave")
        register("no wave", "No Wave", "no-wave")
        register("ebm", "EBM", "electronic body music")
        register("post punk revival", "Post Punk Revival", "post-punk revival")
        register("sovietwave", "Sovietwave", "soviet wave", "soviet-wave")
        register("russian post punk", "Russian Post Punk", "russian post-punk")
        register("dark ambient", "Dark Ambient", "dark-ambient")
        register("art pop", "Art Pop", "art-pop", "artpop")
        register("modal jazz", "Modal Jazz", "modal-jazz")
        register("soul jazz", "Soul Jazz", "soul-jazz")
        register("ethio jazz", "Ethio Jazz", "ethio-jazz")
        register("jazz funk", "Jazz Funk", "jazz-funk")
        register("nu jazz", "Nu Jazz", "nu-jazz", "nujazz")
        register("afro cuban jazz", "Afro Cuban Jazz", "afro-cuban jazz")
        register("cha cha cha", "Cha Cha Chá", "cha-cha-cha", "cha-cha-chá")
    }
    private val canonicalDisplay: Map<String, String> = approved.values.associate { it.key to it.display }

    /**
     * MI-26 aliases that changed the canonical hash. These are read-time
     * compatibility keys only: writes always use the canonical key, so legacy
     * counters are summed exactly once and no evidence is duplicated.
     */
    private val legacyAliasKeys: Map<String, String> = mapOf(
        "synth wave" to "synthwave",
        "synth-wave" to "synthwave",
        "big-band" to "big band",
        "vapor wave" to "vaporwave",
        "vapor-wave" to "vaporwave",
        "rock steady" to "rocksteady",
        "rock-steady" to "rocksteady",
        "new-jack-swing" to "new jack swing",
        "hi-nrg" to "hi nrg",
        "g-funk" to "g funk",
        "gfunk" to "g funk",
        "chill wave" to "chillwave",
        "chill-wave" to "chillwave",
        "musica popular brasileira" to "mpb",
        "intelligent dance music" to "idm",
        "j-rock" to "j rock",
        "jrock" to "j rock",
        "neue deutsche welle" to "ndw",
        "dark wave" to "darkwave",
        "dark-wave" to "darkwave",
        "cold wave" to "coldwave",
        "cold-wave" to "coldwave",
        "minimal-wave" to "minimal wave",
        "no-wave" to "no wave",
        "electronic body music" to "ebm",
        "post-punk revival" to "post punk revival",
        "soviet wave" to "sovietwave",
        "soviet-wave" to "sovietwave",
        "russian post-punk" to "russian post punk",
        "dark-ambient" to "dark ambient",
        "art-pop" to "art pop",
        "artpop" to "art pop",
        "modal-jazz" to "modal jazz",
        "soul-jazz" to "soul jazz",
        "ethio-jazz" to "ethio jazz",
        "jazz-funk" to "jazz funk",
        "nu-jazz" to "nu jazz",
        "nujazz" to "nu jazz",
        "afro-cuban jazz" to "afro cuban jazz",
        "cha-cha-cha" to "cha cha cha",
    )

    /** Literal keys that previously hashed an alias now folded into [key]. */
    fun legacyAliases(): Map<String, String> = legacyAliasKeys

    /**
     * Read-only snapshot of the curated canonical identities. Used by offline audit
     * tests (312-catalog coherence and collision checks) and never by runtime
     * classification, so it cannot become a second policy.
     */
    fun canonicalIdentities(): Set<String> = canonicalDisplay.keys

    fun legacyKeysFor(key: String): List<String> =
        legacyAliasKeys.filterValues { it == key }.keys.toList()

    private fun folded(input: String): String = whitespace.replace(
        combiningMarks.replace(Normalizer.normalize(input.trim(), Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT), " ")

    /** Preserves unknown labels as distinct; refuses missing/invalid input. */
    fun resolve(raw: String): Resolution? {
        if (raw.isBlank() || raw.length > MAX_LABEL_LENGTH ||
            raw.any { it.isISOControl() || it == '\uFFFD' }) return null
        val original = whitespace.replace(raw.trim(), " ")
        val folded = folded(original)
        // MI-26: one shared absence policy. `Other`/`Various` are containers, not
        // absences, so they stay visible as literal labels and are never rewritten.
        if (MichiGenreAbsencePolicy.isMissing(folded)) return null
        val matched = approved[folded] ?: if (safeSeparators.matches(folded)) {
            // Only a listed compact alias may match; never guess a new genre.
            approved[folded.replace(" ", "").replace("-", "").replace("_", "")]
        } else null
        return if (matched != null)
            Resolution(original, matched.key, matched.display, matched.key != folded)
        else Resolution(original, folded, original, false)
    }

    /** One canonical key for UI7 grouping, learned affinity and genre-only editing preview. */
    fun key(raw: String): String = resolve(raw)?.key.orEmpty()

    /** Display only a curated alias; arbitrary labels keep their original text. */
    fun display(key: String, fallback: String): String = canonicalDisplay[key] ?: fallback

    /** Suggested replacement for explicit user confirmation, NEVER an automatic tag write. */
    fun suggestedTag(raw: String): String = resolve(raw)?.let {
        if (it.key in canonicalDisplay) it.display else it.original
    } ?: raw
}
