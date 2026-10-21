package org.michimusic.mobile.ui.screens

import org.michimusic.link.dto.DiagnosticReport
import org.michimusic.link.dto.DiagnosticTest

object DiagnosticsRedactor {

    private val SENSITIVE_KEY_REGEX = Regex("""(?i)(token|bearer|password|secret|auth|apikey|credential|key)[=:\s]+([^\s,;]+)""")
    private val URL_AUTH_REGEX = Regex("""https?://[^:@]+:[^@]+@""")

    fun redactString(input: String): String {
        var result = input
        // Redact URL credentials like http://user:pass@host
        result = URL_AUTH_REGEX.replace(result, "http://[REDACTED_AUTH]@")
        // Redact explicit tokens or secrets
        result = SENSITIVE_KEY_REGEX.replace(result) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        return result
    }

    fun redactReport(report: DiagnosticReport): DiagnosticReport {
        return report.copy(
            device = report.device,
            server = report.server,
            baseUrl = redactBaseUrl(report.baseUrl),
            authStrategy = report.authStrategy,
            tokenRefreshSupported = report.tokenRefreshSupported,
            tests = report.tests.map { test ->
                test.copy(
                    message = redactString(test.message)
                )
            },
            errors = report.errors.map { redactString(it) },
            recommendations = report.recommendations.map { redactString(it) },
            timestamp = report.timestamp,
        )
    }

    fun redactBaseUrl(url: String): String {
        if (url.isEmpty()) return ""
        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme ?: "http"
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val host = uri.host ?: "unknown"
            if (host.matches(Regex("""^(?:10\.|192\.168\.|172\.(?:1[6-9]|2[0-9]|3[01])\.|127\.).*"""))) {
                "$scheme://[PRIVATE_HOST]$port"
            } else {
                "$scheme://$host$port"
            }
        } catch (_: Exception) {
            "[REDACTED_URL]"
        }
    }

    fun formatExportReport(report: DiagnosticReport): String {
        val sanitized = redactReport(report)
        val sb = StringBuilder()
        sb.appendLine("=== MICHI MUSIC MOBILE — INFORME DE DIAGNÓSTICO ===")
        sb.appendLine("Dispositivo: ${sanitized.device}")
        sb.appendLine("Servidor: ${sanitized.server}")
        sb.appendLine("URL Base: ${sanitized.baseUrl}")
        sb.appendLine("Estrategia Auth: ${sanitized.authStrategy}")
        sb.appendLine("Soporte Token Refresh: ${sanitized.tokenRefreshSupported}")
        sb.appendLine("Timestamp: ${sanitized.timestamp}")
        sb.appendLine()
        sb.appendLine("--- PRUEBAS EJECUTADAS (${sanitized.tests.size}) ---")
        sanitized.tests.forEach { t ->
            val status = if (t.passed) "[PASS]" else "[FAIL]"
            val msg = if (t.message.isNotEmpty()) " - ${t.message}" else ""
            sb.appendLine("$status ${t.name} (${t.durationMs}ms)$msg")
        }
        if (sanitized.errors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- ERRORES (${sanitized.errors.size}) ---")
            sanitized.errors.forEach { err -> sb.appendLine("- $err") }
        }
        if (sanitized.recommendations.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- RECOMENDACIONES (${sanitized.recommendations.size}) ---")
            sanitized.recommendations.forEach { rec -> sb.appendLine("- $rec") }
        }
        sb.appendLine("==================================================")
        return sb.toString()
    }
}
