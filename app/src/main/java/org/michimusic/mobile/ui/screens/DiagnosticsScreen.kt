package org.michimusic.mobile.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline

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
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MichiSpacing.screenHorizontal)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TELEMETRÍA",
                        style = MichiTypography.screenEyebrow,
                    )
                    Text(
                        text = "Diagnóstico Michi Link",
                        style = MichiTypography.sectionTitle,
                        maxLines = 2,
                    )
                }

                // Live Health Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AeroGraphiteRaised)
                        .border(
                            1.dp,
                            AeroHairline,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(8.dp).background(
                                if (!savedUrl.isNullOrBlank()) TertiaryCyan else TextMuted,
                                CircleShape,
                            ),
                        )
                        Text(
                            text = if (!savedUrl.isNullOrBlank()) "CONFIGURADO" else "SIN CONFIGURAR",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!savedUrl.isNullOrBlank()) TertiaryCyan else TextMuted,
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
                    value = if (!savedUrl.isNullOrBlank()) "Configurado" else "No vinculado",
                    accent = if (!savedUrl.isNullOrBlank()) TertiaryCyan else TextMuted,
                )
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.VpnKey,
                    title = "Credencial local",
                    value = if (hasToken) "Guardada" else "Sin credencial",
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
                    value = savedUrl?.takeIf { it.isNotBlank() } ?: "Sin asignar",
                    accent = if (!savedUrl.isNullOrBlank()) SecondaryPurple else TextMuted,
                )
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Speed,
                    title = "Protocolo",
                    value = if (savedUrl.isNullOrBlank()) "Sin configurar" else "Pendiente de verificar",
                    accent = TextMuted,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Run Diagnostic Action Button
            val isConfigured = !savedUrl.isNullOrBlank()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isConfigured) {
                            Brush.horizontalGradient(
                                colors = listOf(PrimaryPinkContainer, PrimaryPinkContainer),
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
                        enabled = !running && isConfigured,
                        onClick = {
                            val targetUrl = savedUrl?.takeIf { it.isNotBlank() } ?: return@clickable
                            running = true
                            report = null
                            scope.launch {
                                val client = LinkClient(
                                    baseUrl = targetUrl,
                                    deviceToken = tokenStore.getDeviceToken() ?: "",
                                    clientDeviceId = tokenStore.getClientDeviceId() ?: "",
                                )
                                val diagnostics = LinkDiagnostics(context)
                                try {
                                    report = diagnostics.runAll(client)
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    Toast.makeText(context, "No se pudo completar el diagnóstico", Toast.LENGTH_LONG).show()
                                } finally {
                                    running = false
                                }
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
                            text = if (isConfigured) "Ejecutar diagnóstico completo" else "Configura un servidor para diagnosticar",
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

                        Spacer(Modifier.height(16.dp))

                        // Actions: Copy Redacted Report & Clear
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val formatted = DiagnosticsRedactor.formatExportReport(rep)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("michi_diagnostics", formatted)
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "Informe expurgado copiado al portapapeles", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, TertiaryCyan.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = TertiaryCyan,
                                ),
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Copiar Informe", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    report = null
                                    Toast.makeText(context, "Diagnósticos reiniciados", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderLow),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = TextMuted,
                                ),
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Limpiar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
