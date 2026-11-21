package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataReadAccessPolicyTest {
    private fun kind(owner: String, scheme: String?, authority: String?) =
        MetadataReadAccessPolicy.classify(owner,scheme,authority)

    @Test fun localSourceIdEmptyMeansMediaStoreOnly() {
        assertEquals(MetadataReadAccessPolicy.Kind.MEDIASTORE,kind("","content","media"))
        assertEquals(MetadataReadAccessPolicy.Kind.REJECT,
            kind("","content","com.android.externalstorage.documents"))
    }
    @Test fun knownSystemSafRootsAllowedUnderNonemptyOwner() {
        for(auth in listOf("com.android.externalstorage.documents","com.android.providers.media.documents",
                "com.android.providers.downloads.documents"))
            assertEquals(MetadataReadAccessPolicy.Kind.SAF_LOCAL,kind("saf-A","content",auth))
    }
    @Test fun safOwnerCannotReadMediaStoreAsSaf() {
        assertEquals(MetadataReadAccessPolicy.Kind.REJECT,kind("saf-A","content","media"))
    }
    @Test fun arbitraryCloudProviderRejected() {
        assertEquals(MetadataReadAccessPolicy.Kind.REJECT,
            kind("saf-A","content","com.google.android.apps.docs.storage"))
    }
    @Test fun allNonContentSchemesRejected() {
        for(s in listOf("file","http","https",null))
            assertEquals(MetadataReadAccessPolicy.Kind.REJECT,kind("",s,"media"))
    }
    @Test fun nullAndUnknownAuthoritiesRejected() {
        assertEquals(MetadataReadAccessPolicy.Kind.REJECT,kind("","content",null))
        assertEquals(MetadataReadAccessPolicy.Kind.REJECT,kind("","content","unknown"))
    }
}
