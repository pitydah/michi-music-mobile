package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy
import java.io.File

/**
 * MI-26 review gate for the audit CSV proposals.
 *
 * The CSV is an inventory, not the runtime authority. Every alias must be either
 * already approved (and still resolve to its reviewed canonical target) or
 * explicitly deferred; forbidden neighbours must stay separate. The runtime
 * registry stays the single policy.
 */
class MichiGenreAliasPolicyTest {
    private val approvedAliases: Map<String, String> = mapOf(
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
        "música popular brasileira" to "mpb",
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
        "cha-cha-chá" to "cha cha cha",
    )
    private val deferredAliases = setOf("high energy")

    @Test
    fun everyReviewedProposalIsEitherActiveOrExplicitlyDeferred() {
        val rows = parseCsv(csvFile().readText())
        val header = rows.first()
        val aliasColumn = header.requireColumn("alias_nuevos_propuestos_NO_ACTIVOS")
        val approvedColumn = header.requireColumn("alias_ya_aprobados_MI26")
        val targetColumn = header.requireColumn("clave_grupo_objetivo")
        val noMergeColumn = header.requireColumn("no_fusionar_con")

        // The curated registry revision moved the reviewed aliases from the proposal
        // column into `alias_ya_aprobados_MI26`. Both columns stay verified: an alias
        // already in code must still resolve to its reviewed canonical target, and the
        // proposal column may only keep the deliberately deferred one.
        var reviewedApproved = 0
        val stillProposed = mutableSetOf<String>()
        for (row in rows.drop(1).filter { it.any(String::isNotBlank) }) {
            val target = row[targetColumn]
            for (alias in row[approvedColumn].split('|').map(String::trim).filter(String::isNotEmpty)) {
                val expected = approvedAliases[alias] ?: continue
                reviewedApproved++
                assertEquals(
                    "approved alias '$alias' must resolve to '$expected'",
                    expected,
                    MichiGenreDisambiguationPolicy.key(alias),
                )
            }
            for (alias in row[aliasColumn].split('|').map(String::trim).filter(String::isNotEmpty)) {
                stillProposed += alias
                val expected = approvedAliases[alias]
                when {
                    expected != null -> assertEquals(
                        "alias '$alias' must resolve to '$expected'",
                        expected,
                        MichiGenreDisambiguationPolicy.key(alias),
                    )
                    alias in deferredAliases -> assertNotEquals(
                        "deferred alias '$alias' must not resolve to '$target'",
                        target,
                        MichiGenreDisambiguationPolicy.key(alias),
                    )
                    else -> error("Unreviewed proposed alias '$alias' for target '$target'")
                }
            }
            for (neighbour in row[noMergeColumn]
                .split('|')
                .map { it.substringBefore('[').trim() }
                .filter { it.isNotEmpty() && it != "—" && it != "-" }) {
                assertNotEquals(
                    "'$target' must not merge with '$neighbour'",
                    MichiGenreDisambiguationPolicy.key(target),
                    MichiGenreDisambiguationPolicy.key(neighbour),
                )
            }
        }
        assertEquals(
            "all reviewed aliases must now be listed in the CSV approved column",
            approvedAliases.size,
            reviewedApproved,
        )
        // Only the deliberately deferred alias may remain a proposal.
        assertEquals(deferredAliases, stillProposed)
    }

    @Test
    fun legacyReadMapCoversEveryApprovedAliasShape() {
        val legacy = MichiGenreDisambiguationPolicy.legacyAliases()
        assertEquals(40, legacy.size)
        assertTrue(legacy.entries.none { (oldKey, newKey) -> oldKey == newKey })
        assertEquals("darkwave", legacy["dark wave"])
        assertEquals("darkwave", legacy["dark-wave"])
        assertEquals("mpb", legacy["musica popular brasileira"])
        assertEquals("cha cha cha", legacy["cha-cha-cha"])
        assertEquals(
            listOf("dark wave", "dark-wave"),
            MichiGenreDisambiguationPolicy.legacyKeysFor("darkwave").sorted(),
        )
    }

    private fun csvFile(): File {
        val candidates = listOf(File("."), File("app"))
        return candidates.map { File(it, "src/test/resources/ui7/mi26_genres_audited.csv") }
            .firstOrNull(File::isFile)
            ?: error("MI-26 audit CSV not found")
    }

    private fun List<String>.requireColumn(name: String): Int =
        indexOf(name).also { check(it >= 0) { "Missing CSV column: $name" } }

    private fun parseCsv(text: String): List<List<String>> {
        val source = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < source.length) {
            val char = source[index]
            when {
                quoted && char == '"' && index + 1 < source.length && source[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row += field.toString()
                    field.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (field.isNotEmpty() || row.isNotEmpty()) {
                        row += field.toString()
                        rows += row.toList()
                        row.clear()
                        field.clear()
                    }
                    if (char == '\r' && index + 1 < source.length && source[index + 1] == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row.toList()
        }
        return rows
    }
}
