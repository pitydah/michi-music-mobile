package org.michimusic.link

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.michimusic.link.dto.DiagnosticReport
import org.michimusic.link.dto.DiagnosticTest
import org.michimusic.link.dto.PairingStrategy
import org.michimusic.link.errors.LinkException
import java.io.ByteArrayOutputStream

class LinkDiagnostics(private val context: Context) {

    private val json = Json { prettyPrint = true }

    suspend fun runAll(linkClient: LinkClient, strategy: PairingStrategy = PairingStrategy.LEGACY): DiagnosticReport {
        val tests = mutableListOf<DiagnosticTest>()
        val errors = mutableListOf<String>()
        val recs = mutableListOf<String>()
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})"

        // 1. Ping / Status
        test(tests, "ping") {
            val ok = linkClient.ping()
            if (!ok) errors.add("Ping falló - servidor no responde")
            ok
        }

        // 2. Server Info
        test(tests, "server/info") {
            val r = linkClient.getServerInfo()
            if (r.isFailure) { errors.add("server/info: ${r.exceptionOrNull()?.message}"); false }
            else true
        }
        val info = runCatching { linkClient.getServerInfo().getOrNull() }.getOrNull()

        val auth = info?.effectiveAuthStrategy?.name ?: strategy.name
        if (info != null) {
            if (info.service.isEmpty()) errors.add("server/info no incluye 'service'")
            if (info.effectiveServerId.isEmpty()) recs.add("server/info no incluye server_id - posible servidor legacy")
        }

        // 3. Pairing test (check token)
        val tokenStore = TokenStore(context)
        val hasToken = tokenStore.isPaired()
        test(tests, "token_persistente") { hasToken }

        // 4. Tracks
        test(tests, "tracks") {
            val r = linkClient.fetchLibrary()
            if (r.isSuccess && r.getOrNull()?.tracks?.isEmpty() == true) {
                recs.add("Biblioteca vacía - no hay tracks disponibles")
            }
            r.isSuccess
        }

        // 5. Stream
        val firstTrackId = linkClient.fetchLibrary().getOrNull()?.tracks?.firstOrNull()?.id ?: ""
        if (firstTrackId.isNotEmpty()) {
            test(tests, "stream/$firstTrackId") {
                val baos = ByteArrayOutputStream()
                val r = linkClient.streamTrack(firstTrackId, baos, bufferSize = 4096)
                if (r.isFailure) errors.add("stream falló: ${r.exceptionOrNull()?.message}")
                r.isSuccess
            }
        } else {
            recs.add("No hay tracks para probar streaming")
        }

        // 6. Artwork
        val firstCoverId = linkClient.fetchLibrary().getOrNull()?.tracks?.firstOrNull()?.coverId ?: ""
        if (firstCoverId.isNotEmpty()) {
            test(tests, "artwork/$firstCoverId") {
                val baos = ByteArrayOutputStream()
                val r = linkClient.fetchCover(firstCoverId, baos)
                if (r.isFailure) errors.add("artwork falló: ${r.exceptionOrNull()?.message}")
                r.isSuccess
            }
        }

        // 7. Playback State
        test(tests, "playback/state") {
            val r = linkClient.getPlaybackState()
            if (r.isFailure) errors.add("playback/state: ${r.exceptionOrNull()?.message}")
            r.isSuccess
        }

        // 8. Playback Control
        test(tests, "playback/control (play)") {
            val r = linkClient.sendPlaybackCommand("play")
            if (r.isFailure) errors.add("playback/control play: ${r.exceptionOrNull()?.message}")
            r.isSuccess
        }
        test(tests, "playback/control (pause)") {
            val r = linkClient.sendPlaybackCommand("pause")
            r.isSuccess
        }

        // 9. Queue
        test(tests, "queue") {
            val r = linkClient.getQueue()
            r.isSuccess
        }

        // 10. Sync manifest
        val deviceId = tokenStore.getClientDeviceId() ?: ""
        if (deviceId.isNotEmpty()) {
            test(tests, "sync/manifest") {
                val r = linkClient.fetchSyncManifest(deviceId)
                if (r.isFailure) errors.add("sync/manifest: ${r.exceptionOrNull()?.message}")
                r.isSuccess
            }
        } else {
            recs.add("No hay deviceId para probar sync/manifest")
        }

        // Recommendations
        if (!hasToken) recs.add("Realizar pairing para habilitar funciones completas")
        if (info?.effectiveTokenRefresh == false) recs.add("token_refresh no soportado - actualizar servidor")
        if (tests.count { it.passed } < 4) recs.add("Verificar conectividad de red con el servidor")

        return DiagnosticReport(
            device = device,
            server = info?.effectiveName ?: "",
            baseUrl = linkClient.baseUrl,
            authStrategy = auth,
            tests = tests,
            errors = errors,
            recommendations = recs,
        )
    }

    fun exportJson(report: DiagnosticReport): String = json.encodeToString(report)

    private suspend fun test(tests: MutableList<DiagnosticTest>, name: String, block: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        val passed = try {
            block()
        } catch (e: LinkException) {
            false
        } catch (e: Exception) {
            false
        }
        val duration = System.currentTimeMillis() - start
        tests.add(DiagnosticTest(name = name, passed = passed, durationMs = duration))
    }
}
