package dev.aigod;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GodServiceChatTurnTest {
    @Test
    void combinesNearbyMessagesWithoutCountingSpam() {
        GodService.ChatTurn turn = new GodService.ChatTurn(UUID.randomUUID(), "hi", 1_000);

        turn.appendMessage("hi", 1_200);
        turn.appendMessage("what is the goal", 1_450);

        assertEquals("hi\nwhat is the goal", turn.message());
    }
}
