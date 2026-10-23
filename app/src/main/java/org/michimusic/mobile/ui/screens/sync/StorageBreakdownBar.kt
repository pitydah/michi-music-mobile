package org.michimusic.mobile.ui.screens.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.core.sync.StoragePreflightEvaluator
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceContainerLowest
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

private val ColorSystemOther = Color(0xFF4A4D59)
private val ColorLocalMusic = TertiaryCyan
private val ColorSyncPayload = AccentCoral
private val ColorSafetyReserve = Color(0xFFFF6B6B)
private val ColorFreeSpace = SurfaceContainerLowest

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageBreakdownBar(
    totalStorageBytes: Long,
    systemAndOtherBytes: Long,
    currentLocalMusicBytes: Long,
    newSyncBytes: Long,
    safetyBufferBytes: Long = StoragePreflightEvaluator.SAFETY_BUFFER_BYTES,
    modifier: Modifier = Modifier,
) {
    val total = if (totalStorageBytes > 0) totalStorageBytes else 1L
    val usedBeforeSync = systemAndOtherBytes + currentLocalMusicBytes
    val freeBeforeSync = (totalStorageBytes - usedBeforeSync).coerceAtLeast(0L)
    val freeAfterSync = (freeBeforeSync - newSyncBytes - safetyBufferBytes).coerceAtLeast(0L)

    val wSystem = (systemAndOtherBytes.toFloat() / total).coerceIn(0f, 1f)
    val wLocal = (currentLocalMusicBytes.toFloat() / total).coerceIn(0f, 1f)
    val wSync = (newSyncBytes.toFloat() / total).coerceIn(0f, 1f)
    val wSafety = (safetyBufferBytes.toFloat() / total).coerceIn(0f, 1f)
    val wFree = (freeAfterSync.toFloat() / total).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("storage_breakdown_bar")
    ) {
        // Top summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Almacenamiento del Dispositivo",
                style = MichiTypography.sectionTitle,
                color = PureWhite,
                fontSize = 15.sp,
            )
            Text(
                text = "${StoragePreflightEvaluator.formatBytes(freeBeforeSync)} libres de ${StoragePreflightEvaluator.formatBytes(total)}",
                style = MichiTypography.metadata,
                color = TextSecondary,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Segmented Visual Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GlassFillLow)
                .border(1.dp, GlassBorderLow, RoundedCornerShape(8.dp))
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(16.dp)) {
                if (wSystem > 0.001f) {
                    Box(modifier = Modifier.weight(wSystem).height(16.dp).background(ColorSystemOther))
                }
                if (wLocal > 0.001f) {
                    Box(modifier = Modifier.weight(wLocal).height(16.dp).background(ColorLocalMusic))
                }
                if (wSync > 0.001f) {
                    Box(modifier = Modifier.weight(wSync).height(16.dp).background(ColorSyncPayload))
                }
                if (wSafety > 0.001f) {
                    Box(modifier = Modifier.weight(wSafety).height(16.dp).background(ColorSafetyReserve))
                }
                if (wFree > 0.001f) {
                    Box(modifier = Modifier.weight(wFree).height(16.dp).background(ColorFreeSpace))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Legend Items
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StorageLegendItem(
                color = ColorSystemOther,
                label = "Sistema y Apps",
                sizeText = StoragePreflightEvaluator.formatBytes(systemAndOtherBytes),
            )
            StorageLegendItem(
                color = ColorLocalMusic,
                label = "Música Local",
                sizeText = StoragePreflightEvaluator.formatBytes(currentLocalMusicBytes),
            )
            StorageLegendItem(
                color = ColorSyncPayload,
                label = "Nueva Descarga",
                sizeText = StoragePreflightEvaluator.formatBytes(newSyncBytes),
            )
            StorageLegendItem(
                color = ColorSafetyReserve,
                label = "Reserva Seguridad (100 MB)",
                sizeText = StoragePreflightEvaluator.formatBytes(safetyBufferBytes),
            )
        }
    }
}

@Composable
private fun StorageLegendItem(
    color: Color,
    label: String,
    sizeText: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$label: ",
            style = MichiTypography.metadata,
            color = TextMuted,
            fontSize = 11.sp,
        )
        Text(
            text = sizeText,
            style = MichiTypography.metadata,
            color = PureWhite,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
    }
}
