package org.michimusic.mobile.devices.aircat.experience

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/** A71-S9 design primitive, using the same elevated anodized surfaces as Devices. */
@Composable
internal fun AirCatAeroHeader(
    title: String,
    eyebrow: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AeroSurface(role = AeroSurfaceRole.Content, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("aircat_aero_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = PureWhite)
            }
            Column(
                modifier = Modifier.weight(1f).testTag("aircat_aero_header"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // A71-S9: at 2.0x a single line cut "Michi Link · Seguridad" down to
                // "MICHI LINK · SEGURI…", and two subtitle lines cut "…compartir tu
                // biblioteca". Losing words at 200% is a WCAG 1.4.4 failure, so both are
                // allowed to wrap; the ellipsis stays only as a last-resort guard.
                Text(eyebrow.uppercase(), color = TertiaryCyan, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // At 2.0x the title needs a second line on a 360dp screen; one line
                // truncated "Explorar música" into an unusable ellipsis.
                Text(title, color = PureWhite, fontSize = 23.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = TextSecondary, fontSize = 12.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun AirCatAeroHero(
    title: String,
    detail: String,
    actionLabel: String,
    actionTag: String,
    onAction: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    AeroSurface(
        role = AeroSurfaceRole.Raised,
        finish = AeroFinish.Anodized,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(48.dp)
                    .background(TertiaryCyan.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = TertiaryCyan, modifier = Modifier.size(27.dp)) }
            Text(title, color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
                    .testTag(actionTag),
                colors = ButtonDefaults.buttonColors(containerColor = TertiaryCyan),
                shape = RoundedCornerShape(14.dp),
            ) { Text(actionLabel, color = SurfaceObsidian, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
internal fun AirCatAeroEmptyState(
    title: String,
    detail: String,
    onPrimary: () -> Unit,
    primaryLabel: String,
    modifier: Modifier = Modifier,
    onSecondary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
) {
    AeroSurface(role = AeroSurfaceRole.Content,
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Info, null, tint = TertiaryCyan,
                modifier = Modifier.size(34.dp))
            Text(title, color = PureWhite, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextSecondary, fontSize = 13.sp,
                lineHeight = 19.sp)
            Button(onClick = onPrimary,
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget),
                colors = ButtonDefaults.buttonColors(containerColor = TertiaryCyan),
                shape = RoundedCornerShape(12.dp)) {
                Text(primaryLabel, color = SurfaceObsidian)
            }
            if (onSecondary != null && secondaryLabel != null) {
                OutlinedButton(onClick = onSecondary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)) {
                    Icon(Icons.Filled.Security, null, tint = TertiaryCyan)
                    Spacer(Modifier.size(6.dp))
                    Text(secondaryLabel, color = PureWhite)
                }
            }
        }
    }
}


/** The visual hierarchy is shared by catalogue, review, transfer and results. */
@Composable
internal fun AirCatAeroSectionTitle(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = PureWhite, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (!detail.isNullOrBlank()) Text(detail, color = TextSecondary, fontSize = 13.sp)
    }
}

/** A real filled track, unlike the old unpainted two-Box placeholder. */
@Composable
internal fun AirCatAeroProgress(fraction: Float, modifier: Modifier = Modifier) {
    val clamped = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier.fillMaxWidth().height(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(org.michimusic.mobile.ui.theme.GlassFillLow)
            .semantics { contentDescription = "Progreso ${kotlin.math.round(clamped * 100f).toInt()} por ciento" }
            .testTag("aircat_transfer_fraction"),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(clamped).height(8.dp)
                .background(TertiaryCyan).testTag("aircat_transfer_bar"),
        )
    }
}

/** A small noninteractive, truthful status strip. Never confuses capability with grant. */
@Composable
internal fun AirCatAeroFact(
    label: String,
    modifier: Modifier = Modifier,
    verified: Boolean = false,
) {
    AeroSurface(
        role = AeroSurfaceRole.Content,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Neutral by default: discovered/connected is not a Home grant.
            Box(Modifier.size(7.dp).background(
                if (verified) TertiaryCyan else TextSecondary,
                RoundedCornerShape(4.dp),
            ))
            Text(label, color = TextSecondary, fontSize = 12.sp)
        }
    }
}


/** Source-to-local destination, always explicit; no "receptor remoto" ambiguity. */
@Composable
internal fun AirCatAeroRouteFlow(origin: String, destination: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ORIGEN", color = TertiaryCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(origin, color = PureWhite, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "Transferencia hacia este teléfono",
                tint = TertiaryCyan, modifier = Modifier.size(24.dp))
        }
        Text("DESTINO", color = TertiaryCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(destination, color = PureWhite, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Results use the engine's terminal classification: never an optimistic callback. */
@Composable
internal fun AirCatAeroOutcome(kind: AirCatResultUi.Kind) {
    val success = kind == AirCatResultUi.Kind.COMPLETED
    val label = when (kind) {
        AirCatResultUi.Kind.COMPLETED -> "Verificación completada"
        AirCatResultUi.Kind.NO_FILES -> "Sin archivos transferidos"
        AirCatResultUi.Kind.CANCELLED -> "Operación cancelada"
        AirCatResultUi.Kind.FAILED -> "No completada"
        AirCatResultUi.Kind.UNCERTIFIED -> "Sin comprobante final"
    }
    val accent = if (success) TertiaryCyan else TextSecondary
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()) {
        Icon(if (success) Icons.Filled.CheckCircle else Icons.Filled.Info,
            contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        Text(label, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}


/** Honest per-file preview. The existing event API only exposes aggregate counters
 * and the active title; do NOT infer per-track SHA or Room commit from list order.
 * This list is the selection, not a fabricated transfer journal.
 */
@Composable
internal fun AirCatBatchPreview(
    tracks: List<AirCatTrackUi>,
    evidence: List<AirCatFileEvidenceUi> = emptyList(),
) {
    if (tracks.isEmpty()) return
    AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(MichiSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AirCatAeroSectionTitle("Archivos de este lote",
                "Selección confirmada · sin estados individuales inferidos")
            tracks.take(6).forEach { track ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(5.dp).background(TertiaryCyan,
                        RoundedCornerShape(3.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = PureWhite, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${track.format.uppercase()} · ${airCatHumanBytes(track.size)}",
                            color = TextSecondary, fontSize = 11.sp)
                        evidence.firstOrNull { it.remoteTrackId == track.remoteTrackId }?.let { row ->
                            Text("${row.label} · ${airCatHumanBytes(row.receivedBytes)} registrados",
                                color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (tracks.size > 6) Text("Y ${tracks.size - 6} archivos adicionales",
                color = TextSecondary, fontSize = 12.sp)
            Text("Los bytes y estados por archivo son registros locales. " +
                "La incorporación global solo se acredita por comprobante del motor.",
                color = TextSecondary, fontSize = 12.sp)
        }
    }
}

/** Only an existing checkpoint provides a recovery entry; no source is guessed. */
@Composable
internal fun AirCatAeroRecoveryCard(
    record: AirCatRecoveryUi,
    sourceName: String,
    onReview: () -> Unit,
) {
    AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(MichiSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AirCatAeroSectionTitle(sourceName,
                "${record.pendingTracks} pistas con checkpoint pendiente · reparación local")
            Text("Lote ${record.transferId.take(8)}… · la reanudación por red requiere Home nuevo",
                color = TextSecondary, fontSize = 12.sp)
            OutlinedButton(onClick = onReview,
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)) {
                Text("Revisar reparación local", color = PureWhite)
            }
        }
    }
}

/** Compact factual summary of checkpoint rows, not signed per-file verification. */
@Composable
internal fun AirCatAeroCheckpointSummary(rows: List<AirCatFileEvidenceUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AirCatAeroSectionTitle("Estado registrado por archivo",
            "Información local de checkpoints, sin inferir certificados individuales")
        rows.take(6).forEach { row ->
            Column {
                Text(row.remoteTrackId, color = PureWhite, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${row.label} · ${airCatHumanBytes(row.verifiedBytes)} verificados localmente",
                    color = TextSecondary, fontSize = 11.sp)
            }
        }
        if (rows.size > 6) Text("Y ${rows.size - 6} registros adicionales",
            color = TextSecondary, fontSize = 12.sp)
    }
}
