package it.sottovoce.app.data

import android.content.SharedPreferences
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Stable IDs are shared by settings, persistence and the backup format.
 * "light" and "dark" keep their historical IDs (now Crema and Bosco) so older
 * preferences and backups resolve to the same look.
 */
enum class AppTheme(val id: String, val title: String, val description: String, val dark: Boolean) {
    PAPER("paper", "Carta", "Avorio e terracotta", false),
    LIGHT("light", "Crema", "Crema e salvia", false),
    MATCHA("matcha", "Matcha", "Tè chiaro e muschio", false),
    SORBET("sorbet", "Sorbetto", "Pesca e lilla, caratteri pieni", false),
    PORCELAIN("porcelain", "Porcellana", "Bianco Delft, cobalto, Bodoni", false),
    HERBARIUM("herbarium", "Erbario", "Carta di stracci, grana, serif antico", false),
    SWISS("swiss", "Svizzero", "Griglia pura, rosso, niente angoli", false),
    GAZETTE("gazette", "Gazzetta", "Carta di giornale, retino, copertine a inchiostro", false),
    RISO("riso", "Risografia", "Inchiostri rosa e blu, grana, copertine ristampate", false),
    NOTEBOOK("notebook", "Quaderno", "Carta a righe, scrittura a mano", false),
    DARK("dark", "Bosco", "Bosco e avorio", true),
    GRAPHITE("graphite", "Grafite", "Carbone e lavanda", true),
    NORD("nord", "Nord", "Ardesia e ghiaccio", true),
    DUSK("dusk", "Crepuscolo", "Prugna e pesca", true),
    PETROL("petrol", "Petrolio", "Blu profondo e oro", true),
    VOID("void", "Vuoto", "Nero assoluto, un serif, nient’altro", true),
    VELVET("velvet", "Velluto", "Poltrone da cinema, oro, grana di pellicola", true),
    BLUEPRINT("blueprint", "Cianografia", "Griglia da disegno, linea bianca", true),
    PHOSPHOR("phosphor", "Fosfori", "CRT verde, righe di scansione, bagliore", true),
    DARKROOM("darkroom", "Camera oscura", "Luce rossa di sicurezza, ovunque", true);

    companion object {
        const val SYSTEM_ID = "system"
        val dayThemes: List<AppTheme> get() = entries.filter { !it.dark }
        val nightThemes: List<AppTheme> get() = entries.filter { it.dark }
        fun fromId(id: String?): AppTheme? = entries.firstOrNull { it.id == id }
        /** Accepts every theme ID plus the historical "system" choice. */
        fun isValidId(id: String?): Boolean = id == SYSTEM_ID || fromId(id) != null
    }
}

/** When the night theme takes over while automatic switching is on. */
enum class ThemeSchedule(val id: String, val title: String) {
    SYSTEM("system", "Come il sistema"),
    SUN("sun", "Tramonto → alba"),
    FIXED("fixed", "22:00 → 07:00");

    companion object {
        fun fromId(id: String?): ThemeSchedule? = entries.firstOrNull { it.id == id }
        const val FIXED_NIGHT_START = 22 * 60
        const val FIXED_NIGHT_END = 7 * 60
    }
}

/** A day theme, a night theme and the rule that picks between them. */
data class ThemeSettings(
    val day: AppTheme = AppTheme.PAPER,
    val night: AppTheme = AppTheme.DARK,
    val auto: Boolean = true,
    val schedule: ThemeSchedule = ThemeSchedule.SYSTEM,
    /** With automatic switching off, whether the night theme is the one in use. */
    val manualNight: Boolean = false,
) {
    init { require(!day.dark && night.dark) }

    fun isNight(systemDark: Boolean, now: ZonedDateTime): Boolean {
        if (!auto) return manualNight
        val minute = now.hour * 60 + now.minute
        return when (schedule) {
            ThemeSchedule.SYSTEM -> systemDark
            ThemeSchedule.FIXED -> minute >= ThemeSchedule.FIXED_NIGHT_START || minute < ThemeSchedule.FIXED_NIGHT_END
            // Polar day or night: keep the day theme in summer months, the night one otherwise.
            ThemeSchedule.SUN -> SunTimes.of(now.toLocalDate(), now.zone)?.let { (rise, set) -> minute < rise || minute >= set }
                ?: (now.monthValue !in 4..9)
        }
    }

    fun resolve(systemDark: Boolean, now: ZonedDateTime = ZonedDateTime.now()): AppTheme =
        if (isNight(systemDark, now)) night else day

    /** The theme chosen by hand: the one the settings list shows as selected. */
    val selected: AppTheme get() = if (manualNight) night else day

    /** Single-theme ID still stored for widgets, older versions and the backup format. */
    val legacyId: String get() =
        if (auto && schedule == ThemeSchedule.SYSTEM && day == AppTheme.LIGHT && night == AppTheme.DARK) AppTheme.SYSTEM_ID else selected.id

    /** Picking a theme assigns it to its own slot and makes that slot the manual choice. */
    fun pick(theme: AppTheme): ThemeSettings =
        if (theme.dark) copy(night = theme, manualNight = true) else copy(day = theme, manualNight = false)

    fun write(editor: SharedPreferences.Editor): SharedPreferences.Editor = editor
        .putString(KEY_LEGACY, legacyId).putString(KEY_DAY, day.id).putString(KEY_NIGHT, night.id)
        .putBoolean(KEY_AUTO, auto).putString(KEY_SCHEDULE, schedule.id).putBoolean(KEY_MANUAL_NIGHT, manualNight)

    companion object {
        const val KEY_LEGACY = "theme"
        private const val KEY_DAY = "themeDay"
        private const val KEY_NIGHT = "themeNight"
        private const val KEY_AUTO = "themeAuto"
        private const val KEY_SCHEDULE = "themeSchedule"
        private const val KEY_MANUAL_NIGHT = "themeNightManual"

        /** Settings that reproduce a single theme from 0.7.0 and earlier. */
        fun fromLegacy(id: String?): ThemeSettings {
            val theme = AppTheme.fromId(id)
            return when {
                id == null -> ThemeSettings()
                theme == null -> ThemeSettings(day = AppTheme.LIGHT, night = AppTheme.DARK, auto = true)
                theme.dark -> ThemeSettings(night = theme, auto = false, manualNight = true)
                else -> ThemeSettings(day = theme, auto = false)
            }
        }

        fun of(day: String?, night: String?, auto: Boolean?, schedule: String?, manualNight: Boolean?, legacy: String?): ThemeSettings {
            val d = AppTheme.fromId(day)?.takeIf { !it.dark }
            val n = AppTheme.fromId(night)?.takeIf { it.dark }
            if (d == null || n == null) return fromLegacy(legacy)
            val stored = ThemeSettings(d, n, auto ?: true, ThemeSchedule.fromId(schedule) ?: ThemeSchedule.SYSTEM, manualNight ?: false)
            // A single theme written by an older version (or a restore) wins over stale day/night slots.
            return if (legacy != null && AppTheme.isValidId(legacy) && legacy != stored.legacyId) fromLegacy(legacy) else stored
        }

        fun read(prefs: SharedPreferences): ThemeSettings = of(
            prefs.getString(KEY_DAY, null), prefs.getString(KEY_NIGHT, null),
            if (prefs.contains(KEY_AUTO)) prefs.getBoolean(KEY_AUTO, true) else null,
            prefs.getString(KEY_SCHEDULE, null),
            if (prefs.contains(KEY_MANUAL_NIGHT)) prefs.getBoolean(KEY_MANUAL_NIGHT, false) else null,
            prefs.getString(KEY_LEGACY, null),
        )
    }
}

/**
 * Approximate sunrise and sunset without asking for the location: the longitude is
 * estimated from the time zone's standard offset and the latitude defaults to 45° N.
 * Within about half an hour across Italy, which is enough to switch a theme.
 */
object SunTimes {
    const val DEFAULT_LATITUDE = 45.0

    fun estimatedLongitude(zone: ZoneId, date: LocalDate): Double =
        zone.rules.getStandardOffset(date.atStartOfDay(zone).toInstant()).totalSeconds / 3600.0 * 15.0

    /** Local minutes after midnight of sunrise and sunset, or null during polar day or night. */
    fun of(date: LocalDate, zone: ZoneId, latitude: Double = DEFAULT_LATITUDE,
        longitude: Double = estimatedLongitude(zone, date)): Pair<Int, Int>? {
        val g = 2 * PI / 365 * (date.dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val lat = Math.toRadians(latitude)
        val arg = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (arg !in -1.0..1.0) return null
        val ha = Math.toDegrees(acos(arg))
        val offset = zone.rules.getOffset(date.atTime(12, 0).atZone(zone).toInstant()).totalSeconds / 60.0
        val rise = 720 - 4 * (longitude + ha) - eqTime + offset
        val set = 720 - 4 * (longitude - ha) - eqTime + offset
        return rise.roundToInt().coerceIn(0, 24 * 60 - 1) to set.roundToInt().coerceIn(0, 24 * 60 - 1)
    }
}
