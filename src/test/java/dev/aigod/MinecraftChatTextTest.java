package dev.aigod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftChatTextTest {
    @Test
    void removesMarkdownThatMinecraftWouldShowLiterally() {
        assertEquals("daily challenge: gather 24 oak logs.",
                MinecraftChatText.fromModel("  ## **daily challenge:** gather `24 oak logs`.  "));
    }
}
