package org.michimusic.mobile.ui.screens.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.sync.DeviceAction
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.components.premium.MichiSurface
import org.michimusic.mobile.ui.components.premium.MichiSurfaceLevel
import org.michimusic.mobile.ui.theme.ErrorColor
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailSheet(
    deviceItem: DeviceUiItem,
    onDismiss: () -> Unit,
    onPrimaryAction: (DeviceAction) -> Unit,
    onSecondaryAction: (DeviceAction) -> Unit,
    onForgetDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showUnpairConfirmation by remember { mutableStateOf(false) }
    var showTechDetails by remember { mutableStateOf(false) }

    MichiBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("device_detail_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header Row: Icon, Title, Status & Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MichiShapes.md)
                            .background(GlassFillLow),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = getDeviceIcon(deviceItem.deviceType),
                            contentDescription = null,
                            tint = if (deviceItem.connectionStatus == DeviceConnectionStatus.CONNECTED) TertiaryCyan else PureWhite,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    Column {
                        Text(
                            text = deviceItem.name,
                            style = MichiTypography.cardTitle,
                            color = PureWhite,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = "${formatDeviceTypeLabel(deviceItem.deviceType)} · ${deviceItem.ip}",
                            style = MichiTypography.metadata,
                            color = TextSecondary,
                        )
                    }
                }

                MichiIconAction(
                    icon = Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    onClick = onDismiss,
                    tint = TextSecondary,
                    minTargetSize = 48.dp,
                    testTag = "device_detail_close",
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Trust & Connection Status Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrustBadge(status = deviceItem.trustStatus)
                ConnectionBadge(status = deviceItem.connectionStatus)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Verified Capabilities
            if (deviceItem.capabilities.isNotEmpty()) {
                Text(
                    text = "Capacidades Verificadas",
                    style = MichiTypography.microLabel,
                    color = TextMuted,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    deviceItem.capabilities.forEach { cap ->
                        Box(
                            modifier = Modifier
                                .clip(MichiShapes.pill)
                                .background(GlassFillLow)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = cap,
                                style = MichiTypography.microLabel,
                                color = PureWhite,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Primary Action Button (Exactly 1 primary CTA)
            deviceItem.primaryAction?.let { action ->
                Button(
                    onClick = {
                        onPrimaryAction(action)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("device_detail_primary_cta"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (action.isDestructive) ErrorColor else PrimaryPinkContainer,
                    ),
                    shape = MichiShapes.sm,
                ) {
                    Text(
                        text = action.label,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Secondary Actions List
            if (deviceItem.secondaryActions.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    deviceItem.secondaryActions.forEach { action ->
                        OutlinedButton(
                            onClick = {
                                onSecondaryAction(action)
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("device_detail_secondary_${action.type.name}"),
                            shape = MichiShapes.sm,
                        ) {
                            Text(
                                text = action.label,
                                color = if (action.isDestructive) ErrorColor else PureWhite,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Technical Details Collapsible Section
            MichiSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showTechDetails = !showTechDetails },
                level = MichiSurfaceLevel.Content,
                shape = MichiShapes.sm,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = null,
                                tint = TertiaryCyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Detalles Técnicos y Criptográficos",
                                style = MichiTypography.metadata,
                                color = PureWhite,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Icon(
                            imageVector = if (showTechDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = TextSecondary,
                        )
                    }

                    AnimatedVisibility(visible = showTechDetails) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TechnicalDetailItem("Identificador", deviceItem.id)
                            TechnicalDetailItem("IP / Puerto", "${deviceItem.ip}:${deviceItem.port}")
                            TechnicalDetailItem("Tipo de nodo", deviceItem.deviceType)
                            deviceItem.underlyingDevice?.let { dev ->
                                if (dev.roles.isNotEmpty()) {
                                    TechnicalDetailItem("Roles", dev.roles.joinToString(", "))
                                }
                                if (dev.features.isNotEmpty()) {
                                    TechnicalDetailItem("Features", dev.features.joinToString(", "))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Unpair / Forget Device Action
            if (deviceItem.trustStatus == DeviceTrustStatus.TRUSTED || deviceItem.trustStatus == DeviceTrustStatus.REVOKED) {
                OutlinedButton(
                    onClick = { showUnpairConfirmation = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("device_detail_forget_button"),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = ErrorColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Olvidar este dispositivo",
                        color = ErrorColor,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    if (showUnpairConfirmation) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirmation = false },
            title = {
                Text(
                    text = "¿Olvidar ${deviceItem.name}?",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "Se eliminarán las credenciales criptográficas locales de Michi Link para este dispositivo. Deberás volver a vincularlo para conectarte.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnpairConfirmation = false
                        onForgetDevice()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("device_unpair_confirm"),
                ) {
                    Text("Olvidar", color = ErrorColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirmation = false }) {
                    Text("Cancelar", color = PureWhite)
                }
            },
            containerColor = Color(0xFF1E2129),
        )
    }
}

@Composable
private fun TechnicalDetailItem(label: String, value: String) {
    Column {
        Text(text = label, style = MichiTypography.microLabel, color = TextMuted)
        Text(
            text = value,
            style = MichiTypography.metadata,
            color = PureWhite,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun TrustBadge(status: DeviceTrustStatus) {
    val (color, text) = when (status) {
        DeviceTrustStatus.TRUSTED -> TertiaryCyan to "Confiable"
        DeviceTrustStatus.PENDING -> PrimaryPinkContainer to "Pendiente"
        DeviceTrustStatus.REVOKED -> ErrorColor to "Revocado"
        DeviceTrustStatus.IDENTITY_MISMATCH -> ErrorColor to "Clave inválida"
        DeviceTrustStatus.UNKNOWN -> TextMuted to "No vinculado"
    }

    Box(
        modifier = Modifier
            .clip(MichiShapes.pill)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "● $text",
            style = MichiTypography.microLabel,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ConnectionBadge(status: DeviceConnectionStatus) {
    val (color, text) = when (status) {
        DeviceConnectionStatus.CONNECTED -> TertiaryCyan to "Conectado"
        DeviceConnectionStatus.CONNECTING -> PrimaryPinkContainer to "Conectando..."
        DeviceConnectionStatus.DISCONNECTED -> TextMuted to "Desconectado"
        DeviceConnectionStatus.OFFLINE -> GlassBorderLow to "Fuera de línea"
        DeviceConnectionStatus.ERROR -> ErrorColor to "Error de enlace"
    }

    Box(
        modifier = Modifier
            .clip(MichiShapes.pill)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MichiTypography.microLabel,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun getDeviceIcon(deviceType: String): ImageVector {
    return when (deviceType.lowercase()) {
        "server" -> Icons.Filled.Dns
        "desktop", "player", "pc" -> Icons.Filled.LaptopMac
        "stream", "receiver", "speaker" -> Icons.Filled.Speaker
        "phone", "mobile" -> Icons.Filled.PhoneAndroid
        else -> Icons.Filled.DesktopWindows
    }
}

private fun formatDeviceTypeLabel(deviceType: String): String {
    return when (deviceType.lowercase()) {
        "server" -> "Micro Server"
        "desktop", "player" -> "Michi Player"
        "stream", "receiver" -> "Michi Stream"
        "phone" -> "Teléfono"
        else -> "Michi Link"
    }
}
