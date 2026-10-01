package it.sottovoce.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import it.sottovoce.app.data.AppTheme
import it.sottovoce.app.data.SunTimes
import it.sottovoce.app.data.ThemeSchedule
import it.sottovoce.app.data.ThemeSettings
import it.sottovoce.app.ui.svPalette
import it.sottovoce.app.ui.themeColors
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ThemeTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun at(hour: Int, minute: Int = 0, month: Int = 6, day: Int = 21) = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, rome)

    @Test fun tenDayAndTenNightThemes() {
        assertEquals(10, AppTheme.dayThemes.size)
        assertEquals(10, AppTheme.nightThemes.size)
        assertEquals(AppTheme.entries.size, AppTheme.entries.map { it.id }.distinct().size)
        assertTrue(AppTheme.isValidId("system"))
        assertFalse(AppTheme.isValidId("missing"))
    }

    @Test fun olderSingleThemeChoicesKeepTheirAppearance() {
        val system = ThemeSettings.fromLegacy("system")
        assertEquals(AppTheme.LIGHT, system.resolve(systemDark = false, now = at(12)))
        assertEquals(AppTheme.DARK, system.resolve(systemDark = true, now = at(12)))
        assertEquals("system", system.legacyId)
        val nord = ThemeSettings.fromLegacy("nord")
        assertEquals(AppTheme.NORD, nord.resolve(systemDark = false, now = at(12)))
        assertEquals("nord", nord.legacyId)
        val paper = ThemeSettings.fromLegacy("paper")
        assertEquals(AppTheme.PAPER, paper.resolve(systemDark = true, now = at(23)))
        assertEquals(ThemeSettings(), ThemeSettings.fromLegacy(null))
        assertEquals(AppTheme.DARK, ThemeSettings.fromLegacy("unknown").resolve(systemDark = true, now = at(12)))
    }

    @Test fun storedSlotsRoundTripAndAnOlderSingleThemeWins() {
        val settings = ThemeSettings(AppTheme.RISO, AppTheme.VELVET, auto = true, schedule = ThemeSchedule.FIXED)
        assertEquals(settings, ThemeSettings.of("riso", "velvet", true, "fixed", false, settings.legacyId))
        // An older version (or a restored backup) wrote a different single theme: it takes over.
        assertEquals(AppTheme.DUSK, ThemeSettings.of("riso", "velvet", true, "fixed", false, "dusk").selected)
        // Slots in the wrong family are ignored.
        assertEquals(ThemeSettings.fromLegacy("paper"), ThemeSettings.of("velvet", "riso", true, "fixed", false, "paper"))
    }

    @Test fun pickingAThemeFillsItsOwnSlot() {
        val start = ThemeSettings()
        val night = start.pick(AppTheme.PHOSPHOR)
        assertEquals(AppTheme.PHOSPHOR, night.night)
        assertEquals(start.day, night.day)
        assertTrue(night.manualNight)
        val day = night.pick(AppTheme.SWISS)
        assertEquals(AppTheme.SWISS, day.day)
        assertEquals(AppTheme.PHOSPHOR, day.night)
        assertFalse(day.manualNight)
    }

    @Test fun schedulesPickDayOrNight() {
        val fixed = ThemeSettings(auto = true, schedule = ThemeSchedule.FIXED)
        assertTrue(fixed.isNight(false, at(22)))
        assertTrue(fixed.isNight(false, at(6, 59)))
        assertFalse(fixed.isNight(true, at(7)))
        assertFalse(fixed.isNight(true, at(21, 59)))
        val sun = ThemeSettings(auto = true, schedule = ThemeSchedule.SUN)
        assertFalse(sun.isNight(false, at(13)))
        assertTrue(sun.isNight(false, at(22, 30)))
        assertTrue(sun.isNight(false, at(4)))
        assertTrue(sun.isNight(false, at(18, month = 12)))
        val manual = ThemeSettings(auto = false, manualNight = true)
        assertTrue(manual.isNight(false, at(12)))
    }

    @Test fun sunTimesAreRoughlyRightForItaly() {
        val (rise, set) = requireNotNull(SunTimes.of(LocalDate.of(2026, 6, 21), rome))
        assertTrue("alba $rise", rise in 4 * 60 + 50..6 * 60)
        assertTrue("tramonto $set", set in 20 * 60..21 * 60 + 15)
        val (winterRise, winterSet) = requireNotNull(SunTimes.of(LocalDate.of(2026, 12, 21), rome))
        assertTrue("alba $winterRise", winterRise in 7 * 60..8 * 60)
        assertTrue("tramonto $winterSet", winterSet in 16 * 60 + 15..17 * 60)
    }

    @Test fun allThemesKeepTextAndControlsReadable() {
        AppTheme.entries.forEach { theme ->
            val p = svPalette(theme)
            assertEquals(theme.dark, p.dark)
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
