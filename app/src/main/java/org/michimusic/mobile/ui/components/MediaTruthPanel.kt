package org.michimusic.mobile.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Warning
import org.michimusic.mobile.screens.MediaLibraryState
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite

/** Android 13+ split media permission; Android 12 still grants the legacy storage one. */
fun requiredAudioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun hasAudioPermission(context: Context): Boolean =
    context.checkSelfPermission(requiredAudioPermission()) == PackageManager.PERMISSION_GRANTED

/**
 * Re-reads the library when the real permission no longer matches the rendered state:
 * granted while the panel says denied (came back from Settings) or revoked while the
 * library still claims to be ready. Runs only on ON_RESUME, so it cannot loop.
 */
@Composable
fun MediaPermissionWatcher(
    state: MediaLibraryState,
    onMismatch: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val stateNow by rememberUpdatedState(state)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val granted = hasAudioPermission(context)
            val mismatch = (granted && stateNow == MediaLibraryState.PERMISSION_DENIED) ||
                (!granted && stateNow != MediaLibraryState.PERMISSION_DENIED)
            if (mismatch) onMismatch()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * One Aero surface for the two states that are not "the user has no music": a denied
 * permission and a failed MediaStore query. The empty-library design is only allowed
 * after a successful query, so this panel never renders for EMPTY or READY.
 */
@Composable
fun MediaTruthPanel(
    state: MediaLibraryState,
    onPermissionAvailable: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state != MediaLibraryState.PERMISSION_DENIED && state != MediaLibraryState.QUERY_ERROR) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onPermissionAvailable() }

    // Coming back from the system dialog or from App info must re-read the grant and
    // refresh, instead of leaving a stale "denied" panel behind.
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                state == MediaLibraryState.PERMISSION_DENIED &&
                hasAudioPermission(context)
            ) {
                onPermissionAvailable()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val denied = state == MediaLibraryState.PERMISSION_DENIED
    val title = if (denied) "Michi necesita acceso a tu música" else "No se pudo leer la música del dispositivo"
    val description = if (denied) {
        "Sin permiso de audio la biblioteca local no puede leerse. El resto de Michi sigue disponible."
    } else {
        "MediaStore devolvió un error al consultar el dispositivo. Reintentar no borra ninguna canción."
    }
    val icon: ImageVector = if (denied) Icons.Filled.LibraryMusic else Icons.Filled.Warning

    AeroSurface(
        role = org.michimusic.mobile.ui.theme.AeroSurfaceRole.Content,
        finish = org.michimusic.mobile.ui.theme.AeroFinish.Satin,
        modifier = modifier.fillMaxWidth().testTag("media_truth_panel"),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = PrimaryPinkContainer, modifier = Modifier.size(28.dp))
            Text(title, style = MichiTypography.cardTitle, color = PureWhite)
            Text(description, style = MichiTypography.metadata, color = OnSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (denied) {
                    Button(
                        onClick = { permissionLauncher.launch(requiredAudioPermission()) },
                        modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                            .testTag("media_permission_grant"),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    ) {
                        Text("Conceder acceso", color = org.michimusic.mobile.ui.theme.SurfaceObsidian)
                    }
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                            .testTag("media_open_settings"),
                    ) {
                        Text("Abrir Ajustes")
                    }
                } else {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                            .testTag("media_retry"),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    ) {
                        Text("Reintentar", color = org.michimusic.mobile.ui.theme.SurfaceObsidian)
                    }
                }
            }
        }
    }
}
