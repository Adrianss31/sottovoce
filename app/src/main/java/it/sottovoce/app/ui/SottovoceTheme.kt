package it.sottovoce.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import it.sottovoce.app.data.AppTheme

private data class Palette(
    val background: Long, val surface: Long, val panel: Long, val text: Long, val muted: Long,
    val primary: Long, val secondary: Long, val tertiary: Long,
    val primaryPanel: Long, val secondaryPanel: Long, val tertiaryPanel: Long,
)

/** Each theme owns every role: Material defaults must not introduce unrelated purple surfaces. */
fun themeColors(theme: AppTheme): ColorScheme {
    val p = when (theme) {
        AppTheme.PAPER -> Palette(0xFFF7F0E3, 0xFFFFFAF1, 0xFFEDE3D3, 0xFF302A25, 0xFF6D6258,
            0xFF8A4535, 0xFF3F6864, 0xFF68507C, 0xFFF0D8C6, 0xFFD8E7E1, 0xFFE8DEEF)
        AppTheme.GRAPHITE -> Palette(0xFF24252B, 0xFF2D2E36, 0xFF383A45, 0xFFF0EDF5, 0xFFC7C2D1,
            0xFFC4B5FD, 0xFF8BD5CA, 0xFFE9B680, 0xFF443D5F, 0xFF294A47, 0xFF51402C)
        AppTheme.NORD -> Palette(0xFF2E3440, 0xFF343C4A, 0xFF434C5E, 0xFFECEFF4, 0xFFCBD3E0,
            0xFF88C0D0, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF334F5C, 0xFF3D4D35, 0xFF524833)
        AppTheme.DUSK -> Palette(0xFF29232E, 0xFF342C3B, 0xFF453749, 0xFFF7EAF2, 0xFFD2BFCD,
            0xFFF2B8A0, 0xFFC4B0E5, 0xFF9BCBBD, 0xFF573B39, 0xFF453854, 0xFF314C46)
        AppTheme.PETROL -> Palette(0xFF102D34, 0xFF183942, 0xFF24454E, 0xFFF4EEDF, 0xFFBBCBCB,
            0xFFE8C789, 0xFF8ED2C7, 0xFFB8C5EB, 0xFF4B432D, 0xFF244D49, 0xFF34435D)
        AppTheme.DARK -> Palette(0xFF1D211E, 0xFF1D211E, 0xFF292E29, 0xFFF3EFE5, 0xFFB6BCAE,
            0xFFB1D2A5, 0xFFD1BCE0, 0xFFE3B786, 0xFF354531, 0xFF493C51, 0xFF51412D)
        AppTheme.LIGHT, AppTheme.SYSTEM -> Palette(0xFFF8F5EE, 0xFFF8F5EE, 0xFFF0ECE3, 0xFF17231C, 0xFF60645E,
            0xFF234D38, 0xFF685078, 0xFF79512B, 0xFFE7EDE2, 0xFFF1EAF7, 0xFFF1DFC7)
    }
    val base = if (theme.dark) darkColorScheme() else lightColorScheme()
    val bg = Color(p.background); val surface = Color(p.surface); val panel = Color(p.panel)
    val text = Color(p.text); val muted = Color(p.muted)
    val onAccent = if (theme.dark) bg else Color.White
    return base.copy(
        primary = Color(p.primary), onPrimary = onAccent,
        primaryContainer = Color(p.primaryPanel), onPrimaryContainer = text,
        secondary = Color(p.secondary), onSecondary = onAccent,
        secondaryContainer = Color(p.secondaryPanel), onSecondaryContainer = text,
        tertiary = Color(p.tertiary), onTertiary = onAccent,
        tertiaryContainer = Color(p.tertiaryPanel), onTertiaryContainer = text,
        background = bg, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = panel, onSurfaceVariant = muted, surfaceTint = Color.Transparent,
        surfaceDim = bg, surfaceBright = panel, surfaceContainerLowest = bg,
        surfaceContainerLow = surface, surfaceContainer = lerp(surface, panel, .35f),
        surfaceContainerHigh = lerp(surface, panel, .7f), surfaceContainerHighest = panel,
        outline = lerp(muted, surface, .3f), outlineVariant = lerp(muted, surface, .75f),
        inverseSurface = text, inverseOnSurface = bg,
        inversePrimary = Color(p.primaryPanel),
        error = if (theme.dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
        onError = if (theme.dark) Color(0xFF690005) else Color.White,
        errorContainer = if (theme.dark) Color(0xFF93000A) else Color(0xFFFFDAD6),
        onErrorContainer = if (theme.dark) Color(0xFFFFDAD6) else Color(0xFF410002),
        scrim = Color.Black,
    )
}

@Composable
fun ThemePicker(selectedId: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val systemDark = isSystemInDarkTheme()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Aspetto") }, text = {
        LazyColumn(Modifier.testTag("theme_options").selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Palette complete per dare una personalità diversa al tuo scaffale.", style = MaterialTheme.typography.bodyMedium) }
            items(AppTheme.entries, key = { it.id }) { theme ->
                val colors = themeColors(AppTheme.resolve(theme.id, systemDark))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .selectable(selected = selectedId == theme.id, role = Role.RadioButton,
                        onClick = { onSelect(theme.id) }).padding(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedId == theme.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(theme.title, style = MaterialTheme.typography.titleSmall)
                            Text(theme.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ThemePreview(colors)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } })
}

@Composable
private fun ThemePreview(colors: ColorScheme) {
    // This is a decorative miniature; the enclosing radio row provides the accessible label.
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }.clip(RoundedCornerShape(12.dp))
        .background(colors.background).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp, 52.dp).clip(RoundedCornerShape(6.dp)).background(colors.secondaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Headphones, null, tint = colors.secondary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sottovoce", color = colors.onBackground, fontFamily = FontFamily.Serif,
                style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(colors.primary, colors.secondary, colors.tertiary).forEach { color ->
                    Box(Modifier.size(9.dp).background(color, CircleShape))
                }
            }
            Box(Modifier.fillMaxWidth().height(4.dp).background(colors.surfaceVariant, CircleShape)) {
                Box(Modifier.fillMaxWidth(.55f).height(4.dp).background(colors.tertiary, CircleShape))
            }
        }
        Box(Modifier.size(32.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
        }
    }
}
