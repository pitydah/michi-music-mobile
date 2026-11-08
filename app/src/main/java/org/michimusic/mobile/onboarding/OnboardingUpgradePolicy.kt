package org.michimusic.mobile.onboarding

/**
 * PackageManager firstInstallTime and lastUpdateTime identify a pre-wizard app upgrade.
 * A two-minute margin tolerates small system timestamp differences on initial install.
 * Draft-marker checks are performed by OnboardingStore before calling this function.
 */
internal fun shouldSkipWizardForLegacyUpgrade(
    firstInstallTime: Long,
    lastUpdateTime: Long,
): Boolean = firstInstallTime > 0L && lastUpdateTime >= firstInstallTime &&
    lastUpdateTime - firstInstallTime >= 120_000L
