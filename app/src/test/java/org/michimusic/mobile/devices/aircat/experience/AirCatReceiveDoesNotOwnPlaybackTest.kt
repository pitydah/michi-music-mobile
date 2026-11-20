package org.michimusic.mobile.devices.aircat.experience

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A71-S7: receiving files cannot cut Media3 playback because the receive path
 * does not own playback at all.
 *
 * This is a structural check, not a runtime one. It proves that neither the
 * `:app` AirCat experience code nor the `:aircat-transfer` engine references a
 * player, an audio-focus request, a media session or the playback service, and
 * that the transfer module does not depend on Media3. The runtime half of the
 * claim — an active ExoPlayer keeps playing while the receive surface is opened
 * and operated — lives in the instrumented
 * `AirCatPlaybackContinuityTest`.
 *
 * Without a real Michi peer the receive socket never runs (EXTERNAL_INTEROP is
 * BLOCKED_EXTERNAL), so this test must not be read as proof that a real
 * transfer leaves playback untouched. It proves the ownership boundary that
 * would be the only way this code could touch playback, and that boundary is
 * absent.
 */
class AirCatReceiveDoesNotOwnPlaybackTest {

    private val forbiddenReferences = listOf(
        "androidx.media3",
        "ExoPlayer",
        "AudioManager",
        "requestAudioFocus",
        "abandonAudioFocus",
        "MediaSession",
        "MichiPlaybackService",
        "stopService",
    )

    @Test
    fun noAirCatSourceTouchesPlaybackOrAudioFocus() {
        val roots = listOf(
            File(moduleRoot(), "app/src/main/java/org/michimusic/mobile/devices/aircat"),
            File(moduleRoot(), "aircat-transfer/src/main/java/org/michimusic/mobile/devices/aircat"),
        )
        val sources = roots.flatMap { root ->
            assertTrue("AirCat source root is missing: ${root.absolutePath}", root.isDirectory)
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue(
            "the scan found only ${sources.size} AirCat sources; the check would be vacuous",
            sources.size >= 30,
        )

        val findings = mutableListOf<String>()
        sources.forEach { file ->
            val text = file.readText()
            assertTrue("empty AirCat source: ${file.path}", text.isNotBlank())
            forbiddenReferences.forEach { needle ->
                if (text.contains(needle, ignoreCase = true)) {
                    findings += "${file.path}: $needle"
                }
            }
        }
        assertEquals(
            "AirCat code must not reference playback or audio focus, but found:\n" +
                findings.joinToString("\n"),
            emptyList<String>(),
            findings,
        )
        println("A71_S7_PLAYBACK_SCAN sources=${sources.size} forbidden=0")
    }

    @Test
    fun theTransferEngineDoesNotDependOnMedia3() {
        val buildFile = listOf("build.gradle.kts", "build.gradle")
            .map { File(moduleRoot(), "aircat-transfer/$it") }
            .firstOrNull { it.isFile }
        val resolved = requireNotNull(buildFile) { "aircat-transfer build file not found" }
        val text = resolved.readText()
        assertTrue(
            "aircat-transfer must not depend on Media3:\n$text",
            !text.contains("media3", ignoreCase = true),
        )
        println("A71_S7_PLAYBACK_SCAN transferBuild=${resolved.name} media3=absent")
    }

    /** The module directory is the unit-test working directory; walk up to the repo root. */
    private fun moduleRoot(): File {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile ||
                File(current, "settings.gradle").isFile
            ) {
                return current
            }
            current = current.parentFile
        }
        error("repository root not found from ${System.getProperty("user.dir")}")
    }
}
