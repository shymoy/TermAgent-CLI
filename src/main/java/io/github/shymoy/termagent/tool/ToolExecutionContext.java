package io.github.shymoy.termagent.tool;

import io.github.shymoy.termagent.agent.CancellationToken;

/** 将本次 Agent Run 的执行信号传递给具体工具，当前只包含取消 token。 */
public final class ToolExecutionContext {

    private final CancellationToken cancellationToken;

    public ToolExecutionContext(CancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }
}
