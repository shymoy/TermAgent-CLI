
package com.mewcode.conversation;

/**
 * 保存模型 thinking/reasoning 内容。
 * signature 用于需要回传思考签名的模型协议，例如 Anthropic thinking block。
 */
public record ThinkingBlock(String thinking, String signature) {}
