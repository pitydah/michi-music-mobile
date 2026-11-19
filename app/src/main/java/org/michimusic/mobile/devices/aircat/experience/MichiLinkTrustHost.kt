package org.michimusic.mobile.devices.aircat.experience

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.michimusic.mobile.devices.aircat.AirCatHomeTrustAdmin

/**
 * A71-09: wires the Home trust screen to the encrypted inventory.
 *
 * The split mirrors [AirCatExperienceHost]: this file only owns the scope, the
 * controller lifetime and the effect that performs the single read. Rendering
 * stays in [MichiLinkTrustContent], which receives immutable state and intents
 * and therefore cannot read the Keystore or revoke anything by itself.
 */
@Composable
internal fun MichiLinkTrustHost(
    store: AirCatHomeTrustAdmin,
    onLinkHome: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val admin = remember(store) { MichiLinkTrustAdmin(store) }
    val controller = remember(admin) { MichiLinkTrustController(scope, admin) }
    val state by controller.state.collectAsStateWithLifecycle()

    LaunchedEffect(controller) {
        controller.dispatch(MichiLinkTrustIntent.Refresh)
    }

    BackHandler(enabled = true) {
        if (state.pending != null) {
            controller.dispatch(MichiLinkTrustIntent.CancelRevocation)
        } else {
            onClose()
        }
    }

    MichiLinkTrustContent(
        state = state,
        onIntent = controller::dispatch,
        onLinkHome = onLinkHome,
        onClose = onClose,
        modifier = modifier,
    )
}
