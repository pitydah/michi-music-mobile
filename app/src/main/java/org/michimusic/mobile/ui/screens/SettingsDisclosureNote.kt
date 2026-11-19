package org.michimusic.mobile.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.UI8VisualSpec
import org.michimusic.mobile.ui.theme.TextSecondary

/** UI8: progressive disclosure of explanatory text; never of state or safety blockers. */
@Composable
internal fun SettingsDisclosureNote(
    title: String,
    detail: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val reducedMotion = LocalVisualPreferences.current.reducedMotion
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (reducedMotion) Modifier
                else Modifier.animateContentSize(
                    animationSpec = tween(durationMillis = UI8VisualSpec.disclosureExpandMillis),
                )
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = UI8VisualSpec.minTouchTarget)
                .clip(RoundedCornerShape(UI8VisualSpec.disclosureCorner))
                .clickable(role = Role.Button, onClick = { expanded = !expanded })
                .semantics { stateDescription = if (expanded) "Expandido" else "Contraído" }
                .testTag(testTag)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = TertiaryCyan,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Ocultar explicación" else "Mostrar explicación",
                tint = TextSecondary,
            )
        }
        if (expanded) {
            Text(
                detail,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("${testTag}_detail"),
            )
        }
    }
}
