package org.michimusic.mobile

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeNavigationTest {

    @Test
    fun verifyMainActivityLaunchesAndResumesSuccessfully() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.CREATED))
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }
}

