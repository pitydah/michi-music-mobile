package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsWizardReminderPolicyTest {
    @Test fun noDeferredWizardActionsMeansNoRepeatedPrompt() {
        assertEquals(SettingsWizardReminders(false, false),
            settingsWizardReminders(false, false, false))
    }
    @Test fun usbReminderIsIndependentFromDiscoveryOrLink() {
        assertEquals(SettingsWizardReminders(true, false),
            settingsWizardReminders(true, false, false))
    }
    @Test fun bothDeferredNetworkChoicesNavigateToExistingDevices() {
        assertEquals(SettingsWizardReminders(false, true),
            settingsWizardReminders(false, true, true))
    }
}
