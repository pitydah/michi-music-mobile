package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkDiagnostics
import org.michimusic.link.TokenStore
import org.michimusic.link.dto.DiagnosticReport
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.PulsingDot
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var running by remember { mutableStateOf(false) }
    val tokenStore = remember { TokenStore(context) }
    val savedUrl = remember { tokenStore.getServerUrl() }
    val hasToken = remember { !tokenStore.getDeviceToken().isNullOrEmpty() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .drawBehind {
                drawCircle(
                    color = TertiaryCyan.copy(alpha = 0.08f),
                    radius = size.width * 0.7f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.1f),
                )
                drawCircle(
                    color = PrimaryPinkContainer.copy(alpha = 0.05f),
                    radius = size.width * 0.6f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.85f),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(24.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "TELEMETRÍA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TertiaryCyan,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = "Diagnóstico Michi Link",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = PureWhite,
                            letterSpacing = (-0.5).sp,
                        ),
                    )
                }

                // Live Health Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (savedUrl != null) TertiaryCyan.copy(alpha = 0.15f) else GlassFillLow)
                        .border(
                            1.dp,
                            if (savedUrl != null) TertiaryCyan.copy(alpha = 0.4f) else GlassBorderLow,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PulsingDot(
                            color = if (savedUrl != null) TertiaryCyan else TextMuted,
                            size = 8.dp,
                        )
                        Text(
                            text = if (savedUrl != null) "ENLACE ACTIVO" else "STANDBY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (savedUrl != null) TertiaryCyan else TextMuted,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Telemetry Cards Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Dns,
                    title = "Servidor",
                    value = if (savedUrl != null) "Configurado" else "No vinculado",
                    accent = if (savedUrl != null) TertiaryCyan else TextMuted,
                )
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.VpnKey,
                    title = "Token Cifrado",
                    value = if (hasToken) "Presente (AES)" else "Sin Token",
                    accent = if (hasToken) PrimaryPink else TextMuted,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Lan,
                    title = "Host Destino",
                    value = savedUrl ?: "Sin asignar",
                    accent = if (savedUrl != null) SecondaryPurple else TextMuted,
                )
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Speed,
                    title = "Protocolo",
                    value = if (savedUrl != null) "HTTP/2 + WS" else "Desconectado",
                    accent = if (savedUrl != null) TertiaryCyan else TextMuted,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Run Diagnostic Action Button
            val isConfigured = !savedUrl.isNullOrEmpty()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isConfigured) {
                            Brush.horizontalGradient(
                                colors = listOf(PrimaryPinkContainer, SecondaryPurple, TertiaryCyan),
                            )
                        } else {
                            Brush.horizontalGradient(
                                colors = listOf(GlassFillHigh, GlassFillHigh),
                            )
                        },
                    )
                    .border(
                        1.dp,
                        if (isConfigured) Color.Transparent else GlassBorderLow,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = PureWhite),
                        enabled = !running,
                        onClick = {
                            running = true
                            report = null
                            scope.launch {
                                val client = LinkClient(
                                    baseUrl = savedUrl ?: "http://127.0.0.1:0",
                                    deviceToken = tokenStore.getDeviceToken() ?: "",
                                    clientDeviceId = tokenStore.getClientDeviceId() ?: "",
                                )
                                val diagnostics = LinkDiagnostics(context)
                                report = diagnostics.runAll(client)
                                running = false
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = PureWhite,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Ejecutando Sockets & Tests...",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = PureWhite,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Ejecutar Diagnóstico Completo",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Diagnostic Results
            AnimatedVisibility(
                visible = report != null,
                enter = fadeIn() + slideInVertically(),
            ) {
                report?.let { rep ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "RESULTADOS DE AUDITORÍA",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TertiaryCyan,
                            letterSpacing = 1.5.sp,
                        )

                        rep.tests.forEach { testResult ->
                            val isOk = testResult.passed
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = GlassFillLow,
                                borderColor = if (isOk) TertiaryCyan.copy(alpha = 0.3f) else PrimaryPinkContainer.copy(alpha = 0.3f),
                                accent = if (isOk) TertiaryCyan else PrimaryPinkContainer,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (isOk) TertiaryCyan else PrimaryPinkContainer,
                                            modifier = Modifier.size(22.dp),
                                        )

                                        Column {
                                            Text(
                                                text = testResult.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = PureWhite,
                                            )
                                            Text(
                                                text = testResult.message,
                                                fontSize = 11.sp,
                                                color = TextSecondary,
                                            )
                                        }
                                    }

                                    Text(
                                        text = "${testResult.durationMs}ms",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = TextMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(100.dp))
        }
    }
}

@Composable
private fun TelemetryCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    accent: Color,
) {
    GlassCard(
        modifier = modifier,
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = TextMuted,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = PureWhite,
                maxLines = 1,
            )
        }
    }
}
