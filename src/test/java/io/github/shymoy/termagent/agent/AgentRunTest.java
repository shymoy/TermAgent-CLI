package io.github.shymoy.termagent.agent;

import io.github.shymoy.termagent.config.ProviderConfig;
import io.github.shymoy.termagent.conversation.ConversationManager;
import io.github.shymoy.termagent.llm.LlmClient;
import io.github.shymoy.termagent.llm.StreamEvent;
import io.github.shymoy.termagent.tool.Tool;
import io.github.shymoy.termagent.tool.ToolCategory;
import io.github.shymoy.termagent.tool.ToolRegistry;
import io.github.shymoy.termagent.tool.ToolResult;
import io.github.shymoy.termagent.tool.impl.BashTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void cancelTokenStopsAgentBeforeNextIteration() throws Exception {
        var handleRef = new AtomicReference<AgentRunHandle>();
        var streamCalls = new AtomicInteger();
        var toolExecuted = new CountDownLatch(1);

        LlmClient client = new LlmClient() {
            @Override
            public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
                streamCalls.incrementAndGet();

                var queue = new LinkedBlockingQueue<StreamEvent>();
                queue.offer(new StreamEvent.ToolCallComplete("tool-1", "CancelAfterTool", Map.of()));
                queue.offer(new StreamEvent.StreamEnd("tool_calls", 12, 3));
                return queue;
            }
        };

        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "CancelAfterTool";
            }

            @Override
            public String description() {
                return "Cancels the current agent run.";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.READ;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of(
                        "name", name(),
                        "description", description(),
                        "input_schema", Map.of("type", "object", "properties", Map.of())
                );
            }

            @Override
            public ToolResult execute(Map<String, Object> args) {
                toolExecuted.countDown();
                handleRef.get().cancel();
                return ToolResult.success("cancelled");
            }
        });

        var cfg = new ProviderConfig();
        cfg.setModel("test-model");
        var agent = new Agent(client, registry, "openai", cfg);
        var conversation = new ConversationManager();
        var agentEvents = new LinkedBlockingQueue<AgentEvent>(64);

        AgentRunHandle handle = agent.runCancellable(conversation, agentEvents);
        handleRef.set(handle);

        assertTrue(toolExecuted.await(2, TimeUnit.SECONDS));
        handle.thread().join(2_000);
        assertFalse(handle.thread().isAlive());
        assertEquals(1, streamCalls.get());
        assertTrue(conversation.getMessages().stream()
                .noneMatch(message -> message.getToolResults() != null));
    }

    @Test
    void cancelledRunDoesNotCommitDelayedAssistantResponse() throws Exception {
        var streamEntered = new CountDownLatch(1);
        var streamCalls = new AtomicInteger();

        LlmClient client = new LlmClient() {
            @Override
            public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
                streamCalls.incrementAndGet();
                streamEntered.countDown();
                try {
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 模拟底层 SDK 被取消唤醒后仍返回迟到的完整响应。
                }

                var queue = new LinkedBlockingQueue<StreamEvent>();
                queue.offer(new StreamEvent.TextDelta("late answer"));
                queue.offer(new StreamEvent.StreamEnd("end_turn", 12, 3));
                return queue;
            }
        };

        var cfg = new ProviderConfig();
        cfg.setModel("test-model");
        var agent = new Agent(client, new ToolRegistry(), "openai", cfg);
        var conversation = new ConversationManager();
        var agentEvents = new LinkedBlockingQueue<AgentEvent>(64);

        AgentRunHandle handle = agent.runCancellable(conversation, agentEvents);

        assertTrue(streamEntered.await(2, TimeUnit.SECONDS));
        handle.cancel();
        handle.thread().join(2_000);

        assertFalse(handle.thread().isAlive());
        assertEquals(1, streamCalls.get());
        assertEquals(0, conversation.size());
    }

    @Test
    void agentRunHandleCancelCancelsTokenAndInterruptsThread() throws Exception {
        var streamEntered = new CountDownLatch(1);
        var streamInterrupted = new CountDownLatch(1);
        var interrupted = new AtomicBoolean(false);

        LlmClient client = new LlmClient() {
            @Override
            public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
                streamEntered.countDown();
                try {
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    streamInterrupted.countDown();
                    Thread.currentThread().interrupt();
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

        AgentRunHandle handle = agent.runCancellable(conversation, agentEvents);

        assertSame(agentEvents, handle.queue());
        assertTrue(streamEntered.await(2, TimeUnit.SECONDS));

        handle.cancel();
        handle.cancel();

        assertTrue(handle.token().isCancelled());
        assertTrue(streamInterrupted.await(2, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
    }

    @Test
    void cancellationTokenRunsCallbacksOnceAndRunsLateRegistrationsImmediately() {
        var token = new CancellationToken();
        var calls = new AtomicInteger();
        var registration = token.onCancel(calls::incrementAndGet);

        registration.close();
        token.cancel();
        assertEquals(0, calls.get());

        token.onCancel(calls::incrementAndGet);
        token.cancel();
        assertEquals(1, calls.get());
    }

    @Test
    void cancellingBashStopsProcessSkipsRemainingToolsAndDoesNotCommitToolResult(@TempDir Path tempDir)
            throws Exception {
        var streamCalls = new AtomicInteger();
        var secondToolExecuted = new AtomicBoolean(false);
        Path started = tempDir.resolve("started");

        LlmClient client = new LlmClient() {
            @Override
            public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
                streamCalls.incrementAndGet();

                var queue = new LinkedBlockingQueue<StreamEvent>();
                queue.offer(new StreamEvent.ToolCallComplete("bash-1", "Bash", Map.of(
                        "command", "touch started; sleep 30",
                        "timeout", 30
                )));
                queue.offer(new StreamEvent.ToolCallComplete("tool-2", "ShouldNotRun", Map.of()));
                queue.offer(new StreamEvent.StreamEnd("tool_calls", 12, 3));
                return queue;
            }
        };

        var registry = new ToolRegistry();
        registry.register(new BashTool(tempDir.toString()));
        registry.register(new Tool() {
            @Override
            public String name() {
                return "ShouldNotRun";
            }

            @Override
            public String description() {
                return "Should not be scheduled after cancellation.";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.COMMAND;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of(
                        "name", name(),
                        "description", description(),
                        "input_schema", Map.of("type", "object", "properties", Map.of())
                );
            }

            @Override
            public ToolResult execute(Map<String, Object> args) {
                secondToolExecuted.set(true);
                return ToolResult.success("should not run");
            }
        });

        var cfg = new ProviderConfig();
        cfg.setModel("test-model");
        var agent = new Agent(client, registry, "openai", cfg);
        var conversation = new ConversationManager();
        var agentEvents = new LinkedBlockingQueue<AgentEvent>(64);

        AgentRunHandle handle = agent.runCancellable(conversation, agentEvents);

        assertTrue(waitForFile(started, Duration.ofSeconds(2)));
        long startNanos = System.nanoTime();
        handle.cancel();
        handle.thread().join(3_000);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertFalse(handle.thread().isAlive());
        assertTrue(elapsedMillis < 3_000);
        assertEquals(1, streamCalls.get());
        assertFalse(secondToolExecuted.get());
        assertTrue(conversation.getMessages().stream()
                .noneMatch(message -> message.getToolResults() != null));
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

    private static boolean waitForFile(Path path, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) {
                return true;
            }
            Thread.sleep(50);
        }
        return Files.exists(path);
    }
}
