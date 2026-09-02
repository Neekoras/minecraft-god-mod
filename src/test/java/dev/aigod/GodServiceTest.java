package dev.aigod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GodServiceTest {
    @Test
    void lowHealthTriggersOnceUntilThePlayerRecovers() {
        assertTrue(GodService.shouldTriggerLowHealth(2.0F, true, false));
        assertFalse(GodService.shouldTriggerLowHealth(2.0F, true, true));
        assertFalse(GodService.shouldTriggerLowHealth(0.0F, false, false));
        assertFalse(GodService.shouldTriggerLowHealth(2.5F, true, false));
    }
}
