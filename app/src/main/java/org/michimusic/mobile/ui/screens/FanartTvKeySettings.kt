package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.mobile.artist.artwork.ArtworkProviderKeys

/**
 * fanart.tv issues two keys and its terms require both to be able to travel together:
 * the project key identifies this application, and the personal key belongs to whoever
 * is using it — "this should be sent in addition to your project key".
 *
 * So the personal field is not an alternative to the project key; it is an addition.
 * Both are stored encrypted, never logged, and the disclosure above them names the site
 * and says where the images come from, which is the other thing the terms require.
 */
@Composable
internal fun FanartTvKeySettings() {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val stored = produceState(initialValue = ArtworkProviderKeys.Fanart(null, null), app) {
        value = withContext(Dispatchers.IO) { ArtworkProviderKeys.fanart(app) }
    }
    var projectKey by remember(stored.value.projectKey) { mutableStateOf(stored.value.projectKey.orEmpty()) }
    var personalKey by remember(stored.value.personalKey) { mutableStateOf(stored.value.personalKey.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    SettingsSectionCard(
        icon = Icons.Default.Lock,
        title = "Proveedores manuales",
        subtitle = "Nunca buscan solos y no declaran licencia",
        accent = SecondaryPurple,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Text("fanart.tv", color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Las imágenes de fanart.tv proceden de fanart.tv y de sus colaboradores. Puedes " +
                "usar tu propia clave personal: se envía junto a la clave del proyecto, " +
                "nunca en lugar de ella. Ninguna de las dos se muestra ni se registra. " +
                "Se solicitan a fanart.tv/get-an-api-key/.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        OutlinedTextField(
            value = projectKey,
            onValueChange = { projectKey = it },
            label = { Text("Clave del proyecto") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("fanart_project_key"),
        )
        OutlinedTextField(
            value = personalKey,
            onValueChange = { personalKey = it },
            label = { Text("Tu clave personal (opcional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("fanart_personal_key"),
        )
        Text(
            if (projectKey.isBlank() && personalKey.isBlank()) {
                "Sin clave del proyecto, fanart.tv no se consulta: el botón de búsqueda " +
                    "sigue funcionando con los demás proveedores."
            } else {
                "Sin clave del proyecto no se consulta fanart.tv, aunque haya una personal."
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        TextButton(
            enabled = !saving,
            modifier = Modifier.heightIn(min = 48.dp).testTag("fanart_keys_save"),
            onClick = {
                scope.launch {
                    saving = true
                    try {
                        val ok = withContext(Dispatchers.IO) {
                            ArtworkProviderKeys.saveFanart(app, projectKey, personalKey)
                        }
                        status = if (ok) {
                            "Claves guardadas en este dispositivo."
                        } else {
                            "No se pudo guardar: el almacén seguro de este dispositivo no está disponible."
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        status = "No se pudo guardar: el almacén seguro de este dispositivo no está disponible."
                    } finally {
                        saving = false
                    }
                }
            },
        ) { Text("Guardar claves", color = TertiaryCyan) }
        status?.let { Text(it, color = TertiaryCyan, style = MaterialTheme.typography.bodySmall) }
    }
    }
}
