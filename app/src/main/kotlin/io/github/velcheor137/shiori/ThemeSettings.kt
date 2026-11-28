package io.github.velcheor137.shiori

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val accentPresets = listOf(
    0xFF6B58A8, 0xFF7C4DFF, 0xFF3F7FD9, 0xFF0096C7, 0xFF00A39A, 0xFF2E9D57,
    0xFF8BB43A, 0xFFE0A100, 0xFFE8682A, 0xFFE0465A, 0xFFD94C9A, 0xFF8D6E63, 0xFF607D8B,
)

private val backgroundPresets = listOf(
    // Тёмные
    0xFF2F3136, 0xFF1E1F22, 0xFF121212, 0xFF000000, 0xFF1B2230, 0xFF1F2A24, 0xFF2A211C,
    // Светлые
    0xFFF7F5F0, 0xFFFFFFFF, 0xFFF3EAD7, 0xFFEAF0F4, 0xFFF1ECF7,
)

/** «Оформление»: акцентный цвет и фон приложения — образцы, свой цвет, сброс. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeColorsSection() {
    val store = LocalContext.current.shiori.store
    val colors by store.themeColors.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Акцентный цвет", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultSwatch(selected = colors.accent == null) { store.setThemeColors(colors.copy(accent = null)) }
            accentPresets.forEach { c -> Swatch(c, colors.accent == c) { store.setThemeColors(colors.copy(accent = c)) } }
            CustomSwatch(colors.accent?.takeIf { it !in accentPresets }) { picking = "accent" }
        }

        Text("Фон", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultSwatch(selected = colors.background == null) { store.setThemeColors(colors.copy(background = null)) }
            backgroundPresets.forEach { c -> Swatch(c, colors.background == c) { store.setThemeColors(colors.copy(background = c)) } }
            CustomSwatch(colors.background?.takeIf { it !in backgroundPresets }) { picking = "background" }
        }
        if (colors.background != null) {
            Text(
                "Свой фон заменяет выбор «Тёмная/Светлая»: тема подстраивается под его яркость. " +
                    "Контраст текста ${"%.1f".format(ThemePalette.contrast(scheme.onBackground, scheme.background))}:1, " +
                    "акцента ${"%.1f".format(ThemePalette.contrast(scheme.primary, scheme.background))}:1.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (colors != ThemeColors()) {
            TextButton(onClick = { store.setThemeColors(ThemeColors()) }) { Text("Сбросить цвета темы") }
        }
    }

    picking?.let { what ->
        val initial = (if (what == "accent") colors.accent else colors.background)
            ?.let(ThemePalette::color) ?: if (what == "accent") scheme.primary else scheme.background
        ColorPickerDialog(
            title = if (what == "accent") "Акцентный цвет" else "Цвет фона",
            initial = initial,
            onDismiss = { picking = null },
        ) { c ->
            picking = null
            val v = ThemePalette.argb(c)
            store.setThemeColors(if (what == "accent") colors.copy(accent = v) else colors.copy(background = v))
        }
    }
}

@Composable
private fun Swatch(argb: Long, selected: Boolean, onClick: () -> Unit) {
    val c = ThemePalette.color(argb)
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(c)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, "Выбран", Modifier.size(18.dp), tint = if (c.luminance() > 0.5f) Color.Black else Color.White)
    }
}

@Composable
private fun DefaultSwatch(selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Стандарт", style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CustomSwatch(custom: Long?, onClick: () -> Unit) {
    if (custom != null) {
        Row(verticalAlignment = Alignment.CenterVertically) { Swatch(custom, true, onClick) }
        return
    }
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
            .clickable(onClickLabel = "Свой цвет", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, "Свой цвет", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Свой цвет: тон, насыщенность, яркость и HEX, с предпросмотром. */
@Composable
fun ColorPickerDialog(title: String, initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    var hex by remember { mutableStateOf("%06X".format(initial.toArgb() and 0xFFFFFF)) }
    fun syncHex(c: Color) { hex = "%06X".format(c.toArgb() and 0xFFFFFF) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(current).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)))
                GradientSlider("Тон", h / 360f, Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) })) {
                    h = it * 360f; syncHex(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))))
                }
                GradientSlider(
                    "Насыщенность",
                    s,
                    Brush.horizontalGradient(listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 0f, v))), Color(android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, v))))),
                ) { s = it; syncHex(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))) }
                GradientSlider(
                    "Яркость",
                    v,
                    Brush.horizontalGradient(listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, 1f))))),
                ) { v = it; syncHex(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))) }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { t ->
                        hex = t.uppercase().removePrefix("#").filter { it in "0123456789ABCDEF" }.take(6)
                        if (hex.length == 6) {
                            val out = FloatArray(3)
                            android.graphics.Color.colorToHSV((0xFF000000 or hex.toLong(16)).toInt(), out)
                            h = out[0]; s = out[1]; v = out[2]
                        }
                    },
                    label = { Text("HEX") },
                    prefix = { Text("#") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Применить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun GradientSlider(label: String, value: Float, brush: Brush, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 10.dp).clip(RoundedCornerShape(5.dp)).background(brush))
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onChange)
    }
}
