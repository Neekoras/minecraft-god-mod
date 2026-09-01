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
}
