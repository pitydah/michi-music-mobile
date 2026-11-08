package org.michimusic.mobile.onboarding

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.repository.SafMediaRepository
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI integration for the onboarding state machine, without Koin or physical USB hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class FirstRunWizardFlowTest {
    @get:Rule val rule = createComposeRule()
    private val saf: SafMediaRepository = mockk(relaxed = true)

    @Test fun skipEcosystemPersistsNoDiscoveryAndNeverBlocksNext() {
        val drafts = mutableListOf<OnboardingSnapshot>()
        rule.setContent {
            FirstRunWizard(
                initial = OnboardingSnapshot(step = 2, discoverLater = true, linkLater = true),
                replay = false,
                saveDraft = { drafts += it; true },
                finish = { true },
                onCompleted = {}, onDismissReplay = {}, safRepository = saf,
            )
        }
        rule.onNodeWithTag("wizard_skip_ecosystem").performClick()
        rule.waitForIdle()
        assertEquals(3, drafts.last().step)
        assertFalse(drafts.last().discoverLater)
        assertFalse(drafts.last().linkLater)
        rule.onNodeWithTag("wizard_step_counter").assertTextEquals("4 / 5")
    }

    @Test fun finishUsesSingleHomeActionOnlyAfterDurableWrite() {
        var committed = false
        var routed: OnboardingDestination? = null
        rule.setContent {
            FirstRunWizard(
                initial = OnboardingSnapshot(step = 4, audio = AudioChoice.USB_REMINDER,
                    discoverLater = true, linkLater = true),
                replay = false,
                saveDraft = { true },
                finish = { committed = true; true },
                onCompleted = { routed = it }, onDismissReplay = {}, safRepository = saf,
            )
        }
        rule.onNodeWithTag("wizard_next").performClick()
        rule.waitForIdle()
        assertTrue(committed)
        assertEquals(OnboardingDestination.HOME, routed)
        rule.onNodeWithTag("wizard_open_audio_route").assertDoesNotExist()
        rule.onNodeWithTag("wizard_open_devices").assertDoesNotExist()
        rule.onNodeWithTag("wizard_open_link_settings").assertDoesNotExist()
    }

    @Test fun userCanProceedWithoutPermissionsOrDevices() {
        var routed: OnboardingDestination? = null
        rule.setContent {
            FirstRunWizard(
                initial = OnboardingSnapshot(step = 4, library = LibraryChoice.LATER), replay = false,
                saveDraft = { true }, finish = { true },
                onCompleted = { routed = it }, onDismissReplay = {}, safRepository = saf,
            )
        }
        rule.onNodeWithTag("wizard_next").performClick()
        rule.waitForIdle()
        assertEquals(OnboardingDestination.HOME, routed)
    }

    @Test fun failedCompletionDoesNotNavigate() {
        var routed: OnboardingDestination? = null
        rule.setContent {
            FirstRunWizard(
                initial = OnboardingSnapshot(step = 4), replay = false,
                saveDraft = { true }, finish = { false },
                onCompleted = { routed = it }, onDismissReplay = {}, safRepository = saf,
            )
        }
        rule.onNodeWithTag("wizard_next").performClick()
        rule.waitForIdle()
        assertEquals(null, routed)
        rule.onNodeWithTag("wizard_error").assertExists()
    }

    @Test fun replayCloseKeepsPersistedOptionsUnchanged() {
        var completed = false
        var dismissed = false
        rule.setContent {
            FirstRunWizard(
                initial = OnboardingSnapshot(completed = true, library = LibraryChoice.LATER),
                replay = true, saveDraft = { true },
                finish = { completed = true; true },
                onCompleted = {}, onDismissReplay = { dismissed = true }, safRepository = saf,
            )
        }
        rule.onNodeWithTag("wizard_back").performClick()
        rule.waitForIdle()
        assertTrue(dismissed)
        assertFalse(completed)
    }
}
