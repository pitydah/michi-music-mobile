package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataProviderRecoveryProfile
import org.michimusic.core.metadata.MetadataReadAccessPolicy

/**
 * Exact bridge between the URI/source ownership policy, durable intent provider,
 * and provider-specific write profile. No filename or MIME can choose a provider.
 */
internal data class MetadataSourceProviderIdentity(
    val accessKind: MetadataReadAccessPolicy.Kind,
    val documentProvider: MetadataDocumentProvider,
    val profileProvider: MetadataProviderRecoveryProfile.Kind,
) {
    companion object {
        fun classify(
            sourceId: String,
            scheme: String?,
            authority: String?,
        ): MetadataSourceProviderIdentity? =
            when (val kind = MetadataReadAccessPolicy.classify(
                sourceId, scheme, authority,
            )) {
                MetadataReadAccessPolicy.Kind.MEDIASTORE ->
                    MetadataSourceProviderIdentity(
                        kind,
                        MetadataDocumentProvider.MEDIASTORE,
                        MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
                    )
                MetadataReadAccessPolicy.Kind.SAF_LOCAL ->
                    MetadataSourceProviderIdentity(
                        kind,
                        MetadataDocumentProvider.SAF,
                        MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
                    )
                MetadataReadAccessPolicy.Kind.REJECT -> null
            }
    }
}
