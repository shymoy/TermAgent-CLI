package io.github.shymoy.termagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 Agent Run 的协作式取消信号，同时负责通知已注册的资源清理回调。
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    // callbacks 的注册、注销和快照复制使用同一把锁，避免取消与注册并发时漏掉回调。
    private final List<Runnable> callbacks = new ArrayList<>();

    /** 首次调用时标记取消并执行回调；后续重复调用不会再次触发。 */
    public void cancel() {
        List<Runnable> toRun;
        synchronized (callbacks) {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            toRun = new ArrayList<>(callbacks);
            callbacks.clear();
        }

        // 在锁外执行，允许回调安全地注销或注册其他清理动作，也避免慢回调长期占用锁。
        for (Runnable callback : toRun) {
            runCallback(callback);
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Agent run cancelled");
        }
    }

    /**
     * 注册取消时需要执行的清理动作。若 token 已取消，回调会在当前线程立即执行；
     * 返回的 registration 用于在资源正常结束后注销回调，避免继续持有已失效资源。
     */
    public CancellationRegistration onCancel(Runnable callback) {
        synchronized (callbacks) {
            if (!cancelled.get()) {
                callbacks.add(callback);
                return () -> {
                    synchronized (callbacks) {
                        callbacks.remove(callback);
                    }
                };
            }
        }

        runCallback(callback);
        return () -> {};
    }

    private static void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // 取消回调是 best-effort；某个回调失败不应阻止其他清理动作。
        }
    }

    public interface CancellationRegistration extends AutoCloseable {
        @Override
        void close();
    }
}
