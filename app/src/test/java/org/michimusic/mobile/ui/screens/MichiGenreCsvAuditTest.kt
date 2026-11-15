package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MichiGenreCsvAuditTest {
    private val moduleDir: File = sequenceOf(File("."), File("app"))
        .map { it.absoluteFile }
        .first { File(it, "src/test/resources/ui7/mi26_genres_audited.csv").isFile }

    @Test
    fun csvAuditsAllCatalogIdentitiesAndArtwork() {
        val csv = File(moduleDir, "src/test/resources/ui7/mi26_genres_audited.csv")
            .readText()
            .removePrefix("\uFEFF")
        val rows = parseCsv(csv)
        assertEquals(23, rows.first().size)
        val header = rows.first()
        fun col(name: String): Int = header.indexOf(name).also {
            assertTrue("missing column $name", it >= 0)
        }

        val data = rows.drop(1).filter { row -> row.any { it.isNotBlank() } }
        assertEquals(314, data.size)
        val catalog = data.filter {
            it.getOrElse(col("tipo_de_recurso")) { "" } == "CATALOGO_312_APROBADO"
        }
        val observed = data.filter {
            it.getOrElse(col("tipo_de_recurso")) { "" } == "ETIQUETA_OBSERVADA_FUERA_DEL_CATALOGO"
        }
        assertEquals(312, catalog.size)
        assertEquals(2, observed.size)
        assertEquals(312, catalog.map { it[col("clave_grupo_objetivo")] }.toSet().size)
        assertEquals(312, catalog.map { it[col("identidad_manifest_mosaico")] }.toSet().size)
        assertEquals(312, catalog.map { it[col("identidad_manifest_editorial")] }.toSet().size)

        catalog.forEach { row ->
            val number = row[col("numero_catalogo")]
            val identity = row[col("identidad_manifest_mosaico")]
            val target = row[col("clave_grupo_objetivo")]
            val editorial = row[col("identidad_manifest_editorial")]
            val mosaicFile = row[col("imagen_mosaico")]
            val editorialFile = row[col("imagen_editorial")]
            assertEquals(
                "row $number current/target",
                row[col("clave_grupo_actual_MI26")],
                target,
            )
            assertEquals("row $number canonical", target, genreGroupingKey(identity))
            assertEquals(
                "row $number editorial slug",
                editorial,
                editorialArtworkSlug(identity).orEmpty(),
            )
            assertEquals(
                "row $number mosaic filename",
                target,
                mosaicGenreKeyFromFilename(mosaicFile),
            )
            assertEquals(
                "row $number editorial filename",
                "genre_${editorial}_editorial.webp",
                editorialFile,
            )
        }
        assertEquals(
            setOf("electronic", "jangle pop"),
            observed.map { it[col("clave_grupo_objetivo")] }.toSet(),
        )

        val mosaicDir = File(moduleDir, "src/main/assets/ui7/mosaic")
        val installed = mosaicDir.listFiles().orEmpty()
            .filter { it.extension == "webp" }
            .map { it.name }
            .toSet()
        val rejected = mutableListOf<String>()
        val artwork = mosaicApprovedArtwork(
            manifest = File(mosaicDir, "manifest.tsv").readText(),
            installed = installed,
            onRejected = { rejected += it },
        )
        assertEquals("rejections=$rejected", 312, artwork.size)
        catalog.forEach { row ->
            val number = row[col("numero_catalogo")]
            val target = row[col("clave_grupo_objetivo")]
            val expected = row[col("imagen_mosaico")]
            assertEquals(
                "row $number artwork",
                expected,
                mosaicArtworkForGenre(target, artwork)?.filename,
            )
        }
        assertNull(mosaicArtworkForGenre("Hip/Hop", artwork))
        assertNull(mosaicArtworkForGenre("R B", artwork))
        assertNull(mosaicArtworkForGenre("Rock/Pop", artwork))
        assertNull(mosaicArtworkForGenre("Electronic", artwork))
        assertNull(mosaicArtworkForGenre("Jangle Pop", artwork))
        assertEquals(
            "UI7_MOSAICO_219_Darkwave.webp",
            mosaicArtworkForGenre("Dark Wave", artwork)?.filename,
        )

        val posterLines = File(moduleDir, "src/main/assets/ui7/editorial/posters/manifest.tsv")
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(312, posterLines.size)
        val posterSlugs = posterLines.map { it.split('\t').first() }.toSet()
        assertEquals(312, posterSlugs.size)
        catalog.forEach { row ->
            val slug = row[col("identidad_manifest_editorial")]
            assertTrue(
                "row ${row[col("numero_catalogo")]} poster $slug",
                slug in posterSlugs,
            )
        }
        assertFalse("electronic" in posterSlugs)
        assertFalse("jangle pop" in posterSlugs)
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && inQuotes && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    row.add(field.toString())
                    field.clear()
                }
                (char == '\n' || char == '\r') && !inQuotes -> {
                    row.add(field.toString())
                    field.clear()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                        index++
                    }
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }
        return rows
    }
}
