// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.conversation;

/**
 * 保存模型 thinking/reasoning 内容。
 * signature 用于需要回传思考签名的模型协议，例如 Anthropic thinking block。
 */
public record ThinkingBlock(String thinking, String signature) {}
