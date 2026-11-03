package org.michimusic.mobile.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 regression guard: every Flow that a composable renders is collected with
 * `collectAsStateWithLifecycle`, so the collection stops when the screen leaves
 * the STARTED state instead of keeping a stopped screen subscribed.
 *
 * There are no justified exceptions today. A Flow that must keep running while
 * the UI is not visible belongs to a ViewModel, a service or a WorkManager job,
 * not to a composable. If a real exception appears, add it to [EXCEPTIONS] with
 * the lifecycle reason written next to it instead of deleting this test.
 */
class CollectAsStateLifecycleTest {

    private val moduleDir: File = File(System.getProperty("user.dir") ?: ".").let { cwd ->
        if (File(cwd, "src/main/java").isDirectory) cwd
        else File(cwd, "app").takeIf { File(it, "src/main/java").isDirectory } ?: cwd
    }

    @Test
    fun everyUiFlowIsCollectedWithLifecycle() {
        val roots = listOf(
            File(moduleDir, "src/main/java/org/michimusic/mobile/ui"),
            File(moduleDir, "src/main/java/org/michimusic/mobile/navigation"),
        )
        assertTrue("expected app module sources at ${moduleDir.absolutePath}", roots.all { it.isDirectory })

        val offenders = roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }
            .filter { file -> file.readText().contains("collectAsState(") }
            .map { it.relativeTo(moduleDir).path }
            .filterNot { path -> EXCEPTIONS.containsKey(path) }
            .sorted()

        assertEquals(
            "collectAsState() keeps a stopped screen subscribed; use collectAsStateWithLifecycle() " +
                "or document the exception in CollectAsStateLifecycleTest.EXCEPTIONS",
            emptyList<String>(),
            offenders,
        )
    }

    private companion object {
        /** Path relative to the app module mapped to the reason it may skip the lifecycle. */
        val EXCEPTIONS: Map<String, String> = emptyMap()
    }
}
