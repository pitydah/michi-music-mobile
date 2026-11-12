package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import org.michimusic.mobile.ui.components.AeroSurface
import androidx.compose.material3.TextButton
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary


import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing




/** Shared Aero layout primitives; no domain state, I/O, or persistence. */
@Composable
internal fun SettingsCategoryNavCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    iconRes: Int? = null,
    testTag: String? = null,
) {
    // Editorial index rows, not another set of identically filled glass cards.
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .clip(MichiShapes.sm)
                .clickable(role = Role.Button, onClick = onClick)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // One optical frame for every destination: canonical illustrations
            // and tinted vectors share the same 44 dp tile and baseline.
            Box(
                modifier = Modifier.size(44.dp).clip(MichiShapes.sm)
                    .background(accent.copy(alpha = 0.11f)),
                contentAlignment = Alignment.Center,
            ) {
                if (iconRes != null) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                    )
                } else {
                    Icon(icon, contentDescription = null, tint = accent,
                        modifier = Modifier.size(23.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = PureWhite, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, lineHeight = 21.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = TextSecondary, fontSize = 13.sp,
                    lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null,
                tint = TextSecondary, modifier = Modifier.size(21.dp))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp)
            .background(Color.White.copy(alpha = 0.09f)))
    }
}


@Composable
internal fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    // Sections use one anodized shell; controls inside retain their own semantics.
    AeroSurface(
        role = AeroSurfaceRole.Raised,
        finish = AeroFinish.Anodized,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(19.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = TextSecondary, fontSize = 12.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }


            Spacer(Modifier.height(16.dp))


            content()
        }
    }
}


@Composable
internal fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
    enabled: Boolean = true,
) {
    Box(modifier = Modifier.fillMaxWidth()
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (testTag != null) Modifier.testTag("${testTag}_switch") else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch,
                onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PureWhite,
                checkedTrackColor = TertiaryCyan,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = GlassFillHigh,
            ),
        )
    }
}
}


/**
 * Shows the state of the Ed25519 device identity that pairing and command
 * signing depend on. Resolution runs off the main thread, so a transient
 * Keystore failure must be recoverable here instead of forcing a restart.
 */
@Composable
internal fun IdentityStatusRow(
    state: org.michimusic.link.identity.IdentityState,
    onRetry: () -> Unit,
) {
    val title = when (state) {
        is org.michimusic.link.identity.IdentityState.Ready -> "Identidad lista"
        org.michimusic.link.identity.IdentityState.Loading -> "Cargando identidad…"
        is org.michimusic.link.identity.IdentityState.Failed -> "Identidad no disponible"
    }
    val detail = when (state) {
        is org.michimusic.link.identity.IdentityState.Ready ->
            "El dispositivo puede emparejarse y firmar comandos."
        org.michimusic.link.identity.IdentityState.Loading ->
            "Resolviendo la clave Ed25519 del dispositivo…"
        is org.michimusic.link.identity.IdentityState.Failed -> state.reason
    }
    val accent = when (state) {
        is org.michimusic.link.identity.IdentityState.Ready -> TertiaryCyan
        org.michimusic.link.identity.IdentityState.Loading -> TextMuted
        is org.michimusic.link.identity.IdentityState.Failed ->
            org.michimusic.mobile.ui.theme.ErrorColor
    }


    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MichiSpacing.minTouchTarget)
            .testTag("settings_identity_status"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = accent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state is org.michimusic.link.identity.IdentityState.Failed) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.testTag("settings_identity_retry"),
            ) {
                Text(
                    text = "Reintentar",
                    color = PrimaryPinkContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
