package org.michimusic.mobile.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.BuildConfig
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/** Public, verified destinations. Never transmit library or user identifiers. */
internal object SettingsSupportDestinations {
    const val USERNAME = "pitydah"
    const val PROFILE = "https://github.com/pitydah"
    const val REPOSITORY = "https://github.com/pitydah/michi-music-mobile"
    const val SPONSORS = "https://github.com/sponsors/pitydah"

    fun isAllowed(url: String): Boolean = url == PROFILE || url == REPOSITORY || url == SPONSORS
}

private fun openMichiExternalLink(context: Context, url: String) {
    // Allow only these exact HTTPS endpoints: never open an untrusted URI from a library tag.
    if (!SettingsSupportDestinations.isAllowed(url)) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No hay una aplicación para abrir el enlace.", Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "No se pudo abrir GitHub en este dispositivo.", Toast.LENGTH_LONG).show()
    }
}

/** Bottom-most Settings index destination; no in-app payment or WebView. */
@Composable
internal fun SettingsDonationCard(onOpenExternal: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val openUrl = onOpenExternal ?: remember(context) {
        { url: String -> openMichiExternalLink(context, url) }
    }
    AeroSurface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp)
            .testTag("settings_donate_card"),
        role = AeroSurfaceRole.Raised,
        finish = AeroFinish.Satin,
        shape = RoundedCornerShape(18.dp),
        onClick = { openUrl(SettingsSupportDestinations.SPONSORS) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp)
                    .background(PrimaryPinkContainer.copy(alpha = .14f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // Fluent Color Heart from the very same family used by FirstRunWizard.
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        org.michimusic.mobile.R.drawable.settings_fluent_heart,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Donar", style = MichiTypography.cardTitle, color = PureWhite,
                    fontWeight = FontWeight.SemiBold)
                Text("Apoya el desarrollo de Michi Music mediante GitHub Sponsors.",
                    style = MichiTypography.metadata, color = TextSecondary)
                Text("Aporte opcional · GitHub gestiona el pago",
                    fontSize = 11.sp, color = TextSecondary)
            }
            Icon(Icons.Default.OpenInNew, contentDescription = null,
                tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

/** About page is lightweight and offline: only explicit taps open GitHub. */
@Composable
internal fun SettingsAboutPanel(onOpenExternal: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val openUrl = onOpenExternal ?: remember(context) {
        { url: String -> openMichiExternalLink(context, url) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("settings_about_panel")) {
        AeroSurface(
            role = AeroSurfaceRole.Raised,
            finish = AeroFinish.Anodized,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        org.michimusic.mobile.R.drawable.wizard_brand_official,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).testTag("settings_about_brand_icon"),
                )
                Text("MICHI MUSIC MOBILE", style = MichiTypography.screenEyebrow,
                    color = PrimaryPinkContainer)
                Text("Michi Music Mobile", style = MichiTypography.sectionTitle,
                    color = PureWhite)
                Text("Versión ${BuildConfig.VERSION_NAME} · compilación ${BuildConfig.VERSION_CODE}",
                    style = MichiTypography.metadata, color = TextSecondary,
                    modifier = Modifier.testTag("settings_about_version"))
                Text("Desarrollado por Cristian Rosas · @${SettingsSupportDestinations.USERNAME}",
                    style = MichiTypography.metadata, color = TextSecondary,
                    modifier = Modifier.testTag("settings_about_developer"))
            }
        }
        SettingsAboutLink(
            icon = Icons.Default.PersonOutline,
            title = "Perfil de GitHub",
            subtitle = "@${SettingsSupportDestinations.USERNAME}",
            testTag = "settings_about_github",
            onClick = { openUrl(SettingsSupportDestinations.PROFILE) },
        )
        SettingsAboutLink(
            icon = Icons.Default.Code,
            title = "Código fuente",
            subtitle = "Repositorio de Michi Music Mobile en GitHub",
            testTag = "settings_about_repository",
            onClick = { openUrl(SettingsSupportDestinations.REPOSITORY) },
        )
        Text("Estos enlaces son externos. La aplicación no comparte tu biblioteca ni " +
            "solicita credenciales para abrirlos.",
            style = MichiTypography.metadata, color = TextSecondary)
    }
}

@Composable
private fun SettingsAboutLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
) {
    AeroSurface(
        role = AeroSurfaceRole.Content,
        finish = AeroFinish.Plain,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(testTag),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = PrimaryPinkContainer,
                modifier = Modifier.size(23.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MichiTypography.cardTitle, color = PureWhite)
                Text(subtitle, style = MichiTypography.metadata, color = TextSecondary)
            }
            Icon(Icons.Default.OpenInNew, contentDescription = null,
                tint = TextSecondary, modifier = Modifier.size(19.dp))
        }
    }
}
