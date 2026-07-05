

package com.mewcode.command;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**

 * 运行时上下文传递给命令处理程序，提供对当前命令的访问

 * 应用程序状态（权限模式、token计数、模型等）。

 */
public record CommandContext(
        String args,
        String workDir,
        String model,
        Supplier<String> permissionMode,
        IntSupplier toolCount,
        Supplier<int[]> tokenCount,
        Supplier<List<String>> memoryList,
        Runnable memoryClear,
        Supplier<String> sessionInfo,
        Supplier<List<String>> skillList,
        Supplier<String> mcpInfo,
        Supplier<String> sandboxStatus,
        Consumer<Integer> sandboxSwitch
) {}

