package dev.aigod;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GodServiceChatTurnTest {
    @Test
    void collapsesDuplicateSpamIntoOneMessage() {
        GodService.ChatTurn turn = new GodService.ChatTurn(UUID.randomUUID(), "hi", 1_000);

        turn.appendMessage("hi", 1_200);
        turn.appendMessage("hi", 1_450);

        assertEquals("""
                rapid messages from this player over 450ms, oldest to newest:
                - +0ms: hi (repeated 3 times through +450ms)""", turn.message());
    }

    @Test
    void keepsACompactBurstWithTheNewestFollowUp() {
        GodService.ChatTurn turn = new GodService.ChatTurn(UUID.randomUUID(), "one", 1_000);
        int index = 1;
        for (String message : new String[]{"two", "three", "four", "five", "six"}) {
            turn.appendMessage(message, 1_000 + 100L * index++);
        }

        assertEquals("""
                rapid messages from this player over 500ms, oldest to newest:
                - +0ms: one
                - +100ms: two
                - +200ms: three
                - +300ms: four
                - +500ms: six""", turn.message());
    }
}
