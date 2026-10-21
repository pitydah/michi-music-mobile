package org.michimusic.mobile.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SyncWorkerSecurityTest {

    @Test
    fun workInputContainsNoSecretsTest() {
        val inputData = SyncWorker.buildInputData(
            baseUrl = "http://192.168.1.50:8080",
            deviceId = "dev_12345",
            alias = "Pixel 8",
            clientDeviceId = "michi_id_67890",
        )

        // Verify that no credentials, tokens or keys are present in WorkManager inputData
        assertNull("sessionToken must not be stored in WorkManager Data", inputData.getString("sessionToken"))
        assertNull("deviceToken must not be stored in WorkManager Data", inputData.getString("deviceToken"))
        assertNull("token must not be stored in WorkManager Data", inputData.getString("token"))
        assertNull("privateKey must not be stored in WorkManager Data", inputData.getString("privateKey"))
        assertNull("secret must not be stored in WorkManager Data", inputData.getString("secret"))

        val allKeys = inputData.keyValueMap.keys
        assertFalse("No key containing 'token' is allowed", allKeys.any { it.contains("token", ignoreCase = true) })
        assertFalse("No key containing 'secret' is allowed", allKeys.any { it.contains("secret", ignoreCase = true) })
    }
}
