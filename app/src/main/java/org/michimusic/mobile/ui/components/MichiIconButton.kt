package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun MichiIconButton(
    icon: ImageVector,
    iconSize: Dp,
    tint: Color = TextSecondary,
    onClick: () -> Unit = {},
) {
    IconButton(onClick = onClick, modifier = Modifier.size(iconSize + 16.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
