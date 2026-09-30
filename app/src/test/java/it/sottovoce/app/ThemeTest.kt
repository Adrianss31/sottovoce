package it.sottovoce.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import it.sottovoce.app.data.AppTheme
import it.sottovoce.app.ui.svPalette
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
            val p = svPalette(theme)
            // Body text on every surface, secondary text on the background, text on accent controls.
            listOf(p.ink to p.bg, p.ink to p.surface, p.ink to p.surface2, p.ink2 to p.bg, p.onAccent to p.accent)
                .forEach { (text, background) ->
                    assertTrue("${theme.title}: contrast ${contrast(text, background)}", contrast(text, background) >= 4.5f)
                }
            // Accent as a graphical object (rings, progress, selected states) on the background.
            assertTrue("${theme.title}: accent ${contrast(p.accent, p.bg)}", contrast(p.accent, p.bg) >= 3f)
            val c = themeColors(theme)
            assertEquals(p.bg, c.background)
            assertTrue(contrast(c.onPrimary, c.primary) >= 4.5f)
            assertTrue(contrast(c.onSurface, c.surface) >= 4.5f)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val aa = a.luminance(); val bb = b.luminance()
        return (maxOf(aa, bb) + .05f) / (minOf(aa, bb) + .05f)
    }
}
