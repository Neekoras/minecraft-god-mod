package dev.aigod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayCycleTest {
    @Test
    void sundownFallsOnTheSameDay() {
        assertEquals(12_000, DayCycle.sundownOf(0));
        assertEquals(12_000, DayCycle.sundownOf(11_999));
        assertEquals(36_000, DayCycle.sundownOf(24_000));
        assertEquals(60_000, DayCycle.sundownOf(50_123));
    }

    @Test
    void daytimeEndsAtSundown() {
        assertTrue(DayCycle.beforeSundown(0));
        assertTrue(DayCycle.beforeSundown(11_999));
        assertFalse(DayCycle.beforeSundown(12_000));
        assertFalse(DayCycle.beforeSundown(23_999));
        assertTrue(DayCycle.beforeSundown(24_000));
    }

    @Test
    void daysCountFromZero() {
        assertEquals(0, DayCycle.day(23_999));
        assertEquals(1, DayCycle.day(24_000));
        assertEquals(2, DayCycle.day(48_500));
    }
}
