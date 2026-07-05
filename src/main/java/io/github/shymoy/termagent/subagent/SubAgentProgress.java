
package io.github.shymoy.termagent.subagent;

/**

 * 报告正在运行的子代理的进度。每个工具调用发出一次

 * 当代理完成时（使用 {@code done == true}）一次。

 *

 * @param agentType   子代理的规格名称（e.g."plan"、"explore"）

 * @param description  子代理任务的简短描述

 * @param toolName     刚刚运行的工具的名称，或完成时的 {@code null}

 * @param toolOutput   工具输出摘要，或完成时的 {@code null}

 * @param toolError   工具调用是否返回错误

 * @param done        分代理是否完成

 * @param toolCount    迄今为止进行的工具调用总数

 * @param totalTime   wall-clock 自子代理启动以来经过的秒数

 */
public record SubAgentProgress(
        String agentType,
        String description,
        String toolName,
        String toolOutput,
        boolean toolError,
        boolean done,
        int toolCount,
        double totalTime
) {}

