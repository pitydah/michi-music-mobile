package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.michimusic.mobile.ui.theme.MichiSpacing

@Composable
fun MichiScreen(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val base = Modifier
        .fillMaxSize()
        .padding(horizontal = MichiSpacing.lg)

    if (scrollable) {
        Box(
            modifier = modifier
                .then(base)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    } else {
        Box(
            modifier = modifier.then(base),
            content = content,
        )
    }
}
