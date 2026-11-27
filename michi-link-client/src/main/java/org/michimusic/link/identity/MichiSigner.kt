package org.michimusic.link.identity

/**
 * MIM-02: the minimal signing surface Home enrollment/authentication needs.
 * It deliberately exposes no private key and no storage; [MichiIdentity] is the
 * production implementation and tests may provide a deterministic signer.
 */
interface MichiSigner {
    val publicKeyBase64Url: String
    val michiId: String
    fun signCanonicalPayload(payload: ByteArray): String
}
