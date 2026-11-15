package org.michimusic.data.intelligence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.intelligence.MichiGenreDisambiguationPolicy
import org.michimusic.core.intelligence.MichiGenreIntelligencePolicy
import org.michimusic.core.intelligence.MichiIntelligenceConsentBarrier
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.SettingsEntity
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MI-26 compatibility: learned genre counters written before an alias changed the
 * canonical key must remain readable. The old and new hashes are summed at read
 * time; writes keep using only the canonical key, so evidence is never duplicated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MichiIntelligenceLegacyGenreAffinityTest {
    private lateinit var context: Context
    private lateinit var mainDb: MichiDatabase
    private lateinit var repo: MichiIntelligenceRepository
    private lateinit var privateFile: File
    private lateinit var revokeFile: File
    private val dbName = "legacy-genre-affinity.db"

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        privateFile = File(context.noBackupFilesDir, MichiIntelligenceRepository.FILE_NAME)
        revokeFile = File(context.noBackupFilesDir, MichiIntelligenceRepository.REVOKED_FILE_NAME)
        deleteStore()
        context.deleteDatabase(dbName)
        mainDb = Room.databaseBuilder(context, MichiDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        mainDb.appDao().setSetting(
            SettingsEntity(key = "listening_history_enabled", value = "true"),
        )
        MichiIntelligenceConsentBarrier.acceptedExplicitConsent()
        repo = MichiIntelligenceRepository(context, mainDb.appDao())
    }

    @After
    fun tearDown() {
        if (::mainDb.isInitialized) mainDb.close()
        context.deleteDatabase(dbName)
        deleteStore()
    }

    @Test
    fun legacyAndCanonicalCountersSumInSelectionSignals() = runBlocking {
        seed("dark wave" to 3, "darkwave" to 2)

        val signals = repo.readSelectionSignals(20_000)

        assertEquals(5, signals.genres[sha256("darkwave")] ?: 0)
        assertNull(signals.genres[sha256("dark wave")])
    }

    @Test
    fun legacyOnlyCounterRemainsReadable() = runBlocking {
        seed("dark wave" to 3)

        assertEquals(3, repo.readSelectionSignals(20_000).genres[sha256("darkwave")] ?: 0)
    }

    @Test
    fun canonicalOnlyCounterStillReads() = runBlocking {
        seed("darkwave" to 2)

        assertEquals(2, repo.readSelectionSignals(20_000).genres[sha256("darkwave")] ?: 0)
    }

    @Test
    fun readAffinitiesMergesLegacyAndCanonical() = runBlocking {
        seed("dark wave" to 3, "darkwave" to 2)

        val snapshot = repo.readAffinities(
            artistKeys = emptyList(),
            genreKeys = listOf("Dark Wave"),
            nowEpochDay = 20_000,
        )

        assertEquals(5, snapshot.genre["darkwave"] ?: 0)
    }

    @Test
    fun writesUseTheCanonicalKeyAndLegacyMapIsBounded() {
        // This is the key the capture path hashes; the old literal is only a read alias.
        assertEquals(listOf("darkwave"), MichiGenreIntelligencePolicy.keys("Dark Wave"))

        val legacy = MichiGenreDisambiguationPolicy.legacyAliases()
        assertEquals(40, legacy.size)
        assertTrue(legacy.entries.none { it.key == it.value })
        assertEquals("darkwave", legacy["dark wave"])
        assertEquals(
            listOf("dark wave", "dark-wave"),
            MichiGenreDisambiguationPolicy.legacyKeysFor("darkwave").sorted(),
        )
    }

    private fun seed(vararg counters: Pair<String, Int>) {
        privateFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(privateFile, null)
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS intelligence_entries " +
                    "(k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO intelligence_entries (k, v) VALUES (?, ?)",
                arrayOf("enabled", "true"),
            )
            for ((key, value) in counters) {
                val encoded = 20_000L.toString().padStart(12, '0') + "|" + value
                db.execSQL(
                    "INSERT OR REPLACE INTO intelligence_entries (k, v) VALUES (?, ?)",
                    arrayOf("g." + sha256(key), encoded),
                )
            }
        } finally {
            db.close()
        }
    }

    private fun deleteStore() {
        listOf(
            privateFile,
            File(privateFile.path + "-wal"),
            File(privateFile.path + "-shm"),
            File(privateFile.path + "-journal"),
            revokeFile,
            File(revokeFile.path + ".new"),
            File(revokeFile.path + ".bak"),
        ).forEach { it.delete() }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
