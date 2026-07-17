package io.github.shymoy.termagent.agent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 Agent Run 的运行句柄。事件队列、后台线程和取消信号生命周期一致，
 * 都只属于这一轮运行，不应在不同运行之间复用。
 */
public final class AgentRunHandle {

    private final BlockingQueue<AgentEvent> queue;
    private final Thread thread;
    private final CancellationToken token;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public AgentRunHandle(BlockingQueue<AgentEvent> queue, Thread thread, CancellationToken token) {
        this.queue = queue;
        this.thread = thread;
        this.token = token;
    }

    public BlockingQueue<AgentEvent> queue() {
        return queue;
    }

    public Thread thread() {
        return thread;
    }

    public CancellationToken token() {
        return token;
    }

    /**
     * 请求协作式取消。token 负责让 Agent Loop 在下一个检查点停止，
     * interrupt 负责尽量唤醒 Java 层可中断的阻塞等待。
     */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }

        token.cancel();
        thread.interrupt();
    }
}
