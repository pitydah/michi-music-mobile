package org.michimusic.mobile.devices.aircat

/** Strict managed AirCat v1 files are never promoted without digest and exact size. */
internal object AirCatStrictFilePolicy {
    private val sha256 = Regex("[a-fA-F0-9]{64}")

    fun refusal(remoteTrackId: String, expectedSize: Long, expectedSha256: String): String? = when {
        remoteTrackId.isBlank() -> "remoteTrackId no puede estar vacío"
        expectedSize <= 0L -> "AirCat gestionado exige tamaño positivo de archivo"
        !sha256.matches(expectedSha256) -> "AirCat gestionado exige SHA-256 hexadecimal válido por archivo"
        else -> null
    }
}
