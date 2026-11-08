package org.michimusic.build

private const val CI_VERSION_CODE_OFFSET = 1_000_000L
private const val MAX_ANDROID_VERSION_CODE = 2_100_000_000L

/** Resolves an Android version code without relying on checkout depth in CI. */
fun resolveMichiVersionCode(
    explicitVersionCode: String?,
    githubRunNumber: String?,
    gitCommitCount: String?,
    lastPublishedVersionCode: String? = null,
): Int {
    val publishedFloor = lastPublishedVersionCode?.let {
        parseAuthoritativeVersionCode("lastPublishedVersionCode", it)
    }
    explicitVersionCode?.let {
        val explicit = parseAuthoritativeVersionCode("MICHI_VERSION_CODE", it)
        require(publishedFloor == null || explicit > publishedFloor) {
            "MICHI_VERSION_CODE $explicit must exceed last published versionCode $publishedFloor"
        }
        return explicit
    }

    githubRunNumber?.let {
        val runNumber = parsePositiveLong("GITHUB_RUN_NUMBER", it)
        require(runNumber <= MAX_ANDROID_VERSION_CODE - CI_VERSION_CODE_OFFSET) {
            "GITHUB_RUN_NUMBER $runNumber is too large for Android versionCode after adding the CI offset"
        }
        val ciVersionCode = CI_VERSION_CODE_OFFSET + runNumber
        return ciVersionCode.toInt()
    }

    return gitCommitCount
        ?.toLongOrNull()
        ?.takeIf { it in 1..MAX_ANDROID_VERSION_CODE }
        ?.toInt()
        ?: 1
}

private fun parseAuthoritativeVersionCode(name: String, raw: String): Int {
    val value = parsePositiveLong(name, raw)
    require(value <= MAX_ANDROID_VERSION_CODE) {
        "$name must not exceed Android's maximum versionCode $MAX_ANDROID_VERSION_CODE"
    }
    return value.toInt()
}

private fun parsePositiveLong(name: String, raw: String): Long {
    val value = raw.toLongOrNull()
    require(value != null && value > 0) {
        "$name must be a positive integer, but was '$raw'"
    }
    return value
}
