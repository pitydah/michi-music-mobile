package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.michimusic.core.metadata.MetadataRawTagInventory
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.mobile.ui.theme.TextSecondary

/** Read-only: raw tags are not editable or source-certified. No I/O on composition. */
@Composable
internal fun MichiMetadataRawTagInventory(snapshot: MetadataSnapshot) {
    val report=remember(snapshot.target, snapshot.opaque) {
        MetadataRawTagInventory.inspect(snapshot)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        .testTag("metadata_raw_tag_inventory"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Etiquetas originales no interpretadas",style=MaterialTheme.typography.titleSmall)
        Text("Solo lectura. La presencia de una etiqueta no garantiza que el formato permita editarla o preservarla.",
            style=MaterialTheme.typography.bodySmall,color=TextSecondary)
        if (report.items.isEmpty()) {
            Text("No hay entradas auxiliares disponibles en esta lectura; esto no confirma que el archivo carezca de etiquetas desconocidas.",
                style=MaterialTheme.typography.bodySmall,color=TextSecondary)
        }
        report.items.forEach { item ->
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(item.key,style=MaterialTheme.typography.labelMedium)
                item.values.forEachIndexed { index,value ->
                    Text("${index+1}. $value",style=MaterialTheme.typography.bodySmall,
                        color=TextSecondary)
                }
                if (item.abbreviated) Text("Mostrando una muestra · ${item.totalValues} valor(es) en origen",
                    style=MaterialTheme.typography.labelSmall,color=TextSecondary)
            }
            HorizontalDivider()
        }
        if (report.hasUnshownKeys) Text(
            "Vista limitada a ${MetadataRawTagInventory.MAX_KEYS} de ${report.totalKeys} claves, para proteger el rendimiento móvil.",
            style=MaterialTheme.typography.labelSmall,color=TextSecondary)
    }
}
