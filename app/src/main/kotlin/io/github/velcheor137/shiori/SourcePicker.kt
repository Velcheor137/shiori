package io.github.velcheor137.shiori

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Переключатель источника: заметная плашка с иконкой и названием текущего источника, по нажатию —
 * список всех источников (их будет больше, поэтому список, а не две вкладки).
 */
@Composable
fun SourcePicker(modifier: Modifier = Modifier) {
    val app = LocalContext.current.shiori
    val source by app.source.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { open = true }.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(source.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(source.label, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                Text(source.kind, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ArrowDropDown, "Выбрать источник", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Source.entries.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.label, fontWeight = if (s == source) FontWeight.SemiBold else FontWeight.Normal)
                            Text(s.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(s.icon, null) },
                    trailingIcon = { if (s == source) Icon(Icons.Filled.Check, "Выбран", tint = MaterialTheme.colorScheme.primary) },
                    onClick = { open = false; app.setSource(s) },
                )
            }
        }
    }
}
