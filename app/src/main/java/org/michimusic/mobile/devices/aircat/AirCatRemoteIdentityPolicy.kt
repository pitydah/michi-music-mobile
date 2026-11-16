package org.michimusic.mobile.devices.aircat

/**
 * Paired registry identity must agree with the remote server-info snapshot.
 * This is only a consistency check, NOT a signature, pinning, or TLS verifier.
 */
internal object AirCatRemoteIdentityPolicy {
    fun error(
        targetDeviceId: String,
        expectedServerId: String,
        expectedMichiId: String,
        expectedPublicKey: String,
        remoteServerId: String,
        remoteMichiId: String,
        remotePublicKey: String,
    ): String? {
        if (expectedServerId.isNotEmpty() && expectedServerId != remoteServerId) {
            return "Inconsistencia de serverId remoto: esperado $expectedServerId pero respondió ${remoteServerId.ifEmpty { "<vacio>" }}"
        }
        if (expectedMichiId.isNotEmpty() && expectedMichiId != remoteMichiId) {
            return "Inconsistencia de MichiId remoto: esperado $expectedMichiId pero respondió ${remoteMichiId.ifEmpty { "<vacio>" }}"
        }
        if (expectedPublicKey.isNotEmpty() && expectedPublicKey != remotePublicKey) {
            return "Inconsistencia criptográfica: clave pública remota ${if (remotePublicKey.isEmpty()) "no provista" else "no coincide"} para $targetDeviceId"
        }
        return null
    }
}
