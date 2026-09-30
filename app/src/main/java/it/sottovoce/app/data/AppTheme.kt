package it.sottovoce.app.data

/** Stable IDs are shared by settings, persistence and the backup format. */
enum class AppTheme(val id: String, val title: String, val description: String, val dark: Boolean) {
    SYSTEM("system", "Come il sistema", "Chiaro o scuro secondo il dispositivo", false),
    LIGHT("light", "Chiaro", "Il classico Sottovoce: crema e salvia", false),
    DARK("dark", "Scuro", "Il classico Sottovoce: bosco e avorio", true),
    PAPER("paper", "Carta", "Avorio, terracotta e verde inchiostro", false),
    GRAPHITE("graphite", "Grafite", "Carbone, lavanda e menta", true),
    NORD("nord", "Nord", "Ardesia, ghiaccio e verde muschio", true),
    DUSK("dusk", "Crepuscolo", "Prugna, pesca e giada", true),
    PETROL("petrol", "Petrolio", "Blu profondo, oro e acquamarina", true);

    companion object {
        fun fromId(id: String): AppTheme? = entries.firstOrNull { it.id == id }
        fun resolve(id: String, systemDark: Boolean): AppTheme {
            val theme = fromId(id) ?: SYSTEM
            return if (theme == SYSTEM) { if (systemDark) DARK else LIGHT } else theme
        }
    }
}
