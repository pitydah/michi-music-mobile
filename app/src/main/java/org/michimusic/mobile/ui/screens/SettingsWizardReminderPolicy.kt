package org.michimusic.mobile.ui.screens

/** Wizard state is a read-only reminder, not a second configuration owner.
 * Opening Settings must not replay onboarding or reset a route/peer choice.
 */
internal data class SettingsWizardReminders(val showUsb: Boolean, val showDevices: Boolean)
internal fun settingsWizardReminders(
    usbReminder: Boolean, discoverLater: Boolean, linkLater: Boolean,
): SettingsWizardReminders = SettingsWizardReminders(
    showUsb = usbReminder,
    showDevices = discoverLater || linkLater,
)
