package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.data.repository.ExtendedTagUnavailableReason

class MetadataEditorRetryPolicyTest {

    @Test
    fun unsupportedFormatAndStructuralReasonsDoNotOfferAFalseRetry() {
        assertNull(readRetryLabel(ExtendedTagUnavailableReason.TAG_READER_UNSUPPORTED))
        assertNull(readRetryLabel(ExtendedTagUnavailableReason.NOT_CONTENT_URI))
        assertNull(readRetryLabel(ExtendedTagUnavailableReason.INVALID_TRACK_ID))
        assertNull(readRetryLabel(ExtendedTagUnavailableReason.INVALID_SIZE))
    }

    @Test
    fun permissionAndTransientFailuresKeepTheirExactReattemptLabel() {
        assertEquals("Reintentar con permiso",
            readRetryLabel(ExtendedTagUnavailableReason.PERMISSION_DENIED))
        assertEquals("Volver a intentar",
            readRetryLabel(ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE))
        assertEquals("Volver a intentar",
            readRetryLabel(ExtendedTagUnavailableReason.DOCUMENT_CHANGED_DURING_READ))
        assertEquals("Volver a intentar",
            readRetryLabel(ExtendedTagUnavailableReason.SIZE_MISMATCH))
    }
}
