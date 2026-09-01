package dev.aigod;

/** Pure helpers for Minecraft's 24000-tick day, driven by {@code ServerLevel#getDayTime()}. */
final class DayCycle {
    static final long DAY_LENGTH = 24_000;
    static final long SUNDOWN = 12_000;

    private DayCycle() {}

    static long day(long dayTime) {
        return dayTime / DAY_LENGTH;
    }

    static long timeOfDay(long dayTime) {
        return dayTime % DAY_LENGTH;
    }

    static boolean beforeSundown(long dayTime) {
        return timeOfDay(dayTime) < SUNDOWN;
    }

    /** Absolute dayTime tick at which the sun sets on the day containing {@code dayTime}. */
    static long sundownOf(long dayTime) {
        return day(dayTime) * DAY_LENGTH + SUNDOWN;
    }

    /** Human word for the current sky, for the god's live context. */
    static String phase(long dayTime) {
        long tod = timeOfDay(dayTime);
        if (tod < 1_000) return "dawn";
        if (tod < 6_000) return "morning";
        if (tod < 9_000) return "midday";
        if (tod < 12_000) return "late afternoon, sundown approaching";
        if (tod < 13_000) return "dusk";
        if (tod < 23_000) return "night";
        return "the last moments before dawn";
    }
}
