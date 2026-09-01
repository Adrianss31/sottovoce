package it.sottovoce.app.playback

internal const val NIGHT_END_MINUTES = 6 * 60

internal fun smartRewindForPause(pausedForMs: Long): Long = when {
    pausedForMs < 30_000L -> 0L
    pausedForMs < 5 * 60_000L -> 5_000L
    pausedForMs < 60 * 60_000L -> 10_000L
    pausedForMs < 8 * 60 * 60_000L -> 20_000L
    else -> 30_000L
}

internal fun isNightListeningTime(minuteOfDay: Int, startMinutes: Int): Boolean =
    if (startMinutes < NIGHT_END_MINUTES) minuteOfDay in startMinutes until NIGHT_END_MINUTES
    else minuteOfDay >= startMinutes || minuteOfDay < NIGHT_END_MINUTES

internal fun nightSessionKey(localEpochDay: Long, minuteOfDay: Int, startMinutes: Int): Long =
    localEpochDay - if (startMinutes >= NIGHT_END_MINUTES && minuteOfDay < NIGHT_END_MINUTES) 1L else 0L
