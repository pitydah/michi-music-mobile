package org.michimusic.link

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.michimusic.link.errors.LinkException
import org.michimusic.link.dto.DiagnosticReport
import org.michimusic.link.dto.DiagnosticTest
import org.michimusic.link.dto.PairingStrategy
import java.io.ByteArrayOutputStream

class LinkDiagnostics(private val context: Context) {

    private val json = Json { prettyPrint = true }

    suspend fun runAll(linkClient: LinkClient, strategy: PairingStrategy = PairingStrategy.LEGACY): DiagnosticReport {
        val tests = mutableListOf<DiagnosticTest>()
        val errors = mutableListOf<String>()
        val recs = mutableListOf<String>()
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})"
        val tokenStore = TokenStore(context)

        // 1. Network check
        test(tests, "network") {
            try {
                val addr = java.net.InetAddress.getByName(java.net.URL(linkClient.baseUrl).host)
                addr.isReachable(2000)
            } catch (_: Exception) { false }
        }

        // 2. Ping / Status
        test(tests, "status") {
            val ok = linkClient.ping()
            if (!ok) errors.add("GET /api/v1/status falló - servidor no responde")
            ok
        }

        // 3. Server Info
        val info = runCatching { linkClient.getServerInfo().getOrNull() }.getOrNull()
        test(tests, "server/info") {
            val r = if (info != null) Result.success(info) else linkClient.getServerInfo()
            if (r.isFailure) { errors.add("server/info: ${r.exceptionOrNull()?.message}"); false }
            else {
                if (info?.service.isNullOrEmpty()) errors.add("server/info no incluye 'service'")
                if (info?.effectiveServerId.isNullOrEmpty()) recs.add("server/info no incluye server_id")
                true
            }
        }

        val auth = info?.effectiveAuthStrategy?.name ?: strategy.name

        // 4. Auth strategy
        test(tests, "auth_strategy") {
            if (auth == "LEGACY") recs.add("Servidor usa estrategia de autenticación LEGACY")
            true
        }

        // 5. Token
        val hasToken = tokenStore.isPaired()
        test(tests, "token_persistente") {
            if (!hasToken) recs.add("Realizar pairing para habilitar funciones completas")
            hasToken
        }

        // 6. Token refresh test
        val refreshToken = tokenStore.getRefreshToken()
        test(tests, "token_refresh") {
            if (refreshToken.isNullOrEmpty()) {
                if (info?.effectiveTokenRefresh == true) errors.add("token_refresh soportado pero no hay refresh_token")
                true
            } else {
                val r = linkClient.refreshToken(refreshToken)
                if (r.isFailure && r.exceptionOrNull() !is LinkException.NotImplemented) {
                    errors.add("token/refresh falló: ${r.exceptionOrNull()?.message}")
                    false
                } else true
            }
        }

        // 7. Tracks
        test(tests, "tracks") {
            val r = linkClient.fetchLibrary()
            if (r.isSuccess && r.getOrNull()?.tracks?.isEmpty() == true) {
                recs.add("Biblioteca vacía - no hay tracks disponibles")
            }
            if (r.isFailure) errors.add("tracks: ${r.exceptionOrNull()?.message}")
            r.isSuccess
        }

        // 8. Artwork
        val firstCoverId = linkClient.fetchLibrary().getOrNull()?.tracks?.firstOrNull()?.coverId ?: ""
        if (firstCoverId.isNotEmpty()) {
            test(tests, "artwork/$firstCoverId") {
                val baos = ByteArrayOutputStream()
                val r = linkClient.fetchCover(firstCoverId, baos)
                if (r.isFailure) errors.add("artwork falló: ${r.exceptionOrNull()?.message}")
                r.isSuccess
            }
        } else recs.add("No hay tracks para probar artwork")

        // 9. Stream
        val firstTrackId = linkClient.fetchLibrary().getOrNull()?.tracks?.firstOrNull()?.id ?: ""
        if (firstTrackId.isNotEmpty()) {
            test(tests, "stream/$firstTrackId") {
                val baos = ByteArrayOutputStream()
                val r = linkClient.streamTrack(firstTrackId, baos, bufferSize = 4096)
                if (r.isFailure) errors.add("stream falló: ${r.exceptionOrNull()?.message}")
                r.isSuccess
            }
        } else recs.add("No hay tracks para probar streaming")

        // 10. Sync manifest
        val clientDeviceId = tokenStore.getClientDeviceId() ?: ""
        if (clientDeviceId.isNotEmpty()) {
            test(tests, "sync/manifest") {
                val r = linkClient.fetchSyncManifest(clientDeviceId)
                if (r.isFailure) errors.add("sync/manifest: ${r.exceptionOrNull()?.message}")
                r.isSuccess
            }
        } else recs.add("No hay deviceId para probar sync/manifest")

        // 11. Sync delta
        if (clientDeviceId.isNotEmpty()) {
            test(tests, "sync/manifest/delta") {
                val r = linkClient.fetchSyncManifestDelta(clientDeviceId, "0")
                r.isSuccess || r.exceptionOrNull() is LinkException.NotImplemented
            }
        }

        // 12. Playback state
        test(tests, "playback/state") {
            val r = linkClient.getPlaybackState()
            if (r.isFailure) errors.add("playback/state: ${r.exceptionOrNull()?.message}")
            r.isSuccess
        }

        // 13. Playback control
        test(tests, "playback/control") {
            val r = linkClient.sendPlaybackCommand("play")
            if (r.isFailure) errors.add("playback/control play: ${r.exceptionOrNull()?.message}")
            r.isSuccess
        }

        // 14. Queue
        test(tests, "queue") {
            val r = linkClient.getQueue()
            r.isSuccess
        }

        // 15. Storage check
        test(tests, "storage") {
            try {
                val stat = StatFs(context.filesDir.absolutePath)
                val freeMB = stat.availableBytes / (1024 * 1024)
                if (freeMB < 50) errors.add("Almacenamiento bajo: ${freeMB}MB libres (mínimo 50MB)")
                true
            } catch (_: Exception) { true }
        }

        // Recommendations
        if (tests.count { it.passed } < 4) recs.add("Verificar conectividad de red con el servidor")
        if (info?.effectiveTokenRefresh == false) recs.add("token_refresh no soportado - actualizar servidor")
        if (info?.auth?.tokenRefresh == false && info?.effectiveTokenRefresh == false)
            recs.add("auth.token_refresh=false - refresco automático deshabilitado por el servidor")

        return DiagnosticReport(
            device = device,
            server = info?.effectiveName ?: "",
            baseUrl = linkClient.baseUrl,
            authStrategy = auth,
            tokenRefreshSupported = info?.effectiveTokenRefresh ?: false,
            tests = tests,
            errors = errors,
            recommendations = recs,
        )
    }

    fun exportJson(report: DiagnosticReport): String = json.encodeToString(report)

    private suspend fun test(tests: MutableList<DiagnosticTest>, name: String, block: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        val passed = try { block() } catch (_: Exception) { false }
        val duration = System.currentTimeMillis() - start
        tests.add(DiagnosticTest(name = name, passed = passed, durationMs = duration))
    }
}
