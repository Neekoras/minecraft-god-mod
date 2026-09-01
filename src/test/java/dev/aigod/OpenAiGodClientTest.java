package dev.aigod;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenAiGodClientTest {
    @Test
    void findsAnOrphanedToolCallInsideAnAsyncFailure() {
        Throwable failure = new CompletionException(new OpenAiGodClient.GodApiException(
                "No tool output found for function call call_6FCuNLQ1wxYXCps8oLXN5Qko."));

        assertEquals("call_6FCuNLQ1wxYXCps8oLXN5Qko",
                OpenAiGodClient.missingToolOutputCallId(failure));
    }

    @Test
    void ignoresUnrelatedFailures() {
        assertNull(OpenAiGodClient.missingToolOutputCallId(
                new OpenAiGodClient.GodApiException("OpenAI returned HTTP 500")));
    }
}
