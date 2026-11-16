package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.michimusic.mobile.library.LibraryScopeResolution
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * K48-R02: the selector says what actually happened to the library selection.
 *
 * A failed read is reported as a failed read (never as an `All` choice) and a failed write is
 * reported as an unsaved selection. Both are retryable by the reader; nothing here names a
 * provider, a path or an exception.
 */
enum class LibraryScopeNotice { RESTORING, NOT_RESTORED, NOT_SAVED }

fun LibraryScopeResolution.notice(): LibraryScopeNotice? = when (this) {
    is LibraryScopeResolution.Loading -> LibraryScopeNotice.RESTORING
    is LibraryScopeResolution.Ready -> null
    is LibraryScopeResolution.ReadFailed -> LibraryScopeNotice.NOT_RESTORED
    is LibraryScopeResolution.WriteFailed -> LibraryScopeNotice.NOT_SAVED
}

fun LibraryScopeNotice.message(): String = when (this) {
    LibraryScopeNotice.RESTORING -> "Recuperando la biblioteca guardada…"
    LibraryScopeNotice.NOT_RESTORED -> "No se pudo recuperar la selección"
    LibraryScopeNotice.NOT_SAVED -> "Selección no guardada"
}

/** Only the recoverable notices offer a retry; restoring is not a failure. */
fun LibraryScopeNotice.retryLabel(): String? = when (this) {
    LibraryScopeNotice.RESTORING -> null
    LibraryScopeNotice.NOT_RESTORED,
    LibraryScopeNotice.NOT_SAVED,
    -> "Reintentar"
}

@Composable
fun LibraryScopeNoticeRow(
    notice: LibraryScopeNotice,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .testTag("library_scope_notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = notice.message(),
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).testTag("library_scope_notice_text"),
        )
        notice.retryLabel()?.let { label ->
            TextButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("library_scope_notice_retry"),
            ) {
                Text(label, color = TertiaryCyan, fontSize = 12.sp)
            }
        }
    }
}

/** Store-bound variant: retrying always re-runs the operation that failed. */
@Composable
fun LibraryScopeStatus(store: LibraryScopeStore, modifier: Modifier = Modifier) {
    val resolution by store.resolution.collectAsStateWithLifecycle()
    val notice = resolution.notice() ?: return
    LibraryScopeNoticeRow(
        notice = notice,
        onRetry = {
            when (notice) {
                LibraryScopeNotice.NOT_RESTORED -> store.retryLoading()
                LibraryScopeNotice.NOT_SAVED -> store.retryPersist()
                LibraryScopeNotice.RESTORING -> Unit
            }
        },
        modifier = modifier,
    )
}
