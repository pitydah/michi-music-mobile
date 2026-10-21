package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.DiagnosticReport
import org.michimusic.link.dto.DiagnosticTest

class DiagnosticsRedactionTest {

    @Test
    fun testSensitiveKeysRedacted() {
        val rawInput = "Error: token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 and secret: 123456 superSecret"
        val redacted = DiagnosticsRedactor.redactString(rawInput)

        assertFalse("Token must not leak in raw form", redacted.contains("eyJhbGci"))
        assertTrue("Token must be replaced with [REDACTED]", redacted.contains("token=[REDACTED]"))
        assertTrue("Secret must be replaced with [REDACTED]", redacted.contains("secret=[REDACTED]"))
    }

    @Test
    fun testPrivateHostRedactedInBaseUrl() {
        val report = DiagnosticReport(
            device = "Google Pixel 8",
            server = "Michi Audio Home Server",
            baseUrl = "http://192.168.1.100:8080",
            authStrategy = "TOKEN",
            tokenRefreshSupported = true,
            tests = listOf(
                DiagnosticTest(name = "network", passed = true, message = "Connected with token=secret123"),
            ),
            errors = listOf("Auth failed with bearer=abcd9876"),
            recommendations = listOf("Verify apikey=keyXYZ"),
        )

        val sanitized = DiagnosticsRedactor.redactReport(report)

        assertFalse("Private host IP must be redacted", sanitized.baseUrl.contains("192.168.1.100"))
        assertTrue("Private host mask present", sanitized.baseUrl.contains("[PRIVATE_HOST]"))

        val testMessage = sanitized.tests.first().message
        assertFalse("Test message must not contain secret123", testMessage.contains("secret123"))
        assertTrue("Test message must have redacted token", testMessage.contains("[REDACTED]"))

        val errorMsg = sanitized.errors.first()
        assertFalse("Error message must not contain abcd9876", errorMsg.contains("abcd9876"))

        val recMsg = sanitized.recommendations.first()
        assertFalse("Recommendation must not contain keyXYZ", recMsg.contains("keyXYZ"))
    }

    @Test
    fun testFormatExportReportContainsHeaderAndMasks() {
        val report = DiagnosticReport(
            device = "Samsung Galaxy S24",
            server = "Michi Home Hub",
            baseUrl = "http://10.0.0.5:9000",
            authStrategy = "ED25519",
            tokenRefreshSupported = false,
            tests = listOf(
                DiagnosticTest(name = "ping", passed = true, durationMs = 12),
                DiagnosticTest(name = "storage", passed = false, message = "low disk password=admin", durationMs = 5),
            ),
            errors = emptyList(),
            recommendations = emptyList(),
        )

        val formatted = DiagnosticsRedactor.formatExportReport(report)

        assertTrue(formatted.contains("=== MICHI MUSIC MOBILE — INFORME DE DIAGNÓSTICO ==="))
        assertTrue(formatted.contains("[PASS] ping (12ms)"))
        assertTrue(formatted.contains("[FAIL] storage (5ms) - low disk password=[REDACTED]"))
        assertFalse(formatted.contains("admin"))
        assertFalse(formatted.contains("10.0.0.5"))
    }
}
