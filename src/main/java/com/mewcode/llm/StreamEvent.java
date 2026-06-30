// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.llm;

import java.util.Map;

/**
 * LlmClient 向 Agent 输出的协议无关流式事件。
 * 各客户端负责把 Anthropic、OpenAI 等服务商的原始流事件转换成这些统一类型，
 * Agent 因而只需要处理一套事件模型，不必了解具体 API 协议。
 */
public sealed interface StreamEvent {

    // 模型正文的文本增量；Agent 会持续拼接，并立即转发给 UI 展示。
    record TextDelta(String text) implements StreamEvent {}

    // 模型思考内容的增量，用于流式展示，但不能单独作为完整 ThinkingBlock 保存。
    record ThinkingDelta(String text) implements StreamEvent {}

    // 一个完整思考块结束；signature 用于保存 Anthropic 等协议要求回传的思考签名。
    record ThinkingComplete(String thinking, String signature) implements StreamEvent {}

    // 工具调用开始，此时通常只有调用 ID 和工具名，参数还没有接收完整。
    record ToolCallStart(String toolId, String toolName) implements StreamEvent {}

    // 工具参数的原始增量，通常是尚不完整的 JSON 片段，不能在此阶段直接解析或执行。
    record ToolCallDelta(String text) implements StreamEvent {}

    // 工具参数已接收并解析完成；Agent 从此事件收集调用信息，之后交给执行器运行工具。
    record ToolCallComplete(String toolId, String toolName, Map<String, Object> arguments) implements StreamEvent {}

    // 本轮模型流正常结束，同时携带停止原因和服务商返回的 token 用量。
    record StreamEnd(String stopReason, int inputTokens, int outputTokens,
                     int cacheReadTokens, int cacheCreationTokens) implements StreamEvent {

        // 不支持 Prompt Cache 明细的服务商使用该构造方法，缓存相关 token 默认记为 0。
        public StreamEnd(String stopReason, int inputTokens, int outputTokens) {
            this(stopReason, inputTokens, outputTokens, 0, 0);
        }
    }

    // 流式请求或解析失败；Agent 收到后结束当前流，并根据错误类型决定重试还是终止。
    record Error(String message) implements StreamEvent {}
}
