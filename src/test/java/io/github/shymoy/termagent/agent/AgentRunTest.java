package io.github.shymoy.termagent.agent;

import io.github.shymoy.termagent.config.ProviderConfig;
import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.llm.LlmClient;
import io.github.shymoy.termagent.llm.StreamEvent;
import io.github.shymoy.termagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunTest {

    @Test
    void runWithExternalQueueReturnsPromptlyAndEmitsLoopComplete() throws Exception {
        var streamStarted = new CountDownLatch(1);
        var releaseStream = new CountDownLatch(1);
        LlmClient client = new LlmClient() {
            @Override
            public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
                streamStarted.countDown();
                try {
                    assertTrue(releaseStream.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Interrupted while waiting to release fake stream");
                }

                var queue = new LinkedBlockingQueue<StreamEvent>();
                queue.offer(new StreamEvent.StreamEnd("end_turn", 12, 3));
                return queue;
            }
        };

        var cfg = new ProviderConfig();
        cfg.setModel("test-model");
        var agent = new Agent(client, new ToolRegistry(), "openai", cfg);
        var conversation = new ConversationManager();
        var agentEvents = new LinkedBlockingQueue<AgentEvent>(64);

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> agent.run(conversation, agentEvents));
        assertTrue(streamStarted.await(2, TimeUnit.SECONDS));

        releaseStream.countDown();

        AgentEvent.LoopComplete complete = pollLoopComplete(agentEvents);
        assertEquals(1, complete.totalTurns());
    }

    private static AgentEvent.LoopComplete pollLoopComplete(BlockingQueue<AgentEvent> queue) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            AgentEvent event = queue.poll(50, TimeUnit.MILLISECONDS);
            if (event instanceof AgentEvent.LoopComplete complete) {
                return complete;
            }
        }
        fail("Expected LoopComplete event");
        return null;
    }
}
