package org.michimusic.link.identity

import org.michimusic.link.dto.ServerInfoDto
import org.michimusic.link.home.MichiIdentityHash

/**
 * MIM-03: a `server/info` payload is only trustworthy after its canonical
 * Ed25519+BLAKE3 identity is coherent. Legacy nodes without identity fields are
 * left to the segregated legacy path; a node that CLAIMS the canonical scheme
 * and then fails coherence is refused, never downgraded.
 */
object ServerIdentityPolicy {
    const val SCHEME = "ed25519-blake3-v1"

    fun refusal(info: ServerInfoDto): String? {
        val scheme = info.identityScheme
        val michiId = info.michiId
        val publicKey = info.publicKey
        // No claimed scheme: a legacy node. ConnectionManager still enforces the
        // identity pinned at pairing time; this policy adds no new legacy ban.
        if (scheme == null) return null
        if (scheme != SCHEME) return "SERVER_IDENTITY_SCHEME_UNSUPPORTED"
        if (michiId == null || publicKey == null) return "SERVER_IDENTITY_INCOMPLETE"
        if (!MichiIdentityHash.isMichiId(michiId)) return "SERVER_MICHI_ID_INVALID"
        if (MichiIdentityHash.deriveMichiId(publicKey) != michiId) {
            return "SERVER_IDENTITY_MISMATCH"
        }
        return null
    }
}
