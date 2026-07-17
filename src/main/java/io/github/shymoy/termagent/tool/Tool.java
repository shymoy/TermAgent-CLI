

package io.github.shymoy.termagent.tool;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    ToolCategory category();

    Map<String, Object> schema();

    ToolResult execute(Map<String, Object> args);

    /**
     * 支持运行上下文的新入口。默认转调旧接口，使尚未接入取消机制的工具保持兼容。
     */
    default ToolResult execute(Map<String, Object> args, ToolExecutionContext context) {
        return execute(args);
    }

    default boolean shouldDefer() {
        return false;
    }
}
