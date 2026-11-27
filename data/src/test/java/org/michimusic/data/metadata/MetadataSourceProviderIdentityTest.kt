package org.michimusic.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataProviderRecoveryProfile

class MetadataSourceProviderIdentityTest {
    @Test fun bindsMediaStoreAndSafToDifferentProfileAuthorities() {
        val media = MetadataSourceProviderIdentity.classify(
            "", "content", "media",
        )!!
        assertEquals(MetadataDocumentProvider.MEDIASTORE, media.documentProvider)
        assertEquals(
            MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
            media.profileProvider,
        )

        val saf = MetadataSourceProviderIdentity.classify(
            "safroot_exact", "content", "com.android.externalstorage.documents",
        )!!
        assertEquals(MetadataDocumentProvider.SAF, saf.documentProvider)
        assertEquals(
            MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
            saf.profileProvider,
        )
    }

    @Test fun rejectsMediaAuthorityUnderSafOwnershipAndUnknownProviders() {
        assertNull(MetadataSourceProviderIdentity.classify(
            "safroot_exact", "content", "media",
        ))
        assertNull(MetadataSourceProviderIdentity.classify(
            "safroot_exact", "content", "example.cloud.documents",
        ))
    }
}
