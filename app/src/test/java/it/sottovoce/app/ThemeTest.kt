package it.sottovoce.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import it.sottovoce.app.data.AppTheme
import it.sottovoce.app.ui.themeColors
import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    @Test fun systemModeFollowsDeviceButNamedThemesKeepTheirAppearance() {
        assertEquals(AppTheme.LIGHT, AppTheme.resolve("system", false))
        assertEquals(AppTheme.DARK, AppTheme.resolve("system", true))
        assertEquals(AppTheme.PAPER, AppTheme.resolve("paper", true))
        assertEquals(AppTheme.NORD, AppTheme.resolve("nord", false))
        assertEquals(AppTheme.DARK, AppTheme.resolve("unknown", true))
    }

    @Test fun allThemesKeepTextAndControlsReadable() {
        AppTheme.entries.filter { it != AppTheme.SYSTEM }.forEach { theme ->
            val c = themeColors(theme)
            listOf(c.onBackground to c.background, c.onSurface to c.surface,
                c.onSurfaceVariant to c.surfaceVariant, c.onPrimary to c.primary,
                c.onPrimaryContainer to c.primaryContainer,
                c.onSecondaryContainer to c.secondaryContainer,
                c.onTertiaryContainer to c.tertiaryContainer,
                c.primary to c.surface, c.secondary to c.surface, c.tertiary to c.surface,
                c.onSurface to c.surfaceContainerHighest).forEach { (text, background) ->
                assertTrue("${theme.title}: contrast ${contrast(text, background)}", contrast(text, background) >= 4.5f)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val aa = a.luminance(); val bb = b.luminance()
        return (maxOf(aa, bb) + .05f) / (minOf(aa, bb) + .05f)
    }
}
