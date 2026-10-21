package org.michimusic.link.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MichiIdentityTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun freshInstall_generatesAValidIdentity() {
        val identity = MichiIdentity(context)

        assertNotNull(identity.publicKeyBase64Url)
        assertTrue(identity.publicKeyBase64Url.isNotBlank())
    }

    @Test
    fun existingStorage_preservesIdentityAcrossInstances() {
        val first = MichiIdentity(context)
        val firstPublicKey = first.publicKeyBase64Url

        val second = MichiIdentity(context)

        assertEquals(
            "a second instance backed by the same storage must be the same identity, not a new one",
            firstPublicKey,
            second.publicKeyBase64Url,
        )
    }
}
