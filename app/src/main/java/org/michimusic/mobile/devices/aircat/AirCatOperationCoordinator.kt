package org.michimusic.mobile.devices.aircat

import android.content.Context
import android.os.StatFs
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.link.ConnectionManager
import org.michimusic.link.LinkClient
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.identity.MichiIdentity
import org.michimusic.link.identity.MichiIdentityAuthority
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * AM5B: screen-scoped AirCat use cases. No ViewModel, polling, audio engine,
 * retained application scope or new identity. The caller owns [scope].
 */
internal class AirCatOperationCoordinator(
    private val context: Context,
    private val registry: PairedDeviceRegistry,
    private val connectionManager: ConnectionManager,
    private val identityAuthority: MichiIdentityAuthority,
    private val airCatClient: AirCatTransferClient?,
    private val lease: AirCatLeaseCoordinator,
    private val clientFactory: (PairedDevice, MichiIdentity) -> LinkClient = { device, identity ->
        LinkClient(
            baseUrl = device.lastUrl,
            sessionToken = device.deviceToken,
            deviceToken = device.deviceToken,
            clientDeviceId = device.pairedClientDeviceId.ifEmpty { identity.michiId },
            pairedClientDeviceId = device.pairedClientDeviceId,
        )
    },
) {
    private class TerminalResult(private val callback: (Boolean, String) -> Unit) {
        private val delivered = AtomicBoolean(false)
        fun send(ok: Boolean, message: String) {
            if (delivered.compareAndSet(false, true)) {
                // A UI callback must never skip finally/lock cleanup.
                runCatching { callback(ok, message) }
            }
        }
    }

    private data class SelectedClient(val client: LinkClient, val owned: Boolean)

    /** The paired registry is a local hint, not proof of Link cryptographic trust. */
    private fun resolveClient(
        targetDeviceId: String,
        device: PairedDevice,
        identity: MichiIdentity,
        currentClient: LinkClient?,
    ): SelectedClient {
        require(device.deviceToken.isNotBlank() && device.lastUrl.isNotBlank()) {
            "El dispositivo $targetDeviceId no posee credenciales o URL válidas"
        }
        fun matches(candidate: LinkClient?): Boolean = candidate != null &&
            candidate.baseUrl.trimEnd('/') == device.lastUrl.trimEnd('/') &&
            candidate.deviceToken == device.deviceToken &&
            candidate.pairedClientDeviceId == device.pairedClientDeviceId

        val managed = connectionManager.getClient(targetDeviceId)
        if (matches(managed)) return SelectedClient(managed!!, owned = false)
        if (matches(currentClient)) return SelectedClient(currentClient!!, owned = false)
        return SelectedClient(clientFactory(device, identity), owned = true)
    }

    private suspend fun checkRemoteIdentity(
        targetDeviceId: String,
        device: PairedDevice,
        client: LinkClient,
    ): String? {
        if (registry.getDevice(targetDeviceId)?.isRevoked != false) {
            return "El dispositivo $targetDeviceId ha sido revocado"
        }
        val info = client.getServerInfo().getOrElse {
            return "No se pudo verificar la sesión remota con $targetDeviceId: ${it.message}"
        }
        return AirCatRemoteIdentityPolicy.error(
            targetDeviceId = targetDeviceId,
            expectedServerId = device.effectiveServerId,
            expectedMichiId = device.effectiveMichiId,
            expectedPublicKey = device.effectivePublicKey,
            remoteServerId = info.effectiveServerId,
            remoteMichiId = info.michiId ?: "",
            remotePublicKey = info.publicKey ?: "",
        )
    }

    private suspend fun freeBytes(): Long = withContext(Dispatchers.IO) {
        StatFs(context.filesDir.absolutePath).availableBytes.also {
            require(it >= 0L) { "Espacio de almacenamiento no disponible" }
        }
    }

    private fun finishLease(held: Pair<FileChannel, FileLock>?) {
        runCatching { held?.second?.release() }
        runCatching { held?.first?.close() }
        lease.leave()
    }

    fun recover(
        targetDeviceId: String,
        scope: CoroutineScope,
        currentClient: LinkClient?,
        onResult: (Boolean, String) -> Unit,
    ) {
        val terminal = TerminalResult(onResult)
        val transfer = airCatClient ?: return terminal.send(false, "Servicio AirCat no disponible")
        val device = registry.getDevice(targetDeviceId)
            ?: return terminal.send(false, "Dispositivo no encontrado o revocado")
        if (device.isRevoked) return terminal.send(false, "Dispositivo no encontrado o revocado")
        if (!lease.tryEnter()) return terminal.send(false, "AirCat está ocupado; no se puede recuperar ahora")

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var held: Pair<FileChannel, FileLock>? = null
            var privateClient: LinkClient? = null
            try {
                held = lease.acquireFileLease()
                if (held == null) {
                    terminal.send(false, "Otra instancia de Michi está usando AirCat")
                    return@launch
                }
                coroutineContext.ensureActive()
                val identity = identityAuthority.await() ?: run {
                    terminal.send(false, "La identidad del dispositivo no está disponible. Reintentá en un momento.")
                    return@launch
                }
                val selected = resolveClient(targetDeviceId, device, identity, currentClient)
                if (selected.owned) privateClient = selected.client
                // A27: this legacy UI action can ONLY repair journal/Room state
                // locally. The client rejects network resume without a fresh
                // signed manifest and Home grant. Never ping the source merely
                // to release an already committed local backup.
                val result = transfer.recoverIncompleteTransfers(targetDeviceId, selected.client)
                if (result.isSuccess) {
                    val count = result.getOrThrow().size
                    terminal.send(true, "Recuperación completada (" +
                        context.resources.getQuantityString(
                            org.michimusic.mobile.R.plurals.aircat_recovered_tracks, count, count,
                        ) + ")")
                } else {
                    terminal.send(false, "Fallo en recuperación: ${result.exceptionOrNull()?.message}")
                }
            } catch (cancel: CancellationException) {
                terminal.send(false, "Recuperación AirCat cancelada")
                throw cancel
            } catch (error: Exception) {
                terminal.send(false, "Error durante recuperación: ${error.message}")
            } finally {
                runCatching { privateClient?.close() }
                finishLease(held)
            }
        }
    }

    fun startTransfer(
        targetDeviceId: String,
        scope: CoroutineScope,
        currentClient: LinkClient?,
        onResult: (Boolean, String) -> Unit,
    ) {
        val terminal = TerminalResult(onResult)
        val transfer = airCatClient ?: return terminal.send(false, "Servicio AirCat no disponible")
        val device = registry.getDevice(targetDeviceId)
            ?: return terminal.send(false, "Dispositivo no encontrado")
        if (device.isRevoked) return terminal.send(false, "El dispositivo $targetDeviceId ha sido revocado")
        val canonicalSourceId = device.effectiveMichiId
        // P0: refuse before getServerInfo/fetchSyncManifest can send a bearer.
        // Home-enrolled pin and CA-verified HTTPS are mandatory even for the
        // preliminary remote metadata request. Local registry pairing is not proof.
        // Keystore-backed Home enrollment is read only on the operation's IO
        // coroutine; never block a Compose button on encrypted preferences.
        if (!lease.tryEnter()) return terminal.send(false, "Ya existe una transferencia AirCat en curso")

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var held: Pair<FileChannel, FileLock>? = null
            var privateClient: LinkClient? = null
            var adoptedTransferId: String? = null
            val custodian = transfer as? AirCatOwnedClientCustodian
            try {
                held = lease.acquireFileLease()
                if (held == null) {
                    terminal.send(false, "Otra instancia de Michi está transfiriendo por AirCat")
                    return@launch
                }
                coroutineContext.ensureActive()
                val enrolledPin = withContext(Dispatchers.IO) {
                    if (canonicalSourceId.isBlank()) null
                    else transfer.enrolledSourceTlsPin(canonicalSourceId, device.lastUrl)
                }
                if (enrolledPin == null) {
                    terminal.send(false,
                        "AirCat seguro requiere vinculación Home, certificado HTTPS fijado y proveedor autorizado")
                    return@launch
                }
                if (device.deviceToken.isBlank() || device.lastUrl.isBlank()) {
                    terminal.send(false, "El dispositivo $targetDeviceId no posee credenciales o URL válidas")
                    return@launch
                }
                val identity = identityAuthority.await() ?: run {
                    terminal.send(false, "La identidad del dispositivo no está disponible. Reintentá en un momento.")
                    return@launch
                }
                val selected = resolveClient(targetDeviceId, device, identity, currentClient)
                // A63: a cached ConnectionManager client or fresh signed UDP
                // observation must not replace the exact Home-enrolled URL.
                val directRefusal = AirCatDirectEndpointPolicy.refusal(
                    device.lastUrl, selected.client.baseUrl, enrolledPin)
                if (directRefusal != null) {
                    if (selected.owned) runCatching { selected.client.close() }
                    terminal.send(false, "La ubicación segura del dispositivo cambió; vuelva a vincularlo en Home")
                    return@launch
                }
                // A27: always use a NEW pinned transport for the complete
                // AirCat metadata / manifest / transfer flow. Existing borrowed
                // ConnectionManager clients may be HTTP or lack a Home pin.
                val secureClient = try {
                    selected.client.pinnedAirCatSession(enrolledPin)
                } finally {
                    if (selected.owned) runCatching { selected.client.close() }
                }
                privateClient = secureClient
                val mismatch = checkRemoteIdentity(targetDeviceId, device, secureClient)
                if (mismatch != null) {
                    terminal.send(false, mismatch)
                    return@launch
                }
                val manifest = secureClient.fetchAirCatManifest().getOrElse {
                    terminal.send(false, "Error al obtener catálogo: ${it.message}")
                    return@launch
                }
                if (manifest.deviceId != canonicalSourceId) {
                    terminal.send(false, "El manifiesto AirCat pertenece a otro Michi ID")
                    return@launch
                }
                val tracks = manifest.effectiveTracks
                if (tracks.isEmpty()) {
                    terminal.send(true, "No hay pistas disponibles para transferir")
                    return@launch
                }
                val prepared = AirCatManifestFactory.prepare(
                    sourceDeviceId = canonicalSourceId,
                    destinationMichiId = identity.michiId,
                    tracks = tracks,
                    nowMillis = System.currentTimeMillis(),
                )
                // A27: obtain a short-lived grant from the real producer over
                // the ALREADY pinned metadata connection. No invented local
                // signature, no grant inferred from the sync manifest itself.
                val request = AirCatGrantRequest.create(prepared.intent, prepared.manifest)
                val grantJson = secureClient.requestAirCatAuthorization(
                    Json.encodeToString(request),
                ).getOrElse { error ->
                    terminal.send(false,
                        "El emisor no ofrece autorización AirCat Home verificada: ${error.message}")
                    return@launch
                }
                val signedGrant = runCatching {
                    Json.decodeFromString<AirCatSignedGrant>(grantJson)
                }.getOrElse {
                    terminal.send(false, "El emisor devolvió un grant AirCat inválido")
                    return@launch
                }
                val authenticatedIntent = prepared.intent.copy(
                    signedGrant = signedGrant,
                    authorizationNonceBase64Url = request.clientNonceBase64Url,
                )
                val available = freeBytes() // No fictitious 10 GiB on StatFs failure.
                if (custodian == null) {
                    terminal.send(false, "El cliente AirCat no administra sesiones reanudables")
                    return@launch
                }
                if (custodian != null) {
                    custodian.adoptOwnedClient(prepared.intent.transferId, secureClient)
                    adoptedTransferId = prepared.intent.transferId
                    privateClient = null // AirCat owns pause/resume lifetime from here.
                }
                val result = transfer.executeTransfer(
                    intent = authenticatedIntent,
                    manifest = prepared.manifest,
                    linkClient = secureClient,
                    availableStorageBytes = available,
                )
                if (result.isSuccess) {
                    val count = result.getOrThrow().size
                    terminal.send(true, "Transferencia AirCat completada (" +
                        context.resources.getQuantityString(
                            org.michimusic.mobile.R.plurals.aircat_transferred_tracks, count, count,
                        ) + ")")
                } else {
                    terminal.send(false, "Transferencia AirCat falló: ${result.exceptionOrNull()?.message}")
                }
            } catch (cancel: CancellationException) {
                terminal.send(false, "Transferencia AirCat cancelada")
                throw cancel
            } catch (error: Exception) {
                terminal.send(false, "Error en AirCat: ${error.message}")
            } finally {
                // An AirCat session may retain its client across pause/resume.
                adoptedTransferId?.let { custodian?.releaseIfNotRetained(it) }
                runCatching { privateClient?.close() }
                finishLease(held)
            }
        }
    }
}
